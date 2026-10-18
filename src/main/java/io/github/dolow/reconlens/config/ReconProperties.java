package io.github.dolow.reconlens.config;

import io.github.dolow.reconlens.engine.DiffSetDigest;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * recon.* 配置。
 */
@ConfigurationProperties(prefix = "recon")
public class ReconProperties {

    /** 规则 DSL 位置，支持 classpath:rules/ 或 file:/path/to/rules/ */
    private String rulesLocation = "classpath:rules/";

    /** 启动时是否把规则目录里的 YAML 导入规则库（只导入库里还没有的 code，作为 v1） */
    private boolean seedRules = true;

    private final Engine engine = new Engine();
    private final Llm llm = new Llm();
    private final Review review = new Review();
    private final Trial trial = new Trial();
    private final Explain explain = new Explain();
    private final Rules rules = new Rules();
    private final Security security = new Security();
    private final Model model = new Model();

    public String getRulesLocation() {
        return rulesLocation;
    }

    public void setRulesLocation(String rulesLocation) {
        this.rulesLocation = rulesLocation;
    }

    public boolean isSeedRules() {
        return seedRules;
    }

    public void setSeedRules(boolean seedRules) {
        this.seedRules = seedRules;
    }

    public Engine getEngine() {
        return engine;
    }

    public Llm getLlm() {
        return llm;
    }

    public Review getReview() {
        return review;
    }

    public Trial getTrial() {
        return trial;
    }

    public Explain getExplain() {
        return explain;
    }

    public Rules getRules() {
        return rules;
    }

    public Security getSecurity() {
        return security;
    }

    public Model getModel() {
        return model;
    }

    /**
     * 数据面写接口（{@code POST /api/jobs}、{@code POST /api/models/**}、{@code DELETE /api/rules/**}）的密钥。
     *
     * <p>和 {@code recon.review.api-key} 分开：审批密钥是"人签字"的凭据，数据面密钥是"谁可以发任务"的凭据，
     * 两者混用会让"能跑对账"自动等于"能批准规则"。
     *
     * <p><b>留空 = 不鉴权</b>（本机/内网默认，启动时打 WARN）。这不是 fail-closed 的取舍：
     * 这些接口在旧版本里本来就没有鉴权，留空是为了不破坏既有脚本；要真正收紧就配上密钥，
     * 配了之后无密钥请求一律 403。
     */
    public static class Security {

        private String apiKey = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean enforced() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * 预处理模型表（{@code POST /api/models}）生成的那条 {@code <规则>__MODEL} 规则怎么落库。
     *
     * <p>默认落<b>草稿</b>：这条规则是机器按已审批规则机械生成的，但"机械生成"不等于"验过" ——
     * 聚合维度写错、分片桶算错，规则都会安静地跑出全绿。落草稿后它和别的规则一样要过试算与人工审批。
     *
     * <p>{@code recon.model.require-approval=false} 保留旧行为（直接 ACTIVE），只应在
     * 没启用 Agent 模块（没有试算能力）的部署里用 —— 那等于明确接受"机器生成的规则免审生效"。
     */
    public static class Model {

        private boolean requireApproval = true;

        public boolean isRequireApproval() {
            return requireApproval;
        }

        public void setRequireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
        }
    }

    /**
     * 规则写入侧的开关（不是读取侧）。
     *
     * <p>{@code POST /api/rules} 是引入 Agent 之前的人工通路：它<b>直接落 ACTIVE</b>，
     * 闸门 1/2/3 全程不介入。同一套系统里，审批接口（{@code /api/rule-reviews/**}）默认拒绝，
     * 而"绕过全部闸门、直接让规则生效"的入口如果既无密钥、无开关、无审计，
     * 那三道闸门就只是"建议" —— 一条 {@code curl} 就能把容差从 0.1 改成 10。
     *
     * <p>所以它默认关闭。显式打开时仍然要求 {@code X-Api-Key}（复用
     * {@code recon.review.api-key}，不引入第二个密钥），缺失即拒绝。
     */
    public static class Rules {

        /** 是否允许通过 REST 直写规则并立即生效。默认 {@code false}。 */
        private boolean legacyDefineEnabled = false;

        public boolean isLegacyDefineEnabled() {
            return legacyDefineEnabled;
        }

