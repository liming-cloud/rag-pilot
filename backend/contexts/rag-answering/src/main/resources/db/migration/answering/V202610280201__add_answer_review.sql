-- 所属模块：rag-answering。
-- 目的：差评处理闭环。记下低分时系统改写后的问题；管理员处理差评、答不上来的问题后标记已处理并写处理说明。
-- 影响：answer_record 加五列，已有记录都是待处理。
-- 回滚：可以删除这五列；回答记录页依赖它们。
ALTER TABLE answer_record
    ADD COLUMN rewritten_question varchar(500),
    ADD COLUMN review_status      varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (review_status IN ('OPEN', 'RESOLVED')),
    ADD COLUMN resolution_note    varchar(500),
    ADD COLUMN resolved_by        bigint,
    ADD COLUMN resolved_at        timestamptz;
