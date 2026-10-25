package io.github.dolow.reconlens.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.ApprovalConfirmationRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 闸门 3：人工审核与草稿状态机。
 *
 * <pre>
 *  草稿 ──→ DRAFT ──submit──→ REVIEWING ──approve──→ ACTIVE
 *                               └────reject────→ REJECTED
 * </pre>
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li>{@code RuleCatalog.resolve} 只返回 ACTIVE —— 草稿与待审版本对业务不可见；</li>
 *   <li>审批必须绑定内容哈希：{@code reviewed_spec_sha} 与 {@code spec_json} 对不上时，{@code resolve} 会把这一版
 *       降级回 REVIEWING（见 {@code RuleCatalog#verifyReviewedContent}）；</li>
 *   <li>"能不能批"只读 {@link TrialState#approval()}，<b>本类不写自己的 if</b> —— 否则矩阵与代码会各说一套。</li>
 * </ol>
 *
 * <p>审批入口不对 Agent <b>单方</b>开放：Agent 能调用 {@link #confirmApproval}，但必须带上
 * 用户从 REST 领取的一次性确认码（{@link #issueConfirmation}）。没有码就发不出审批请求 ——
 * 这条约束不靠提示词，靠条件 UPDATE。
 */
@Service
public class RuleLifecycleService implements ApprovalRelay {

    private static final Logger log = LoggerFactory.getLogger(RuleLifecycleService.class);

    private final RuleRepository repository;
    private final RuleCatalog catalog;
    private final ApprovalConfirmationRepository confirmations;
    private final ObjectMapper json;
    private final String tokenSecret;

    public RuleLifecycleService(RuleRepository repository, RuleCatalog catalog,
                                ApprovalConfirmationRepository confirmations, ObjectMapper json,
                                ReconProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.confirmations = confirmations;
        this.json = json;
        String configured = properties.getReview().getTokenSecret();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.tokenSecret = HexFormat.of().formatHex(random);
            log.warn("未配置 recon.review.token-secret：审批 token 使用进程级随机密钥，重启后旧 token 全部失效");
        } else {
            this.tokenSecret = configured;
        }
    }

    /** 一版规则 + 它与基线之间的字段级差异。审核页需要的"规则 diff"就是它。 */
    public record Review(String code, int version, RuleSpec spec, RuleLifecycle lifecycle,
                         RuleChange change, String specSha256, String reviewedSpecSha, String baseDescription) {

        public boolean skippable() {
            return change.skippable();
        }
    }

    public Review review(String code, int version) {
        RuleRepository.RuleRow row = requireRow(code, version);
        RuleSpec spec = parse(row);
        Optional<RuleRepository.RuleRow> baseline = baselineRow(row);
        RuleChange change = RuleChange.between(baseline.map(this::parse).orElse(null), spec);
        String base = baseline.map(b -> b.code() + " v" + b.version()).orElse("(无基线，新规则)");
        return new Review(row.code(), row.version(), spec, row.lifecycle(), change,
                SpecHasher.sha256(row.specJson()), row.reviewedSpecSha(), base);
    }

    /** 草稿提交审核：DRAFT → REVIEWING。 */
    public void submit(String code, int version, String operator) {
        requireOperator(operator);
        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() != RuleLifecycle.DRAFT) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + row.lifecycle()
                    + "，只有 DRAFT 可以提交审核");
        }
        if (repository.transition(code, version, RuleLifecycle.DRAFT, RuleLifecycle.REVIEWING) == 0) {
            throw new RuleException("规则 " + code + " v" + version + " 状态已被并发修改，请刷新后重试");
        }
        catalog.invalidate(code);
        log.info("规则 {} v{} 提交审核（操作人 {}）", code, version, operator);
    }

    /**
     * 发审批 token：绑定 {@code (code, version, 当前内容哈希)}。
     * 内容在这之后被改动 → 哈希变了 → token 对不上 → 审批失败，而不是"批了 A 生效了 B"。
     */
    public String issueToken(String code, int version) {
        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() != RuleLifecycle.REVIEWING) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + row.lifecycle()
                    + "，只有 REVIEWING 的版本可以发起审批");
        }
        return tokenFor(code, version, SpecHasher.sha256(row.specJson()));
    }

    /**
     * 签发"人工确认码"：审批不可能由 Agent 单方发起，但 Agent 要能把"用户点了确认"这件事**带回来**。
     *
     * <p>所以把用户的那一下点击变成一串一次性凭据，而不是让模型自述"用户已同意"：
     * 用户在 REST 上拿到码并转述给 Agent，Agent 调 {@code recon_submit_approval} 时必须原样带上，
     * 服务端用条件 UPDATE 把它消费掉。模型编不出码，也就无法自批。
     *
     * <p>这个动作只签发、不改变任何状态 —— 拿到码不等于批了，它最多五分钟内能用一次。
     */
    public ApprovalConfirmationRepository.Confirmation issueConfirmation(String code, int version,
                                                                        String decision, String operator) {
        requireOperator(operator);
        String normalized = normalizeDecision(decision);
        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() != RuleLifecycle.DRAFT && row.lifecycle() != RuleLifecycle.REVIEWING) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + row.lifecycle()
                    + "，只有 DRAFT / REVIEWING 的版本需要人工确认");
        }
        return confirmations.issue(code, version, normalized, operator);
    }

    /**
     * 带确认码的审批：提交 → 取 token → 批准/驳回，一次调用走完，但**入口必须持有用户的确认码**。
     *
     * <p>这是 MCP 侧唯一能推进审批的方法。它把"谁能批"从"谁知道 api-key"改成了
     * "谁手上有用户刚签发的一次性码" —— Agent 能转发，不能自批。
     *
     * <p>顺序上先消费确认码再动状态机：码被用掉之后哪怕后续失败，也必须让人重新确认一次，
     * 而不是留着一个"用过一半"的凭据。
     */
    @Override
    @Transactional
    public void confirmApproval(String code, int version, String decision, String operator,
                                String confirmationCode, String note) {
        requireOperator(operator);
        String normalized = normalizeDecision(decision);
        ApprovalConfirmationRepository.Confirmation used =
                confirmations.consume(confirmationCode, code, version, normalized, operator);
        String audit = "人工确认码 " + used.code() + "（" + used.createdBy() + " 于 " + used.createdAt()
                + " 签发）" + (note == null || note.isBlank() ? "" : "；" + note.trim());

        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() == RuleLifecycle.DRAFT) {
            submit(code, version, operator);
        }
        if (normalized.equals("APPROVE")) {
            approve(code, version, operator, issueToken(code, version), audit);
        } else {
            reject(code, version, operator, audit);
        }
        log.info("人工确认完成：{} v{} decision={} 操作人={} 确认码={}（签发人 {}）",
                code, version, normalized, operator, used.code(), used.createdBy());
    }

    private static String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new RuleException("decision 必填：APPROVE 或 REJECT");
        }
        String value = decision.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "APPROVE", "APPROVED" -> "APPROVE";
            case "REJECT", "REJECTED" -> "REJECT";
            default -> throw new RuleException("decision 只能是 APPROVE 或 REJECT，当前：" + decision);
        };
    }

    /**
     * 跳过试算（只允许非语义变更）。
     *
     * <p>它是一个<b>独立动作</b>，不能和"批准"合在一个请求里：合成一个接口之后，
     * 审核记录上只会留下"批准"，看不出"这一版其实没验过"。
     *
     * <p>资格由服务端拿两版规则做字段级 diff 判定，<b>不接受操作人自述</b>。
     */
    @Transactional
    public void skipTrial(String code, int version, String operator, String reason) {
        requireOperator(operator);
        if (reason == null || reason.isBlank()) {
            throw new RuleException("跳过试算必须写理由：否则它和\"还没跑\"在审核页上长得一模一样");
        }
        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() != RuleLifecycle.DRAFT && row.lifecycle() != RuleLifecycle.REVIEWING) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + row.lifecycle()
                    + "，只有草稿/待审版本才需要跳过试算；已生效版本的试算结论不能被覆盖"
                    + "（它正是 approve 时判定可批性的依据）");
        }
        RuleChange change = review(row.code(), row.version()).change();
        if (!change.skippable()) {
            throw new RuleException("SKIPPED_NOT_ALLOWED：这次改动触及安全语义 "
                    + change.changedPaths() + "，不能跳过试算（跳过只允许用于只改名字 / 描述）");
        }
        Map<String, String> audit = new LinkedHashMap<>();
        audit.put("skippedBy", operator);
        audit.put("reason", reason);
        repository.saveTrial(code, version, new io.github.dolow.reconlens.domain.TrialState.Skipped(reason),
                null, writeJson(audit), SpecHasher.sha256(row.specJson()));
        log.info("规则 {} v{} 跳过试算（操作人 {}）：{}", code, version, operator, reason);
    }

    /**
     * 批准。
     *
     * <p><b>试算结论只从 {@code recon_rule.trial_*} 读</b>，接口不再接受调用方传入 verdict / coverage。
     * M1 期间要求调用方传 {@code TrialState} 是个临时口子：那等于允许"人工声称 PASS + FULL"，
     * 而闸门 2 的全部意义就是不让这句话由人说了算。M2 把这条通路关掉了。
     *
     * @param signature 签字说明：{@link ApprovalMode#NEED_SIGN} 与 {@link ApprovalMode#BLOCK_SIGNABLE} 必填
     */
    @Transactional
    public void approve(String code, int version, String operator, String token, String signature) {
        requireOperator(operator);
        RuleRepository.RuleRow row = requireRow(code, version);
        if (row.lifecycle() != RuleLifecycle.REVIEWING) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + row.lifecycle()
                    + "，只有 REVIEWING 的版本可以审批");
        }
        String currentSha = SpecHasher.sha256(row.specJson());
        if (token == null || !token.equals(tokenFor(code, version, currentSha))) {
            throw new RuleException("审批 token 无效：它绑定的是 (code, version, 内容哈希)，"
                    + "内容或版本对不上就说明你审的不是现在这一份");
        }
        TrialState state = repository.findTrial(code, version)
                .map(RuleRepository.TrialRecord::state)
                .orElseGet(TrialState.NotRun::new);
        requireTrialMatchesContent(code, version, state, currentSha);
        ApprovalMode mode = state.approval();
        switch (mode) {
            case NOT_APPROVABLE -> throw new RuleException("规则 " + code + " v" + version
                    + " 还没跑试算，不能批准；先跑影子试算，或（仅限非语义变更）明确跳过试算");
            case BLOCK_HARD -> throw new RuleException("规则 " + code + " v" + version
                    + " 的试算结论是硬拦（FAIL，或闸门不可信时什么也没验到），任何身份都不能批准");
            case NEED_SIGN, BLOCK_SIGNABLE -> {
                if (signature == null || signature.isBlank()) {
                    throw new RuleException("这条规则需要签字：" + modeLabel(mode) + "，请在签字说明里写清你接受了什么");
                }
            }
            case NO_SIGNOFF_REQUIRED -> {
                // 直接放行；SKIPPED 的资格在 skipTrial 那一侧已经用字段级 diff 校验过
            }
        }
        if (repository.transition(code, version, RuleLifecycle.REVIEWING, RuleLifecycle.ACTIVE) == 0) {
            throw new RuleException("规则 " + code + " v" + version + " 状态已被并发修改，请刷新后重试");
        }
        repository.recordReview(code, version, operator, currentSha);
        // 这一版生效了，同 code 下还没走完的旧版本就没机会了：折叠（不删）它们。
        // 放在同一个事务里，因为"谁取代了谁"必须和"这一版生效"是同一个事实。
        int archived = repository.archiveSiblings(code, version);
        catalog.invalidate(code);
        if (archived > 0) {
            log.info("规则 {} v{} 生效，归档了 {} 个旧草稿（superseded_by=v{}）", code, version, archived, version);
        }
        log.info("规则 {} v{} 已批准生效（操作人 {}，试算 {}，签字 {}，可批性 {}）",
                code, version, operator, state.getClass().getSimpleName(),
                signature == null ? "-" : "有", mode);
    }

    /** 驳回：REVIEWING → REJECTED（草稿也可以直接驳回）。 */
    @Transactional
    public void reject(String code, int version, String operator, String reason) {
        requireOperator(operator);
        if (reason == null || reason.isBlank()) {
            throw new RuleException("驳回必须写理由：它会进知识库与 few-shot 池，是下一轮改进的输入");
        }
        RuleRepository.RuleRow row = requireRow(code, version);
        RuleLifecycle from = row.lifecycle();
        if (from != RuleLifecycle.REVIEWING && from != RuleLifecycle.DRAFT) {
            throw new RuleException("规则 " + code + " v" + version + " 当前是 " + from + "，不能驳回");
        }
        if (repository.transition(code, version, from, RuleLifecycle.REJECTED) == 0) {
            throw new RuleException("规则 " + code + " v" + version + " 状态已被并发修改，请刷新后重试");
        }
        catalog.invalidate(code);
        log.info("规则 {} v{} 被驳回（操作人 {}）：{}", code, version, operator, reason);
    }

    private RuleRepository.RuleRow requireRow(String code, int version) {
        return repository.findByVersion(code, version)
                .orElseThrow(() -> new RuleException("规则 " + code + " 没有版本 v" + version));
    }

    /**
     * 试算结论必须绑在"这一份内容"上。
     *
     * <p>这一条与 token 校验是两件事，缺一不可：
     * <ul>
     *   <li>{@code token} 绑定的是 <b>审批那一刻</b>的内容 —— 防"批完再改"；</li>
     *   <li>{@code trial_spec_sha} 绑定的是 <b>试算那一刻</b>的内容 —— 防"试算完再改、然后重新提交"。</li>
     * </ul>
     * 少了后者，这条链路是完全合法的：trial PASS → DBA 改 spec_json → submit → issueToken（绑新内容）
     * → approve（token 对得上、trial 是旧的）→ <b>批准了一份从未试算过的内容</b>。
     *
     * <p>哈希缺失按 fail-closed 处理：有结论但没绑定，等于无法证明它验的是这一份。
     */
    private void requireTrialMatchesContent(String code, int version, TrialState state, String currentSha) {
        if (state instanceof TrialState.NotRun) {
            return;   // 没跑过走 NOT_APPROVABLE，那里有更准确的话术
        }
        String trialSha = repository.findTrialSpecSha(code, version).orElse(null);
        if (currentSha.equals(trialSha)) {
            return;
        }
        throw new RuleException("规则 " + code + " v" + version + " 的试算结论不是在当前内容上跑出来的"
                + (trialSha == null ? "（这一版没有记录试算时的内容哈希）" : "")
                + "：试算之后内容被改动过，结论已经不作数。请重新跑试算，不要直接批准");
    }

    private String writeJson(Map<String, String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Map<String,String> 序列化不该失败；真失败了也不能写半截字符串进库
            throw new RuleException("跳过试算的审计字段序列化失败：" + e.getMessage());
        }
    }

    private RuleSpec parse(RuleRepository.RuleRow row) {
        return catalog.parseSnapshot(row.specJson(), "规则库 " + row.code() + " v" + row.version());
    }

    /**
     * 基线：优先用草稿自己记的 {@code base_version}；没有就取"该版本之前最新的生效版本"。
     * 后者是给历史草稿兜底，正常路径都应该显式记 base_version。
     */
    private Optional<RuleRepository.RuleRow> baselineRow(RuleRepository.RuleRow row) {
        if (row.baseVersion() != null) {
            return repository.findByVersion(row.code(), row.baseVersion());
        }
        return repository.listVersions(row.code()).stream()
                .filter(candidate -> candidate.version() < row.version() && candidate.runtimeVisible())
                .findFirst();
    }

    private String tokenFor(String code, int version, String specSha256) {
        return SpecHasher.sha256(tokenSecret + "|" + code + "|" + version + "|" + specSha256);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new RuleException("审批类操作必须带操作人身份（X-Operator），否则审计链是断的");
        }
    }

    private static String modeLabel(ApprovalMode mode) {
        return switch (mode) {
            case NEED_SIGN -> "样本不完整，只能签字放行";
            case BLOCK_SIGNABLE -> "没验成，只能由人签字接受降级验证";
            default -> mode.name();
        };
    }
}
