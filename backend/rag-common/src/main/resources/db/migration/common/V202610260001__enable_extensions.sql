-- 所属模块：rag-common。
-- 目的：启用向量检索扩展，知识模块的分块表依赖它。
-- 影响：只创建扩展，不建业务表。
-- 回滚：存在向量列后不能删除扩展。
-- 约定：所有模块的迁移版本号统一用 yyyyMMddNNNN 时间戳，避免多个迁移目录之间版本冲突。
CREATE EXTENSION IF NOT EXISTS vector;