        public void setLegacyDefineEnabled(boolean legacyDefineEnabled) {
            this.legacyDefineEnabled = legacyDefineEnabled;
        }
    }

    /**
     * 闸门 2（影子试算）的采样与判定参数。
     *
     * <p>这些值不是"调优旋钮"，而是<b>判据的一部分</b>：改了它们，同一条规则可能从 {@code FULL} 变成
     * {@code SAMPLE_PARTIAL}。所以它们是 {@code GateVersion} 的组成部分 —— 换了阈值就等于换了一套闸门，
     * 之前那次 canary 的结论不能顺延（{@code recon_agent_gate} 会让它失效并强制重跑自检）。
     */
    public static class Trial {

        /** 闸门实现版本标签：改了判定逻辑（而不只是阈值）时才该动它。 */
        private String gateVersion = "v1";

        /**
         * 每侧取窗行数上界（不是两侧总量）。窗口太大 → 试算成本和生产差不多；
         * 太小 → 差异几乎必然为 0，判定变成"没发现问题"而非"没问题"。
         */
        private int windowRows = 5000;

        /** 每侧取窗的探测次数上界（缩窗重试也吃这个预算）。总探测 ≤ 2 × 本值。 */
        private int windowAttempts = 3;

        /** T5-a：每侧样本行数下限。低于它，"差异为 0"没有统计意义。 */
        private int minSampleRows = 200;

        /** T5-b：双侧交集下限。低于它，两侧压根没重叠，所有差异都是采样制造的。 */
        private int minCommonKeys = 100;

        /** T5-c 的绝对下限："至少看到跨分片行为"的意愿。但意愿不能压过事实（见下面 ratio 与基线封顶）。 */
        private int minShardsWithData = 2;

        /** T5-c 的相对下限：允许多少分片抖动。取 floor，取 ceil 会让 baselineHit∈{3,4} 变成零容忍。 */
        private double minShardCoverageRatio = 0.8;

        /** 样本物化的字节上界；超了减半窗重试，耗尽则 SAMPLE_PARTIAL + SAMPLE_TOO_SMALL。 */
        private long maxSampleBytes = 256L * 1024 * 1024;

        /** T6（软判据）：新差异总量不得超过基线的这个倍数，超了进审核重点区，不自动拒绝。 */
        private double diffGrowthRatio = 1.5;

        /** TRIAL 任务的 job / diff 保留天数。不清的话 {@code recon_job} 会被试算撑爆。 */
        private int retentionDays = 7;

        /** 残留样本表的租约时长（分钟），语义见 getter 注释。 */
        private int cleanupLeaseMinutes = 30;

        public String getGateVersion() {
            return gateVersion;
        }

        public void setGateVersion(String gateVersion) {
            if (gateVersion == null || gateVersion.isBlank()) {
                throw new IllegalArgumentException("recon.trial.gate-version 不能为空：它是闸门健康状态的缓存键");
            }
            this.gateVersion = gateVersion;
        }

        public int getWindowRows() {
            return windowRows;
        }

        public void setWindowRows(int windowRows) {
            requirePositive("recon.trial.window-rows", windowRows);
            this.windowRows = windowRows;
        }

        public int getWindowAttempts() {
            return windowAttempts;
        }

        public void setWindowAttempts(int windowAttempts) {
            requirePositive("recon.trial.window-attempts", windowAttempts);
            this.windowAttempts = windowAttempts;
        }

        public int getMinSampleRows() {
            return minSampleRows;
        }

        public void setMinSampleRows(int minSampleRows) {
            requirePositive("recon.trial.min-sample-rows", minSampleRows);
            this.minSampleRows = minSampleRows;
        }

        public int getMinCommonKeys() {
            return minCommonKeys;
        }

        public void setMinCommonKeys(int minCommonKeys) {
            requirePositive("recon.trial.min-common-keys", minCommonKeys);
            this.minCommonKeys = minCommonKeys;
        }

        public int getMinShardsWithData() {
            return minShardsWithData;
        }

        public void setMinShardsWithData(int minShardsWithData) {
            requirePositive("recon.trial.min-shards-with-data", minShardsWithData);
            this.minShardsWithData = minShardsWithData;
        }

        public double getMinShardCoverageRatio() {
            return minShardCoverageRatio;
        }

