-- 所属模块：rag-common。
-- 目的：启用 ParadeDB 的 pg_search 扩展，提供 BM25 关键词检索（含 jieba 中文分词）。
-- 影响：只创建扩展，不建业务表；要求数据库镜像为 ParadeDB（见 DECISIONS.md D-007）。
-- 回滚：存在 bm25 索引后不能删除扩展。
CREATE EXTENSION IF NOT EXISTS pg_search;
