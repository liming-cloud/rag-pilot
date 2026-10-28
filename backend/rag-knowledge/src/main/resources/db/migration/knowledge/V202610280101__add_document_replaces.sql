-- 所属模块：rag-knowledge。
-- 目的：上传新版本时记下它要替换哪份文档；新版本解析成功后删除旧版本，失败时旧版本保留。
-- 影响：document 表加一列，已有数据为空。
-- 回滚：可以删除这一列；上传新版本的接口依赖它。
ALTER TABLE document
    ADD COLUMN replaces_document_id bigint REFERENCES document (id) ON DELETE SET NULL;
