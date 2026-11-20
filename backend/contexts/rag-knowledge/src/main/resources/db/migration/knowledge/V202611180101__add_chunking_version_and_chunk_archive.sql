-- 文档的分块是按哪一版分块规则切的（#225）：升级前入库的都是第 1 版，后台把版本旧的文档逐份重新分块。
ALTER TABLE document ADD COLUMN chunking_version integer NOT NULL DEFAULT 1;

-- 重新分块时替换下来的旧分块正文（不含向量）。历史回答的引用按分块 id 回查原文，查不到现行分块时查这里，
-- 授权过滤和现行分块相同；文档删除时一起删除。
CREATE TABLE chunk_archive (
    id                bigint PRIMARY KEY,
    tenant_id         bigint NOT NULL,
    knowledge_base_id bigint NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    document_id       bigint NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    content           text   NOT NULL
);

CREATE INDEX chunk_archive_document_idx ON chunk_archive (document_id);
