-- 所属模块：rag-answering。
-- 目的：记录每次问答（问题、回答、是否拒答、引用），以及提问人的点赞点踩和留言；用于找出答错的问题、补充评测集。
-- 影响：新建两张表，不改动已有数据。记录里有员工提出的问题，属于客户数据，只存在客户数据库里。
-- 回滚：可以删除这两张表；问答接口会写入它们。
CREATE TABLE answer_record (
    id               bigserial PRIMARY KEY,
    tenant_id        bigint       NOT NULL,
    user_id          bigint       NOT NULL,
    question         varchar(500) NOT NULL,
    answer_text      text         NOT NULL,
    refused          boolean      NOT NULL,
    refusal_reason   varchar(20),
    top_relevance    double precision,
    rating           varchar(10) CHECK (rating IN ('UP', 'DOWN')),
    feedback_comment varchar(500),
    rated_at         timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX answer_record_tenant_created_idx ON answer_record (tenant_id, created_at DESC);

-- 引用只存定位信息和当时的文档名，文档删掉后仍能看出当时引用了什么。
CREATE TABLE answer_citation (
    answer_id     bigint  NOT NULL REFERENCES answer_record (id) ON DELETE CASCADE,
    citation_index integer NOT NULL,
    chunk_id      bigint  NOT NULL,
    document_id   bigint  NOT NULL,
    document_name varchar(255) NOT NULL,
    heading_path  text    NOT NULL,
    PRIMARY KEY (answer_id, citation_index)
);
