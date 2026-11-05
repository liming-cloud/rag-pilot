-- 所属模块：rag-knowledge。
-- 目的：模型暂时不可用导致的入库失败可以重新处理，第一次失败 10 分钟后自动重试一次（#145 A9）。
-- 影响：document 加三列；已有的失败文档都按文件问题处理（不能重新处理，可以重传同一份文件替换）。
-- 回滚：可以删除这三列和索引；失败的文档就只能重传。
ALTER TABLE document
    ADD COLUMN reprocessable boolean NOT NULL DEFAULT false,
    ADD COLUMN retry_at timestamptz,
    ADD COLUMN attempts integer NOT NULL DEFAULT 1;

-- 后台每分钟找到了重试时间的文档，只有少数行有值。
CREATE INDEX document_retry_at_idx ON document (retry_at) WHERE retry_at IS NOT NULL;
