-- ============================================================
-- 落叶（LuoYe）数据库初始化 V1
-- 依据《落叶AI智能体技术设计文档》§2 数据库设计
-- 表：users / sessions / messages / long_term_memory
--      kb_documents / kb_chunks / notes / configs / todos
--      tool_calls / memory_correction
-- ============================================================

-- 1) 扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2) 用户（单用户方案，为多用户预留）
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username      varchar(64)  NOT NULL UNIQUE,
    display_name  varchar(128),
    password_hash varchar(256),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    deleted_at    timestamptz
);

-- 3) 会话
CREATE TABLE sessions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id),
    title      varchar(200),
    status     varchar(20) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    deleted_at timestamptz
);
CREATE INDEX idx_sessions_user_created ON sessions (user_id, created_at);

-- 4) 消息（短期记忆载体）
CREATE TABLE messages (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    uuid NOT NULL REFERENCES sessions(id),
    seq           int  NOT NULL,
    role          varchar(16) NOT NULL,           -- USER|ASSISTANT|TOOL|SYSTEM|MEMORY
    content       text,
    tool_calls    jsonb,
    citations     jsonb,
    tokens_used   int,
    status        varchar(16) NOT NULL DEFAULT 'completed',
    run_id        uuid,
    retrieval_log jsonb,                           -- 本轮记忆/知识库检索状态（§5.2 未命中区分）
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);
-- UNIQUE(session_id, seq) 已提供对应 B-tree 索引。
CREATE INDEX idx_messages_run_id ON messages (run_id);            -- 打断时快速定位（评审项）

-- 5) 长期记忆
CREATE TABLE long_term_memory (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid NOT NULL REFERENCES users(id),
    content            text NOT NULL,
    memory_type        varchar(32) NOT NULL DEFAULT 'fact',       -- fact|preference|event|profile
    source             varchar(16) NOT NULL,                      -- explicit|inferred
    confidence         varchar(8),                                -- high|medium|low（仅 inferred 必填）
    status             varchar(16) NOT NULL DEFAULT 'active',     -- pending|active|stale|deleted
    embedding          vector(1536),
    importance_weight  float  NOT NULL DEFAULT 0.5,
    access_count       int    NOT NULL DEFAULT 0,
    last_access_at     timestamptz,
    never_decay        boolean NOT NULL DEFAULT false,
    correction_count   int    NOT NULL DEFAULT 0,
    origin_session_id  uuid,
    origin_message_id  uuid,
    next_review_at     timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz,
    deleted_at         timestamptz,

    CONSTRAINT chk_memory_confidence CHECK (source != 'inferred' OR confidence IS NOT NULL),
    CONSTRAINT chk_memory_confidence_value CHECK (confidence IS NULL OR confidence IN ('high','medium','low'))
);
CREATE INDEX idx_memory_user_status ON long_term_memory (user_id, status);
CREATE INDEX idx_memory_status_access ON long_term_memory (status, last_access_at);
CREATE INDEX idx_memory_embedding ON long_term_memory USING hnsw (embedding vector_cosine_ops);

-- 6) 知识库文档
CREATE TABLE kb_documents (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id),
    title       varchar(255) NOT NULL,
    source_type varchar(16) NOT NULL,              -- upload|note（方案 A）
    source_ref  uuid,
    file_name   varchar(255),
    mime        varchar(100),
    doc_meta    jsonb,
    status      varchar(16) NOT NULL DEFAULT 'indexing',  -- indexing|ready|failed
    chunk_count int NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    deleted_at  timestamptz
);
CREATE INDEX idx_kb_doc_user_type ON kb_documents (user_id, source_type);

-- 7) 知识库文本块
CREATE TABLE kb_chunks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES kb_documents(id),
    user_id     uuid NOT NULL,
    seq         int  NOT NULL,
    content     text NOT NULL,
    source_hash char(64),                          -- chunk 内容哈希，用于增量判变（评审项）
    embedding   vector(1536),
    token_count int,
    metadata    jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz
);
CREATE INDEX idx_kb_chunk_doc_seq ON kb_chunks (document_id, seq);
CREATE INDEX idx_kb_chunk_embedding ON kb_chunks USING hnsw (embedding vector_cosine_ops);

-- 8) 笔记（统一存储方案 A）
CREATE TABLE notes (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL REFERENCES users(id),
    title          varchar(255) NOT NULL,
    content        text,
    content_hash   char(64),
    status         varchar(16) NOT NULL DEFAULT 'active',
    kb_document_id uuid,                           -- 关联 kb_documents(source_type='note')
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz,
    deleted_at     timestamptz
);

