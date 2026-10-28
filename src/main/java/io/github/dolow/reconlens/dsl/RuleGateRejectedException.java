package io.github.dolow.reconlens.dsl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 闸门 1 拒绝了某一版规则，拦在试算<b>之前</b>。
 *
 * <p>它不是试算结论（试算还没跑），而是"这条规则根本没资格碰数据"。所以它不落
 * {@code trial_verdict}，也不该被当成"试算失败"—— 试算失败说明规则跑了但数据不对，
 * 这里说明规则连查询都不会被发出去。
 *
 * <p>违规列表是<b>结构化</b>的，不是拼进消息里的文字：HTTP 层回 {@code violations[]}，
 * Agent 层直接喂回模型做自我修正。之前只有 {@code toString()} 拼出来的一句话，调用方只能
 * 靠正则从中文里抠。
 *
 * <p><b>为什么放在 {@code dsl} 而不是 {@code agent}</b>：这个异常要穿过
 * {@code rule.RuleTrialRunner}（它的实现由 agent 条件装配注入）一路冒到 {@code web} 的
 * 异常处理器。放在 agent 包会让 {@code web → agent} 这条边成立，而
 * {@code recon.agent.enabled=false} 的前提恰恰是引擎（web/rule/dsl/engine/store）不碰 agent
 * ——{@code AgentBoundaryTest} 就是这么断言的。所以这里只保留"已经渲染好的视图"
 * （{@code code/location/message/suggestion}），{@code SafetyViolation} 本身留在 agent 里。
 */
public class RuleGateRejectedException extends RuleException {

    private final String ruleCode;
    private final int ruleVersion;
    private final List<Map<String, Object>> violations;

    /**
     * @param violations 已经渲染成视图的违规（{@code code / location / message / suggestion}），
     *                   由 agent 侧从 {@code SafetyViolation} 转换而来
     */
    public RuleGateRejectedException(String ruleCode, int ruleVersion, List<Map<String, Object>> violations) {
        this(ruleCode, ruleVersion, summarize(ruleCode, ruleVersion, violations), violations);
    }

    /** 显式给消息的入口：上层想改写措辞时用，{@code violations} 仍原样保留。 */
    public RuleGateRejectedException(String ruleCode, int ruleVersion, String message,
                                     List<Map<String, Object>> violations) {
        super(message);
        this.ruleCode = ruleCode;
        this.ruleVersion = ruleVersion;
        this.violations = List.copyOf(violations);
    }

    public String ruleCode() {
        return ruleCode;
    }

    public int ruleVersion() {
        return ruleVersion;
    }

    /** 结构化违规视图。顺序即校验器给出的顺序，可直接序列化或回喂模型。 */
    public List<Map<String, Object>> violationViews() {
        return violations;
    }

    private static String summarize(String ruleCode, int version, List<Map<String, Object>> violations) {
        String detail = violations.stream()
                .limit(3)
                .map(RuleGateRejectedException::render)
                .collect(Collectors.joining("；"));
        String more = violations.size() > 3 ? "；等共 " + violations.size() + " 项" : "";
        return "闸门 1 拒绝 " + ruleCode + " v" + version + "，未进入试算：" + detail + more;
    }

    /** 与 {@code SafetyViolation.toString()} 保持同一种人读形态，日志和消息不会因为搬家而变形。 */
    private static String render(Map<String, Object> view) {
        Object code = view.get("code");
        Object location = view.get("location");
        Object message = view.get("message");
        Object suggestion = view.get("suggestion");
        String head = code + " @" + location + ": " + message;
        return suggestion == null || String.valueOf(suggestion).isBlank()
                ? head
                : head + " → " + suggestion;
    }
}