        public void setMinShardCoverageRatio(double minShardCoverageRatio) {
            if (!(minShardCoverageRatio > 0) || minShardCoverageRatio > 1) {
                throw new IllegalArgumentException("recon.trial.min-shard-coverage-ratio 必须在 (0,1] 内，当前: "
                        + minShardCoverageRatio);
            }
            this.minShardCoverageRatio = minShardCoverageRatio;
        }

        public long getMaxSampleBytes() {
            return maxSampleBytes;
        }

        public void setMaxSampleBytes(long maxSampleBytes) {
            requirePositive("recon.trial.max-sample-bytes", maxSampleBytes);
            this.maxSampleBytes = maxSampleBytes;
        }

        public double getDiffGrowthRatio() {
            return diffGrowthRatio;
        }

        public void setDiffGrowthRatio(double diffGrowthRatio) {
            if (!(diffGrowthRatio > 0)) {
                throw new IllegalArgumentException("recon.trial.diff-growth-ratio 必须 > 0，当前: " + diffGrowthRatio);
            }
            this.diffGrowthRatio = diffGrowthRatio;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            requirePositive("recon.trial.retention-days", retentionDays);
            this.retentionDays = retentionDays;
        }

        /**
         * 残留样本表的租约时长（分钟）：心跳超过这么久没更新，就认为拥有这次试算的实例已经死了，
         * 那对 {@code trial_*} 表可以回收。
         *
         * <p>必须比"一次试算的正常时长"大一个量级才安全（默认 30 分钟，实测一次试算约 1 分钟）。
         * 调小它不会让清理更积极，只会让<b>卡住的试算</b>被提前判死，代价是一次本来能出结论的试算变成 T1 FAIL。
         * 反过来，调大它的唯一代价是多占几张表 —— 所以这条宁大勿小。
         *
         * <p><b>它不参与 {@code GateVersion}</b>：租约是运维参数，不是判定阈值。
         * 改它不该让之前那次 canary 的结论失效（对比 {@code window-rows} 之类的判据参数）。
         */
        public int getCleanupLeaseMinutes() {
            return cleanupLeaseMinutes;
        }

        public void setCleanupLeaseMinutes(int cleanupLeaseMinutes) {
            requirePositive("recon.trial.cleanup-lease-minutes", cleanupLeaseMinutes);
            this.cleanupLeaseMinutes = cleanupLeaseMinutes;
        }

        private static void requirePositive(String key, long value) {
            if (value < 1) {
                throw new IllegalArgumentException(key + " 必须 ≥ 1，当前: " + value);
            }
        }
    }

    /**
     * 闸门 3 的审批入口配置。审批只能在 REST / CLI 上做，<b>不注册 MCP 工具</b> ——
     * MCP 的调用方就是 Agent，做成工具等于开一条无需人参与的发布通道。
     */
    public static class Review {

        /**
         * 审批接口的 API Key（请求头 {@code X-Api-Key}）。<b>留空 = 接口一律拒绝</b>（fail-closed）：
         * 审批是"能改钱的判定口径"的操作，忘了配密钥的后果不该是"谁都能批"。
         */
        private String apiKey = "";

        /** 审批 token 的签名密钥；留空则每次启动随机生成（重启后旧 token 失效，但不会静默降级安全性）。 */
        private String tokenSecret = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getTokenSecret() {
            return tokenSecret;
        }

        public void setTokenSecret(String tokenSecret) {
            this.tokenSecret = tokenSecret;
        }
    }

    public static class Engine {
        /** 分片并行度（线程数） */
        private int parallelism = 8;
        /** JDBC 游标抓取条数：决定单次网络/内存占用，与数据总量无关 */
        private int fetchSize = 2000;
        /** 单个分片最大尝试次数（含首次） */
        private int maxAttempts = 2;
        /** 启动时是否自动跑一次演示对账 */
        private boolean runDemoOnStartup = false;
        /**
         * 演示是否允许往"外部数据源"写。
         *
         * <p>默认 profile 用 H2 内存库，演示跑完就没了；但如果有人把默认 profile 的数据源换成
         * 真实 MySQL（只改了 {@code spring.datasource.url}），演示就会往业务库里插演示任务、
         * 往 {@code bill_left}/{@code bill_right} 写演示行。默认只在内嵌 H2 上自动跑，
         * 要在外部库上跑必须显式打开这个开关。
         */
        private boolean demoAllowExternalDb = false;
        /**
         * 单分片差异摘要的键数上限（{@code DiffSetDigest}）。摘要要把全部业务键排一次序，
         * 是 O(n) 内存；超过上限就明确放弃产出摘要，而不是只哈希前 N 条。
         * 设成 0 没有任何"关闭"含义，直接当成配置错误 —— 否则摘要会静默变成空。
         */
        private int diffDigestMaxKeys = DiffSetDigest.DEFAULT_MAX_KEYS;

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getFetchSize() {
            return fetchSize;
        }

