package io.github.dolow.reconlens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * recon.* 配置。
 */
@ConfigurationProperties(prefix = "recon")
public class ReconProperties {

    /** 规则 DSL 位置，支持 classpath:rules/ 或 file:/path/to/rules/ */
    private String rulesLocation = "classpath:rules/";

    private final Engine engine = new Engine();
    private final Llm llm = new Llm();

    public String getRulesLocation() {
        return rulesLocation;
    }

    public void setRulesLocation(String rulesLocation) {
        this.rulesLocation = rulesLocation;
    }

    public Engine getEngine() {
        return engine;
    }

    public Llm getLlm() {
        return llm;
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
