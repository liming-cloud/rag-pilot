-- 所属模块：rag-answering。
-- 目的：记录员工点开了哪条回答的第几个引用，用来统计“引用点开率”（#145 A11）。只记回答、引用位置和时间，不记人。
-- 影响：新增一张表；回答被删时一并删除。
-- 回滚：可以删除这张表；引用点开率不再统计，其他功能不受影响。
CREATE TABLE answer_citation_open (
    id             bigserial   PRIMARY KEY,
    answer_id      bigint      NOT NULL REFERENCES answer_record (id) ON DELETE CASCADE,
    citation_index integer     NOT NULL,
    opened_at      timestamptz NOT NULL
);

CREATE INDEX answer_citation_open_answer_idx ON answer_citation_open (answer_id);