-- 9) 配置
CREATE TABLE configs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users(id),
    config_key   varchar(64) NOT NULL,
    config_value jsonb NOT NULL,
    updated_at   timestamptz,
    UNIQUE (user_id, config_key)
);

-- 10) 待办/日程
CREATE TABLE todos (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id),
    title      varchar(255) NOT NULL,
    description text,
    due_at     timestamptz,
    remind_at  timestamptz,
    status     varchar(16) NOT NULL DEFAULT 'open',  -- open|done
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    deleted_at timestamptz
);
CREATE INDEX idx_todos_user_status ON todos (user_id, status);

-- 11) 工具调用日志（审计 N7）
CREATE TABLE tool_calls (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL,
    session_id    uuid,
    tool_name     varchar(64) NOT NULL,
    input         jsonb,
    output        jsonb,
    status        varchar(16) NOT NULL,           -- success|failed|pending_confirm
    latency_ms    int,
    confirm_token uuid,
    called_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_calls_user_time ON tool_calls (user_id, called_at);

-- 12) 记忆纠偏记录
CREATE TABLE memory_correction (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL,
    memory_id         uuid REFERENCES long_term_memory(id),
    corrected_value   text,
    source_message_id uuid,
    created_at        timestamptz NOT NULL DEFAULT now()
);
-- §2 枚举域约束。messages.status 保留 v1.1 原文 interrupped 拼写。
ALTER TABLE sessions ADD CONSTRAINT chk_sessions_status CHECK (status IN ('active', 'archived'));
ALTER TABLE messages
    ADD CONSTRAINT chk_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM', 'MEMORY')),
    ADD CONSTRAINT chk_messages_status CHECK (status IN ('completed', 'interrupped', 'generated'));
ALTER TABLE long_term_memory
    ADD CONSTRAINT chk_memory_type CHECK (memory_type IN ('fact', 'preference', 'event', 'profile')),
    ADD CONSTRAINT chk_memory_source CHECK (source IN ('explicit', 'inferred')),
    ADD CONSTRAINT chk_memory_status CHECK (status IN ('pending', 'active', 'stale', 'deleted')),
    ADD CONSTRAINT fk_memory_origin_session FOREIGN KEY (origin_session_id) REFERENCES sessions(id),
    ADD CONSTRAINT fk_memory_origin_message FOREIGN KEY (origin_message_id) REFERENCES messages(id);
ALTER TABLE kb_documents
    ADD CONSTRAINT chk_kb_document_source CHECK (source_type IN ('upload', 'note')),
    ADD CONSTRAINT chk_kb_document_status CHECK (status IN ('indexing', 'ready', 'failed')),
    ADD CONSTRAINT fk_kb_document_note FOREIGN KEY (source_ref) REFERENCES notes(id);
ALTER TABLE notes ADD CONSTRAINT fk_note_document FOREIGN KEY (kb_document_id) REFERENCES kb_documents(id);
ALTER TABLE kb_chunks ADD CONSTRAINT fk_kb_chunk_user FOREIGN KEY (user_id) REFERENCES users(id);
CREATE INDEX idx_kb_chunk_user ON kb_chunks (user_id);
ALTER TABLE todos ADD CONSTRAINT chk_todos_status CHECK (status IN ('open', 'done'));
ALTER TABLE tool_calls
    ADD CONSTRAINT chk_tool_call_status CHECK (status IN ('success', 'failed', 'pending_confirm')),
    ADD CONSTRAINT fk_tool_call_user FOREIGN KEY (user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_tool_call_session FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE memory_correction
    ADD CONSTRAINT fk_correction_user FOREIGN KEY (user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_correction_source_message FOREIGN KEY (source_message_id) REFERENCES messages(id);

-- §1.3/§6 文本检索索引；simple 不提供中文分词，中文子串查询可走 pg_trgm。
CREATE INDEX idx_sessions_title_trgm ON sessions USING gin (title gin_trgm_ops);
CREATE INDEX idx_messages_content_fts ON messages USING gin (to_tsvector('simple', coalesce(content, '')));
CREATE INDEX idx_messages_content_trgm ON messages USING gin (content gin_trgm_ops);
CREATE INDEX idx_kb_chunks_content_fts ON kb_chunks USING gin (to_tsvector('simple', content));
CREATE INDEX idx_kb_chunks_content_trgm ON kb_chunks USING gin (content gin_trgm_ops);