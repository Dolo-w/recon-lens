-- 会话结论板：让「早就定过、后来改口」在压缩之后仍然只有一条有效结论。
--
-- 为什么需要它（和滚动摘要的分工）：
--   摘要按轮次追加，只负责「把过程变短」；它不会让旧话失效。
--   于是长会话里会出现两种事故：
--     a) 旧话被折进「…（略去 23 行，第 12–34 轮）…」，内容没了，只剩"提到过"的索引；
--     b) 旧话被留着，和新话同时进提示词，模型挑哪句全看运气。
--   结论板只存「此刻还算数的那一条」：同一 claim_key 后写覆盖先写，读的时候只读最新，
--   所以覆盖掉的是过程，不是结论。
--
-- 为什么按 claim_key 而不是「第几条」：
--   用户改的是「这条规则的参数」，不是「那次起草」。用 ruleCode 当主题，第二次起草
--   同一编码时才会覆盖第一次，而不是并排成两条各说各的。
--
-- 为什么只收结构化主题：
--   口径/参数能落到规则编码、列名、任务号上，归属可判定；自由文本的「以后都这样」
--   没有可靠的覆盖判据，硬记会把旧话伪造成新结论——宁可不记。
--
-- 兼容性：新表，对老库只是多一张空表；代码读不到结论时行为与之前完全一致。

CREATE TABLE IF NOT EXISTS recon_chat_claim (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT       NOT NULL,
  claim_key  VARCHAR(160) NOT NULL,
  claim_value VARCHAR(255) NOT NULL,
  -- 第几轮定的：给「第 12 轮」这类回执/排查用，不参与覆盖判断
  claim_seq  INT          NOT NULL,
  -- USER / SYSTEM：谁说的。目前只写 USER，留一列是为了以后模型结论进来时能区分
  source     VARCHAR(16)  NOT NULL DEFAULT 'USER',
  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_chat_claim (session_id, claim_key),
  KEY idx_chat_claim_session (session_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
