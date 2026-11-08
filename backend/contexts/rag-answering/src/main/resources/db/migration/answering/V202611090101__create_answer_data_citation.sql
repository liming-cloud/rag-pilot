-- 所属模块：rag-answering。
-- 目的：记录回答引用的数据出处（#182，规范第 7 节）：来源系统和数据集、查询条件、数据截至时间、查询时间，供查看历史回答。
-- 影响：新增一张表；回答被删时一并删除。只存出处，不存查到的数据本身。
-- 回滚：可以删除这张表；历史回答不再显示数据出处，其他功能不受影响。
CREATE TABLE answer_data_citation (
    answer_id      bigint      NOT NULL REFERENCES answer_record (id) ON DELETE CASCADE,
    citation_index integer     NOT NULL,
    tool           text        NOT NULL,
    source_system  text        NOT NULL,
    dataset        text        NOT NULL,
    conditions     text        NOT NULL,
    as_of          timestamptz NOT NULL,
    queried_at     timestamptz NOT NULL,
    truncated      boolean     NOT NULL,
    PRIMARY KEY (answer_id, citation_index)
);
