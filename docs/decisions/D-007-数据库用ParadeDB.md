# D-007 数据库用 ParadeDB（2026-10-26 更新）

改用 ParadeDB 镜像（PostgreSQL 18，按摘要 `sha256:9b15fc38…` 锁定），同时提供 pgvector 向量检索和 pg_search BM25。PR #7 的集成测试验证了 jieba 中文分词、中文关键词 BM25 命中和向量距离排序。升级镜像时更新 `TestDatabase` 和 `deploy/compose.yml` 两处摘要。
