package io.github.dolow.reconlens.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 子系统的开关与护栏配置（前缀 {@code recon.agent}）。
 *
 * <p><b>默认关</b>：关掉时 {@code agent} 包一个 Bean 都不装配，进程行为与引入 Agent 之前完全一致。
 * 这条不是"礼貌性默认值"，而是让"引入 Agent"成为一次<b>可回滚的配置变更</b>，而不是一次不可逆的架构改动。
 */
@ConfigurationProperties(prefix = "recon.agent")
public class AgentProperties {

    private boolean enabled = false;

    /**
     * 一次会话最多几轮 LLM 往返（起草规则的自纠正循环吃这个预算）。
     *
     * <p>3 轮不是"随便定的":第一轮是模型的原稿,第二轮是它看了闸门 1 的违规列表之后的修正,
     * 第三轮是最后一次机会。四轮以上几乎不会更好 —— 模型开始"猜审核人想看到什么",
     * 而不是修问题。超限的结局是<b>转人工</b>,不是放行。
     */
    private int maxLlmRounds = 3;

    /**
     * 一次会话的 token 上界(估算值)。见 {@code AgentBudget}:接口只返回文本、不回传 usage,
     * 所以按字符数上界折算,宁可早熔断也不漏算。
     */
    private int maxLlmTokensPerRun = 40000;

    /**
     * 一次会话的墙钟上界(秒)。MCP 客户端普遍 30s 超时,而单次对账实测 21s,
     * 所以这条不是"性能预算"而是"别让调用方等到超时还看不到任何状态"。
     */
    private int maxWallClockSeconds = 120;

    /**
     * 巡检调度间隔(毫秒)。<= 0 表示不启用定时巡检(只保留按需 {@code recon_diagnose})。
     * 与 {@code cleanup-interval-ms} 同样只放在 {@code @Scheduled} 占位符里,避免两处默认值。
     */
    private long perceptionIntervalMs = 3_600_000L;

    /** 同一类巡检发现的最短告警间隔(分钟):去重 + 告警抑制,避免每轮把同一个问题再喊一遍。 */
    private int alertSuppressMinutes = 60;

    /**
     * 后台试算的并发数。默认 1 不是保守,是<b>算过连接池</b>:一次试算要跑两个正式任务 + 一个 canary,
     * 每个分片同时持有左右两个游标,而池子默认 24。并发两个会话就等于拿"连接超时"换"看起来更快"。
     */
    private int trialThreads = 1;

    /** 后台试算的排队上限。满了直接拒绝:拒绝是调用方立刻能处理的事实,排队是等 2 分钟后拿一个超时。 */
    private int trialQueueCapacity = 8;

    private final Schema schema = new Schema();
    private final Safety safety = new Safety();
    private final Perception perception = new Perception();
    private final FileImport fileImport = new FileImport();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxLlmRounds() {
        return maxLlmRounds;
    }

    public void setMaxLlmRounds(int maxLlmRounds) {
        if (maxLlmRounds < 1) {
            throw new IllegalArgumentException("recon.agent.max-llm-rounds 必须 ≥ 1，当前: " + maxLlmRounds
                    + "（0 轮等于禁用起草，那应该直接关 recon.agent.enabled）");
        }
        this.maxLlmRounds = maxLlmRounds;
    }

    public int getMaxLlmTokensPerRun() {
        return maxLlmTokensPerRun;
    }

    public void setMaxLlmTokensPerRun(int maxLlmTokensPerRun) {
        if (maxLlmTokensPerRun < 1) {
            throw new IllegalArgumentException("recon.agent.max-llm-tokens-per-run 必须 ≥ 1，当前: " + maxLlmTokensPerRun);
        }
        this.maxLlmTokensPerRun = maxLlmTokensPerRun;
    }

    public int getMaxWallClockSeconds() {
        return maxWallClockSeconds;
    }

    public void setMaxWallClockSeconds(int maxWallClockSeconds) {
        if (maxWallClockSeconds < 1) {
            throw new IllegalArgumentException("recon.agent.max-wall-clock-seconds 必须 ≥ 1，当前: " + maxWallClockSeconds);
        }
        this.maxWallClockSeconds = maxWallClockSeconds;
    }

    public long getPerceptionIntervalMs() {
        return perceptionIntervalMs;
    }

    public void setPerceptionIntervalMs(long perceptionIntervalMs) {
        // 0 是合法值（"只做按需诊断"），负数不是
        if (perceptionIntervalMs < 0) {
            throw new IllegalArgumentException("recon.agent.perception-interval-ms 不能为负，当前: " + perceptionIntervalMs);
        }
        this.perceptionIntervalMs = perceptionIntervalMs;
    }

