-- ============ recon-lens 元数据表（任务 / 分片 / 差异）============
CREATE TABLE IF NOT EXISTS recon_job (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code     VARCHAR(64)  NOT NULL,
  rule_version  INT,
  rule_snapshot TEXT,
  batch_id      VARCHAR(64)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  -- PROD / TRIAL：试算与 canary 也写这张表（要拿分片记录与差异），但绝不能进生产基线
  kind          VARCHAR(8)   NOT NULL DEFAULT 'PROD',
  -- TRIAL 的子阶段：TRIAL / CANARY。canary 的结果不参与 T1–T7 与差异摘要
  phase         VARCHAR(8)   NULL,
  shard_count   INT          NOT NULL,
  shard_done    INT          NOT NULL DEFAULT 0,
  left_rows     BIGINT       NOT NULL DEFAULT 0,
  right_rows    BIGINT       NOT NULL DEFAULT 0,
  matched_rows  BIGINT       NOT NULL DEFAULT 0,
  diff_rows     BIGINT       NOT NULL DEFAULT 0,
  remark        VARCHAR(255),
  error_msg     VARCHAR(1000),
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recon_shard (
  job_id        BIGINT      NOT NULL,
  shard_id      INT         NOT NULL,
  status        VARCHAR(16) NOT NULL,
  attempts      INT         NOT NULL DEFAULT 0,
  scanned_left  BIGINT      NOT NULL DEFAULT 0,
  scanned_right BIGINT      NOT NULL DEFAULT 0,
  matched_rows  BIGINT      NOT NULL DEFAULT 0,
  diff_rows     BIGINT      NOT NULL DEFAULT 0,
  error_msg     VARCHAR(1000),
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP,
  PRIMARY KEY (job_id, shard_id)
);

CREATE TABLE IF NOT EXISTS recon_diff (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id       BIGINT       NOT NULL,
  shard_id     INT          NOT NULL,
  diff_type    VARCHAR(32)  NOT NULL,
  severity     VARCHAR(8)   NOT NULL,
  biz_key      VARCHAR(128) NOT NULL,
  field_name   VARCHAR(64)  NOT NULL DEFAULT '',
  left_value   VARCHAR(512),
  right_value  VARCHAR(512),
  delta_value  DECIMAL(20,4),
  delta_ratio  DECIMAL(12,6),
  explanation  VARCHAR(1000),
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 幂等基石：同一 job 同一分片同一业务键同一字段只允许一条差异，重跑先删后写天然去重
CREATE UNIQUE INDEX IF NOT EXISTS uk_diff_shard_key
  ON recon_diff (job_id, shard_id, diff_type, biz_key, field_name);
CREATE INDEX IF NOT EXISTS idx_diff_job_type ON recon_diff (job_id, diff_type);
-- 试算的 diffsInWindow(jobId, lo, hi) 按 job_id + biz_key 范围扫；没有这条索引就是整段差异全表扫
CREATE INDEX IF NOT EXISTS idx_diff_job_key ON recon_diff (job_id, biz_key);
-- T5-c 的分母来自"最近一次同 code 的 PROD 任务"，按 code + kind 倒序取一条
CREATE INDEX IF NOT EXISTS idx_job_code_kind ON recon_job (rule_code, kind, id);
-- listJobs 是 WHERE kind='PROD' ORDER BY id DESC：上面那条索引以 rule_code 打头，服务不了这个查询
CREATE INDEX IF NOT EXISTS idx_job_kind_id ON recon_job (kind, id);

-- ============ 规则库：使用方可以不改代码、不重启就新增/修改对账维度 ============
CREATE TABLE IF NOT EXISTS recon_rule (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)   NOT NULL,
  version     INT           NOT NULL,
  -- MEDIUMTEXT 而不是 VARCHAR(8000)：草稿理由 + 试算报告会把规则体顶过 8000 字符，
  -- 届时写入是直接 ERROR 1406 / 22001（不是静默截断），草稿根本落不了库
  spec_json   MEDIUMTEXT    NOT NULL,
  enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
  -- version 级状态：DRAFT / REVIEWING / ACTIVE / REJECTED。resolve 只认 ACTIVE。
  -- 与 enabled（code 级，一次下线该 code 的所有版本）是两个维度，不是同一个开关的两个值。
  lifecycle   VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  origin      VARCHAR(16)   NOT NULL DEFAULT 'HUMAN',
  -- 草稿基于哪一版：闸门 3 用它算字段级 diff（"只改了名字"必须由代码判定，不能由人自述）
  base_version INT          NULL,
  draft_reason TEXT         NULL,
  -- 内容哈希：审批把 reviewed_spec_sha 钉在"当时那一份内容"上，DBA 直接 UPDATE spec_json 会被发现
  spec_sha256 CHAR(64)      NULL,
  reviewed_spec_sha CHAR(64) NULL,
  reviewed_by VARCHAR(64)   NULL,
  reviewed_at TIMESTAMP     NULL,
  -- 闸门 2 的结论。trial_coverage 是推导值：NULL 只表示"未跑 / 跳过"，不是一种覆盖等级
  trial_verdict         VARCHAR(32) NULL,
  trial_coverage        VARCHAR(16) NULL,
  trial_coverage_reason VARCHAR(255) NULL,
  skip_reason           VARCHAR(255) NULL,
  trial_digest          CHAR(64)    NULL,
  -- 试算当时那份内容的哈希。reviewed_spec_sha 防的是"批完再改"，防不住
  -- "试算完 → 改内容 → 重新提交 → 用新 token 批准"：token 对得上，trial 结论却是旧的。
  -- 两个哈希缺一不可，它们钉住的是时间轴上的两个不同时刻。
  trial_spec_sha        CHAR(64)    NULL,
  trial_report          MEDIUMTEXT  NULL,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 规则版本：同一个 code 的每一版都留档，任务表把生效版本钉住，重跑才能复现当时的语义
CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_code_version ON recon_rule (code, version);

-- ============ 闸门自身的健康状态 ============
-- canary 失败是"闸门级"事件（按 gate-version 缓存，一次失败整个版本不可信）。
-- 不持久化的话重启后 trusted 丢了，降级签字通道会悄悄打开。
-- 一行小表，不要合并进 recon_rule：同一条规则在不同闸门版本下的判定不可混谈。
CREATE TABLE IF NOT EXISTS recon_agent_gate (
  gate_version  VARCHAR(64) NOT NULL PRIMARY KEY,
  trusted       BOOLEAN     NOT NULL DEFAULT FALSE,
  canary_job_id BIGINT      NULL,
  checked_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  detail        TEXT        NULL
);

-- ============ 试算样本表的租约登记 ============
-- 为什么不能只靠"扫 trial_ 前缀"：多实例部署时，A 正在读的样本表会被 B 启动时的清理
-- 当成"上次崩溃的残留"删掉 —— 清理动作本身变成一次数据竞争。
--
-- 为什么归属不挂在 recon_job 上：① 样本表在 job 创建<b>之前</b>就已建好（CTAS 先于 runTraced），
-- 建表后崩掉的话没有任何 job 指向它；② 一次试算有 TRIAL×2 + CANARY 三个 job，却只有一对样本表，
-- 按 job 判归属连"这对表属于谁"都说不清。表属于"这次试算"，所以租约也按 run_id 记。
--
-- heartbeat_at 由工作节点推进（物化后、每跑完一个 job、canary 前后），不另起守护线程：
-- 守护线程活着只证明进程活着，而我们要的是"这次试算还在推进"。
CREATE TABLE IF NOT EXISTS recon_trial_sample (
  run_id       VARCHAR(32) NOT NULL PRIMARY KEY,
  table_left   VARCHAR(64) NOT NULL,
  table_right  VARCHAR(64) NOT NULL,
  owner        VARCHAR(96) NOT NULL,
  heartbeat_at TIMESTAMP   NOT NULL,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_trial_sample_heartbeat ON recon_trial_sample (heartbeat_at);

-- ============ 人工审批的一次性确认码 ============
-- 为什么需要它：MCP 的 recon_submit_approval 是"替用户走审批"的通道。只靠提示词要求模型先征得同意，
-- 模型完全可以跳过 —— 实测过：agent 在对话里自拟一句"用户已确认"，47ms 就把规则批成 ACTIVE。
-- 所以把"用户确实点过确认"变成一个可校验的事实：确认码由人从另一条通道（REST）领取，
-- 一次性、5 分钟过期、绑定 (rule_code, rule_version, decision)，模型拿不到码就发不出审批请求。
CREATE TABLE IF NOT EXISTS recon_approval_confirmation (
  code         VARCHAR(16)  NOT NULL PRIMARY KEY,
  rule_code    VARCHAR(128) NOT NULL,
  rule_version INT          NOT NULL,
  decision     VARCHAR(16)  NOT NULL,
  created_by   VARCHAR(64)  NOT NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   TIMESTAMP    NOT NULL,
  used_at      TIMESTAMP    NULL,
  used_by      VARCHAR(64)  NULL
);
CREATE INDEX IF NOT EXISTS idx_approval_confirmation_rule
  ON recon_approval_confirmation (rule_code, rule_version);

-- ============ Agent 会话与轨迹 ============
-- 一次会话 = 一次"从目标到结论"的运行。status 区分 BLOCKED（按规则不该继续：预算耗尽 / 闸门拒绝）
-- 与 FAILED（出错了）—— 混成一个值会让"模型不收敛"看起来像"系统故障"。
CREATE TABLE IF NOT EXISTS recon_agent_run (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  goal           TEXT         NOT NULL,
  -- SCHEDULED / NL_QUESTION / API：决定"谁发起的"，也决定要不要走 LLM
  trigger_type   VARCHAR(16)  NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  -- 规划结果（哪条路径、为什么）。原设计想用 VARCHAR(8000)，超长会写失败而不是静默截断
  plan_json      TEXT         NULL,
  plan_truncated BOOLEAN      NOT NULL DEFAULT FALSE,
  rule_code      VARCHAR(64)  NULL,
  job_id         BIGINT       NULL,
  draft_version  INT          NULL,
  llm_calls      INT          NOT NULL DEFAULT 0,
  llm_tokens     INT          NOT NULL DEFAULT 0,
  error_msg      VARCHAR(1000) NULL,
  created_by     VARCHAR(64)  NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at    TIMESTAMP    NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_run_created ON recon_agent_run (created_at);

-- 每一步轨迹：可回放、可审计、排障依据（验收 A8）。
-- payload 存内容、digest 存哈希：v0.1 把两者塞进一个 output_digest 列，
-- 结果既不能按哈希比对，又因为"叫 digest"而不敢截断。
CREATE TABLE IF NOT EXISTS recon_agent_step (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id     BIGINT      NOT NULL,
  seq        INT         NOT NULL,
  phase      VARCHAR(16) NOT NULL,
  action     VARCHAR(64) NOT NULL,
  input_json TEXT        NULL,
  payload    TEXT        NULL,
  digest     CHAR(64)    NULL,
  truncated  BOOLEAN     NOT NULL DEFAULT FALSE,
  status     VARCHAR(16) NOT NULL,
  cost_ms    INT         NULL,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_step ON recon_agent_step (run_id, seq);

-- ============ 归因知识库（M4）============
-- cause_key = hash(rule_code, diff_type, field_name, feature_signature)。
-- cause 是<b>值</b>不是键（评审 R3）：查库时你还不知道原因，把原因放进键等于拿答案查索引，命中率恒为 0。
CREATE TABLE IF NOT EXISTS recon_diff_kb (
  id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
  cause_key         VARCHAR(64)   NOT NULL,
  rule_code         VARCHAR(64)   NOT NULL,
  diff_type         VARCHAR(64)   NOT NULL,
  field_name        VARCHAR(64)   NOT NULL DEFAULT '',
  -- 归一化后的差异特征（方向 + 量级档）：同类差异才应该复用同一条归因
  feature_signature VARCHAR(256)  NOT NULL DEFAULT '',
  cause             VARCHAR(1000) NOT NULL,
  action            VARCHAR(1000) NULL,
  confidence        DECIMAL(4,3)  NOT NULL DEFAULT 0.500,
  -- TEMPLATE / LLM / HUMAN：人工确认过的才允许升到高置信度
  source            VARCHAR(16)   NOT NULL,
  hits              INT           NOT NULL DEFAULT 0,
  misses            INT           NOT NULL DEFAULT 0,
  created_by        VARCHAR(64)   NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_cause_key ON recon_diff_kb (cause_key);
CREATE INDEX IF NOT EXISTS idx_kb_lookup ON recon_diff_kb (rule_code, diff_type);

-- ============ 预处理模型表：把"每次对账都要重做的扫描 + 汇总"提前做一次 ============
-- 模型表本身（recon_model_<规则>_l/r）由 recon_build_model 动态建，这里只登记元信息。
-- dimension/aggregated 记的是构建时用的粒度，row_count/build_ms 是实测成本，方便对比与排查新鲜度。
CREATE TABLE IF NOT EXISTS recon_model (
  id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
  rule_code       VARCHAR(64)   NOT NULL,
  rule_version    INT           NOT NULL,
  model_rule_code VARCHAR(64)   NOT NULL,
  side            VARCHAR(8)    NOT NULL,
  model_table     VARCHAR(128)  NOT NULL,
  dimension       VARCHAR(512),
  aggregated      BOOLEAN       NOT NULL,
  shards          INT           NOT NULL,
  row_count       BIGINT        NOT NULL,
  build_ms        BIGINT        NOT NULL,
  created_by      VARCHAR(64),
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_model_rule ON recon_model (rule_code, side);

-- ============ 动态表注册：闸门 1 白名单的"运行时半边" ============
-- 白名单 = 配置（recon.agent.schema.tables）∪ 这里登记的表。
-- 注册是对话里的动作（recon_register_table confirm），不能要求改配置重启；
-- 落库还让 Web 实例与 MCP 进程（两个 JVM）看到同一份白名单。
-- registered_by/registered_at 是审计：谁在什么时候把哪张表放进了闸门。
CREATE TABLE IF NOT EXISTS recon_registered_table (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  table_name       VARCHAR(128)  NOT NULL,
  columns_snapshot TEXT          NOT NULL,
  registered_by    VARCHAR(64)   NOT NULL,
  registered_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_registered_table_name ON recon_registered_table (table_name);

-- ============ 演示数据：账单（我方）与结算（对方）============
CREATE TABLE IF NOT EXISTS bill_left (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS bill_right (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_left_order ON bill_left (order_no);
CREATE INDEX IF NOT EXISTS idx_right_order ON bill_right (order_no);