        public void setFetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public boolean isRunDemoOnStartup() {
            return runDemoOnStartup;
        }

        public void setRunDemoOnStartup(boolean runDemoOnStartup) {
            this.runDemoOnStartup = runDemoOnStartup;
        }

        public boolean isDemoAllowExternalDb() {
            return demoAllowExternalDb;
        }

        public void setDemoAllowExternalDb(boolean demoAllowExternalDb) {
            this.demoAllowExternalDb = demoAllowExternalDb;
        }

        public int getDiffDigestMaxKeys() {
            return diffDigestMaxKeys;
        }

        /** 启动时就校验：配错值应该让应用起不来，而不是让每个分片跑到一半才失败。 */
        public void setDiffDigestMaxKeys(int diffDigestMaxKeys) {
            if (diffDigestMaxKeys < 1) {
                throw new IllegalArgumentException("recon.engine.diff-digest-max-keys 必须 ≥ 1，当前: " + diffDigestMaxKeys);
            }
            this.diffDigestMaxKeys = diffDigestMaxKeys;
        }
    }

    /**
     * 归因链路的分层策略（§6 成本分层）。
     *
     * <p>顺序固定为 <b>知识库 → 大模型 → 内置模板</b>：
     * <ul>
     *   <li>知识库命中 = 零 LLM 调用，这就是"LLM 成本随运行次数递减"的唯一来源；</li>
     *   <li>大模型未命中才调用，且它的输出会写回知识库（{@code source=LLM}）；</li>
     *   <li>模板永远是最后一层：不依赖网络、不依赖配置，保证每条差异都有解释。</li>
     * </ul>
     *
     * <p><b>不把模板排在知识库前面</b>：模板谁都会答，答了也不写入知识库 ——
     * 一旦它抢在知识库前面，知识库就永远长不大，成本永远降不下来。
     */
    public static class Explain {

        private final Knowledge knowledge = new Knowledge();

        public Knowledge getKnowledge() {
            return knowledge;
        }

        public static class Knowledge {
            /** 关掉就退回"直接调 LLM"，用于验证知识库是否真的在省调用 */
            private boolean enabled = true;

            /**
             * 采用门槛。<b>0.5 是刻意的</b>：LLM 自产的条目置信度就是 0.5，
             * 门槛设得比它高，知识库就只吃不吐 —— 有数据、不命中、成本一点不降。
             * 想要"只信人工"就把这里调到 0.9。
             */
            private double minConfidence = 0.5;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getMinConfidence() {
                return minConfidence;
            }

            public void setMinConfidence(double minConfidence) {
                if (minConfidence < 0 || minConfidence > 1) {
                    throw new IllegalArgumentException(
                            "recon.explain.knowledge.min-confidence 必须在 [0,1]，当前: " + minConfidence);
                }
                this.minConfidence = minConfidence;
            }
        }
    }

    public static class Llm {
        /** 打开后归因说明交给大模型；关闭时用内置规则模板（完全离线） */
        private boolean enabled = false;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private int timeoutSeconds = 30;
        /** 一次批量归因最多处理多少条差异 */
        private int batchLimit = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getBatchLimit() {
            return batchLimit;
        }

        public void setBatchLimit(int batchLimit) {
            this.batchLimit = batchLimit;
        }

        /** 配置优先，其次读环境变量，便于 CI / 容器里注入密钥 */
        public String resolveApiKey() {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.trim();
            }
            String fromEnv = System.getenv("RECON_LLM_API_KEY");
            return fromEnv == null ? "" : fromEnv.trim();
        }

        public boolean usable() {
            return enabled && !resolveApiKey().isEmpty() && baseUrl != null && !baseUrl.isBlank();
        }
    }
}