    public int getAlertSuppressMinutes() {
        return alertSuppressMinutes;
    }

    public void setAlertSuppressMinutes(int alertSuppressMinutes) {
        if (alertSuppressMinutes < 0) {
            throw new IllegalArgumentException("recon.agent.alert-suppress-minutes 不能为负，当前: " + alertSuppressMinutes);
        }
        this.alertSuppressMinutes = alertSuppressMinutes;
    }

    public int getTrialThreads() {
        return trialThreads;
    }

    public void setTrialThreads(int trialThreads) {
        if (trialThreads < 1) {
            throw new IllegalArgumentException("recon.agent.trial-threads 必须 ≥ 1，当前: " + trialThreads);
        }
        this.trialThreads = trialThreads;
    }

    public int getTrialQueueCapacity() {
        return trialQueueCapacity;
    }

    public void setTrialQueueCapacity(int trialQueueCapacity) {
        if (trialQueueCapacity < 1) {
            throw new IllegalArgumentException("recon.agent.trial-queue-capacity 必须 ≥ 1，当前: " + trialQueueCapacity);
        }
        this.trialQueueCapacity = trialQueueCapacity;
    }

    public Schema getSchema() {
        return schema;
    }

    public Safety getSafety() {
        return safety;
    }

    public Perception getPerception() {
        return perception;
    }

    public FileImport getFileImport() {
        return fileImport;
    }

    /**
     * 巡检的判据参数（不是"调优旋钮"）。
     *
     * <p>这些值决定"什么算异常"：改了它们，同一批历史数据会得出不同结论。
     * 所以它们放在配置类里、带校验、能被测试直接引用 —— 而不是散在 SQL 与 if 里的魔数。
     */
    public static class Perception {

        /** 看最近几次生产任务。2 是最小有意义值：一次任务没有环比。 */
        private int windowJobs = 5;

        /** 差异率涨到上期的几倍算"跳变"。1.5 = 涨 50%。 */
        private double diffRateJumpRatio = 1.5;

        /** 高严重度差异占比的告警阈值。0.3 = 三成以上是 HIGH。 */
        private double highShareThreshold = 0.3;

        public int getWindowJobs() {
            return windowJobs;
        }

        public void setWindowJobs(int windowJobs) {
            if (windowJobs < 2) {
                throw new IllegalArgumentException("recon.agent.perception.window-jobs 必须 ≥ 2"
                        + "（只看一次任务谈不上环比），当前: " + windowJobs);
            }
            this.windowJobs = windowJobs;
        }

        public double getDiffRateJumpRatio() {
            return diffRateJumpRatio;
        }

        public void setDiffRateJumpRatio(double diffRateJumpRatio) {
            if (diffRateJumpRatio <= 1.0) {
                throw new IllegalArgumentException("recon.agent.perception.diff-rate-jump-ratio 必须 > 1"
                        + "（≤ 1 等于「差异率下降也要报警」），当前: " + diffRateJumpRatio);
            }
            this.diffRateJumpRatio = diffRateJumpRatio;
        }

        public double getHighShareThreshold() {
            return highShareThreshold;
        }

        public void setHighShareThreshold(double highShareThreshold) {
            if (highShareThreshold <= 0 || highShareThreshold > 1) {
                throw new IllegalArgumentException(
                        "recon.agent.perception.high-share-threshold 必须在 (0,1]，当前: " + highShareThreshold);
            }
            this.highShareThreshold = highShareThreshold;
        }
    }

    /** 闸门 1 的对象白名单：没列进来的表一律拒绝（空列表 = 全部拒绝，不是"全部允许"）。 */
    public static class Schema {

        private List<String> tables = new ArrayList<>();

        public List<String> getTables() {
            return tables;
        }

        public void setTables(List<String> tables) {
            this.tables = tables == null ? new ArrayList<>() : tables;
        }
    }

