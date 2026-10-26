-- 所属模块：rag-knowledge。
-- 目的：知识库、文档、分块三张表；所有表都带 tenant_id，检索 SQL 按租户和知识库过滤。
-- 影响：新建表，不改动已有数据。
-- 回滚：可以删除这三张表；之后的向量列、BM25 索引迁移依赖它们。
CREATE TABLE knowledge_base (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint       NOT NULL,
    name       varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_base_name_unique UNIQUE (tenant_id, name)
);

CREATE TABLE document (
    id                bigserial PRIMARY KEY,
    tenant_id         bigint       NOT NULL,
    knowledge_base_id bigint       NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    filename          varchar(255) NOT NULL,
    media_type        varchar(100) NOT NULL,
    sha256            char(64)     NOT NULL,
    size_bytes        bigint       NOT NULL CHECK (size_bytes > 0),
    status            varchar(20)  NOT NULL CHECK (status IN ('PARSING', 'READY', 'FAILED')),
    error             varchar(500),
    chunk_count       integer      NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    -- 同一知识库里同一份内容只保留一份，重复上传直接拒绝。
    CONSTRAINT document_content_unique UNIQUE (knowledge_base_id, sha256)
);

CREATE INDEX document_tenant_knowledge_base_idx ON document (tenant_id, knowledge_base_id);

CREATE TABLE chunk (
    id                bigserial PRIMARY KEY,
    tenant_id         bigint  NOT NULL,
    knowledge_base_id bigint  NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    document_id       bigint  NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    ordinal           integer NOT NULL,
    heading_path      text    NOT NULL,
    content           text    NOT NULL,
    token_count       integer NOT NULL,
    CONSTRAINT chunk_document_ordinal_unique UNIQUE (document_id, ordinal)
);

CREATE INDEX chunk_tenant_knowledge_base_idx ON chunk (tenant_id, knowledge_base_id);
