-- 知识库块记录嵌入模型；待办增加自然语言原文便于核对。
ALTER TABLE kb_chunks ADD COLUMN IF NOT EXISTS embedding_model varchar(512);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_hash ON kb_chunks(document_id, source_hash);

ALTER TABLE todos ADD COLUMN IF NOT EXISTS origin_text text;
