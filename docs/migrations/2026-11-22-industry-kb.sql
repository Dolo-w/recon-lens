-- 业界知识库 recon_industry_kb（2026-11-22，K5 行业知识层）
-- 三路写入（AI 蒸馏 / 对话起草 / YAML seed）全走机器准入，无人工核证闸；
-- 质量靠后置闭环：注入计数 + 核销反馈涨落置信度，跌破阈值进 GRAVEYARD。

CREATE TABLE recon_industry_kb (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
  entry_key      VARCHAR(96)   NOT NULL,
  -- 适用条件 JSON：{ruleCodes, diffTypes, fieldRegex, directions}，全部可选，全空即拒绝入库
  applies_when   VARCHAR(2048) NOT NULL,
  knowledge_text VARCHAR(500)  NOT NULL,
  source         VARCHAR(128)  NOT NULL,
  -- SEED(人工种子YAML) / HARVEST(AI蒸馏) / CHAT(对话起草·人工确认)
  origin         VARCHAR(16)   NOT NULL,
  confidence     DECIMAL(4,3)  NOT NULL DEFAULT 0.400,
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  used_count     INT           NOT NULL DEFAULT 0,
  rejected_count INT           NOT NULL DEFAULT 0,
  updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_industry_kb_key ON recon_industry_kb (entry_key);
CREATE INDEX idx_industry_kb_status ON recon_industry_kb (status);

-- 回滚：
-- DROP TABLE recon_industry_kb;
