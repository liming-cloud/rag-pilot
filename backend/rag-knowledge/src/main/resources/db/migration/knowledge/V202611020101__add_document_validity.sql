-- 所属模块：rag-knowledge。
-- 目的：文档的有效期（#124、D-046）。失效日期当天仍有效，次日起不参与检索；未到生效日期的也不参与。
-- 影响：document 表加两列，已有数据都为空（长期有效），检索行为不变。
-- 回滚：可以删除这两列；检索 SQL 和有效期接口依赖它们。
ALTER TABLE document
    ADD COLUMN effective_from date,
    ADD COLUMN expires_on date,
    ADD CONSTRAINT document_validity_range CHECK (expires_on IS NULL OR effective_from IS NULL
        OR expires_on >= effective_from);
