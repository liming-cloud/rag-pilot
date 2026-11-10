-- 所属模块：rag-knowledge。
-- 目的：文档乐观锁（#197）。后台处理要几分钟，期间管理员改的有效期不能被整行回写覆盖；读取之后被改过的文档不能整份保存。
-- 影响：document 加一列，已有文档版本号为 0；兼容变更（D-067）。
-- 回滚：可以删除这一列，回到不检查并发修改。
ALTER TABLE document ADD COLUMN version bigint NOT NULL DEFAULT 0;
