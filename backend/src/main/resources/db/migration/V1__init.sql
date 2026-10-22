-- ============================================================
-- ai-notes 初始表结构
-- 说明:PostgreSQL 存"事实数据";检索索引(chunk/向量/全文)在 Elasticsearch
-- 主键由应用侧(JPA)生成 UUID,因此这里不设 DEFAULT
-- ============================================================

-- 用户与工作区 -------------------------------------------------
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    email         TEXT UNIQUE,
    display_name  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace (
    id            UUID PRIMARY KEY,
    name          TEXT NOT NULL,
    storage_mode  TEXT NOT NULL DEFAULT 'db',
    vault_path    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_member (
    workspace_id  UUID NOT NULL REFERENCES workspace (id),
    user_id       UUID NOT NULL REFERENCES app_user (id),
    role          TEXT NOT NULL DEFAULT 'owner',
    PRIMARY KEY (workspace_id, user_id)
);

-- 目录树 -------------------------------------------------------
CREATE TABLE folder (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspace (id),
    parent_id     UUID REFERENCES folder (id),
    name          TEXT NOT NULL,
    sort_order    INT NOT NULL DEFAULT 0
);

-- 笔记 ---------------------------------------------------------
CREATE TABLE note (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspace (id),
    folder_id     UUID REFERENCES folder (id),
    title         TEXT NOT NULL,
    slug          TEXT,
    content_md    TEXT NOT NULL DEFAULT '',
    summary       TEXT,
    content_hash  TEXT NOT NULL,
    index_status  TEXT NOT NULL DEFAULT 'pending',
    created_by    UUID REFERENCES app_user (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_note_slug ON note (workspace_id, slug) WHERE deleted_at IS NULL;
CREATE INDEX ix_note_updated ON note (workspace_id, updated_at DESC);

CREATE TABLE note_version (
    id            UUID PRIMARY KEY,
    note_id       UUID NOT NULL REFERENCES note (id),
    version       INT NOT NULL,
    content_md    TEXT NOT NULL,
    author_id     UUID REFERENCES app_user (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 标签 ---------------------------------------------------------
CREATE TABLE tag (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspace (id),
    name          TEXT NOT NULL
);

CREATE TABLE note_tag (
    note_id       UUID NOT NULL REFERENCES note (id),
    tag_id        UUID NOT NULL REFERENCES tag (id),
    PRIMARY KEY (note_id, tag_id)
);

-- 双链 ---------------------------------------------------------
CREATE TABLE link (
    id              UUID PRIMARY KEY,
    workspace_id    UUID NOT NULL REFERENCES workspace (id),
    source_note_id  UUID NOT NULL REFERENCES note (id),
    target_note_id  UUID REFERENCES note (id),
    target_title    TEXT NOT NULL,
    context         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_link_source ON link (source_note_id);
CREATE INDEX ix_link_target ON link (target_note_id);

-- 附件 ---------------------------------------------------------
CREATE TABLE attachment (
    id            UUID PRIMARY KEY,
    note_id       UUID REFERENCES note (id),
    filename      TEXT NOT NULL,
    mime_type     TEXT,
    size_bytes    BIGINT,
    storage_key   TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 对话 ---------------------------------------------------------
CREATE TABLE conversation (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspace (id),
    user_id       UUID REFERENCES app_user (id),
    title         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            TEXT NOT NULL,
    content         TEXT,
    model           TEXT,
    token_in        INT,
    token_out       INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE citation (
    id            UUID PRIMARY KEY,
    message_id    UUID NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    note_id       UUID REFERENCES note (id),
    chunk_id      TEXT,
    score         REAL
);

-- Agent -------------------------------------------------------
CREATE TABLE agent_run (
    id              UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversation (id),
    goal            TEXT,
    status          TEXT NOT NULL DEFAULT 'running',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

CREATE TABLE tool_invocation (
    id            UUID PRIMARY KEY,
    run_id        UUID REFERENCES agent_run (id),
    tool_name     TEXT NOT NULL,
    arguments     JSONB,
    result        JSONB,
    status        TEXT,
    latency_ms    INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 长期记忆(内容在 PG,向量在 ES 的 ai-notes-memory 索引)-------
CREATE TABLE memory (
    id            UUID PRIMARY KEY,
    user_id       UUID REFERENCES app_user (id),
    workspace_id  UUID REFERENCES workspace (id),
    kind          TEXT,
    content       TEXT NOT NULL,
    es_doc_id     TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 索引任务 -----------------------------------------------------
CREATE TABLE index_job (
    id            UUID PRIMARY KEY,
    note_id       UUID REFERENCES note (id),
    type          TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'queued',
    attempts      INT NOT NULL DEFAULT 0,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_index_job_status ON index_job (status, created_at);

-- 模型与 MCP 配置 ----------------------------------------------
CREATE TABLE model_provider (
    id            UUID PRIMARY KEY,
    name          TEXT NOT NULL,
    base_url      TEXT NOT NULL,
    api_key_enc   TEXT,
    kind          TEXT NOT NULL,
    model_name    TEXT NOT NULL,
    is_default    BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE mcp_server (
    id            UUID PRIMARY KEY,
    name          TEXT NOT NULL,
    transport     TEXT NOT NULL,
    endpoint      TEXT,
    config        JSONB,
    enabled       BOOLEAN NOT NULL DEFAULT true
);