    /** 闸门 1 的阈值防篡改参数。 */    public static class Safety {

        /**
         * 新容差不得超过「基线容差 × 本倍数」。基线没有容差（精确比对）时视为 0，
         * 于是"从精确比对放宽成有容差"会被直接拦下 —— 那正是"为了让差异消失而放宽"的典型路径。
         */
        private BigDecimal toleranceMultiplier = new BigDecimal("2.0");

        /**
         * 容差的<b>绝对</b>上限（百分比）。与基线无关，新规则也受它约束。
         *
         * <p>只做"相对基线"的倍数校验会漏掉一类最重要的规则：Agent 起草的<b>首版</b>规则
         * {@code baseline == null}，此时 {@code tolerancePercent: 90} 一路畅通。
         * 而"容差多大算大"不该由一次模型输出决定 —— 它是业务口径，必须有人配过的上界。
         *
         * <p>默认 5%：对账要发现的是"钱对不上"，超过 5% 的金额差异在业务上已经不是容差问题。
         * 确实需要更宽的规则，改这个配置（而不是让模型自己说服自己）。
         */
        private BigDecimal maxTolerancePercent = new BigDecimal("5.0");

        public BigDecimal getToleranceMultiplier() {
            return toleranceMultiplier;
        }

        public void setToleranceMultiplier(BigDecimal toleranceMultiplier) {
            if (toleranceMultiplier == null || toleranceMultiplier.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("recon.agent.safety.tolerance-multiplier 不能小于 1"
                        + "（小于 1 等于要求新容差比基线更严，那不需要这道闸门）");
            }
            this.toleranceMultiplier = toleranceMultiplier;
        }

        public BigDecimal getMaxTolerancePercent() {
            return maxTolerancePercent;
        }

        public void setMaxTolerancePercent(BigDecimal maxTolerancePercent) {
            if (maxTolerancePercent == null || maxTolerancePercent.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("recon.agent.safety.max-tolerance-percent 必须 > 0"
                        + "（0 会把所有带容差的规则都判违规；要精确比对就把规则里的容差删掉）");
            }
            if (maxTolerancePercent.compareTo(new BigDecimal("100")) >= 0) {
                throw new IllegalArgumentException("recon.agent.safety.max-tolerance-percent 必须 < 100"
                        + "（≥ 100 等于\"差多少都不算差异\"，这条规则本身就没意义了）");
            }
            this.maxTolerancePercent = maxTolerancePercent;
        }
    }

    /**
     * 文件导入（M7）：用户的原始数据在 CSV/Excel 里的接入护栏。
     *
     * <p>导入和表注册一样放宽的是安全边界（新建表 + 进白名单），所以护栏全是硬上界：
     * 文件只能来自导入目录（拒绝路径穿越）、行列有上限（导入是同步动作，别让一次对话灌一千万行）。
     */
    public static class FileImport {

        /**
         * 导入文件的根目录。MCP 工具只接受这个目录内的<b>相对路径</b>，
         * 解析后的规范路径必须仍落在这个目录里 —— 任何 {@code ../} 穿越都被拒绝。
         * 绝对路径是给运维看的：放这里的就是"允许被导入的数据"的边界。
         */
        private String dir = "./data/import";

        /**
         * 是否允许导入任意绝对路径的本地文件。默认 {@code false}：
         * MCP 暴露给智能体的场景下，导入工具就是一个"任意文件读→入库"通道，
         * 绝对路径放开等于把整台机器的 CSV 都递给它 —— 保持目录边界是安全的默认值。
         * 本地自用（数据就在自己机器的任意目录）把它设为 {@code true} 即可，
         * 后缀白名单、行/列上限等其余护栏不受影响。
         */
        private boolean allowAbsolutePaths = false;

        /** 单文件最大数据行数。10 万行按 20 列估约几十 MB，同步灌数在分钟级，够探底也够用。 */
        private int maxRows = 100_000;

        /** 最大列数。对账表是窄表场景，200 列已经离谱宽了；上限主要防解析炸弹。 */
        private int maxColumns = 200;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            if (dir == null || dir.isBlank()) {
                throw new IllegalArgumentException("recon.agent.file-import.dir 不能为空"
                        + "（空目录等于没有导入边界，工具会拒绝所有路径）");
            }
            this.dir = dir.trim();
        }

        public boolean isAllowAbsolutePaths() {
            return allowAbsolutePaths;
        }

        public void setAllowAbsolutePaths(boolean allowAbsolutePaths) {
            this.allowAbsolutePaths = allowAbsolutePaths;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            if (maxRows < 1) {
                throw new IllegalArgumentException("recon.agent.file-import.max-rows 必须 ≥ 1，当前: " + maxRows);
            }
            this.maxRows = maxRows;
        }

        public int getMaxColumns() {
            return maxColumns;
        }

        public void setMaxColumns(int maxColumns) {
            if (maxColumns < 1) {
                throw new IllegalArgumentException("recon.agent.file-import.max-columns 必须 ≥ 1，当前: " + maxColumns);
            }
            this.maxColumns = maxColumns;
        }
    }
}
