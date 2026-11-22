-- 回滚：大模型调用账本（清单 P0-2）
-- 注意：删表会丢失全部成本历史。仅在确认不需要历史成本数据时执行。
DROP TABLE IF EXISTS recon_llm_call;
