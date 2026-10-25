-- ADR-0001 D4：口径文档切片加向量列（可空，不装模型也能跑）
--
-- 为什么 embedding 可空：向量只是检索的加速带。没配 recon.recall.embedding.* 的部署
-- 照样能查口径（走全文/关键词层），此时这三列一直是 NULL。设成 NOT NULL 等于强迫
-- 每个部署都装模型，ADR N4 的降级链就没意义了。
--
-- dim 必须存：换模型（bge-m3 1024 -> 其它 1536）之后老向量还在库里，余弦会算出垃圾
-- 而不报错。存 dim 才能在查询时判"维度不一致 -> 降级"。

DELIMITER $$
DROP PROCEDURE IF EXISTS add_doc_embedding $$
CREATE PROCEDURE add_doc_embedding()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'recon_doc_chunk'
                   AND column_name = 'embedding') THEN
    ALTER TABLE recon_doc_chunk ADD COLUMN embedding BLOB NULL AFTER content_hash;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'recon_doc_chunk'
                   AND column_name = 'dim') THEN
    ALTER TABLE recon_doc_chunk ADD COLUMN dim INT NULL AFTER embedding;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'recon_doc_chunk'
                   AND column_name = 'model') THEN
    ALTER TABLE recon_doc_chunk ADD COLUMN model VARCHAR(64) NULL AFTER dim;
  END IF;
END $$
DELIMITER ;

CALL add_doc_embedding();
DROP PROCEDURE add_doc_embedding;

-- 老数据不需要回填：dim 为 NULL 就是"还没算过向量"，检索时会跳过这些行。
