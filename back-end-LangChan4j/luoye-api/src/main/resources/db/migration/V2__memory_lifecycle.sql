-- M2：抽取游标、并发修订号、去重/遗忘抑制和向量版本。
-- 不修改已经执行的 V1；向量仍固定为 1536 维。
ALTER TABLE users ADD COLUMN memory_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE long_term_memory
    ADD COLUMN content_hash char(64),
    ADD COLUMN embedding_model varchar(512),
    ADD COLUMN decay_score double precision NOT NULL DEFAULT 1,
    ADD COLUMN cold boolean NOT NULL DEFAULT false;
CREATE INDEX idx_memory_hash ON long_term_memory(user_id, content_hash);
CREATE INDEX idx_memory_review ON long_term_memory(next_review_at)
    WHERE status = 'active' AND deleted_at IS NULL;
CREATE INDEX idx_memory_content_trgm ON long_term_memory USING gin(content gin_trgm_ops);
CREATE INDEX idx_memory_content_fts ON long_term_memory
    USING gin(to_tsvector('simple', content));

CREATE TABLE memory_extraction_cursor (
    session_id uuid PRIMARY KEY REFERENCES sessions(id),
    last_seq int NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);
-- 仅保留旧事实的摘要，避免隐式抽取重新引入已删除或已纠正的事实。
CREATE TABLE memory_suppression (
    user_id uuid NOT NULL REFERENCES users(id),
    content_hash char(64) NOT NULL,
    PRIMARY KEY (user_id, content_hash)
);
