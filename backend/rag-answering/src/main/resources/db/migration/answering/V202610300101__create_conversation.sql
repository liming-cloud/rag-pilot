-- 所属模块：rag-answering。
-- 目的：员工端会话（#107、D-036）。会话属于提问人，记下所选的知识库；轮次按树存（parent_turn_id），
--       被“重新生成”“修改问题”替换的轮次标 superseded，留着供审计；每一轮关联回答记录。
-- 影响：新建两张表，不改动已有数据。会话里有员工提出的问题，属于客户数据，只存在客户数据库里。
-- 回滚：可以删除这两张表；回答记录（answer_record）不引用它们，不受影响。
CREATE TABLE conversation (
    id                 bigserial PRIMARY KEY,
    tenant_id          bigint      NOT NULL,
    user_id            bigint      NOT NULL,
    title              varchar(40) NOT NULL,
    -- NEW：还没提问；QUESTION：第一个问题的截断；MODEL：模型生成；USER：员工改过，不再自动改。
    title_source       varchar(10) NOT NULL CHECK (title_source IN ('NEW', 'QUESTION', 'MODEL', 'USER')),
    knowledge_base_ids bigint[]    NOT NULL,
    last_asked_at      timestamptz NOT NULL DEFAULT now(),
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX conversation_owner_idx ON conversation (tenant_id, user_id, last_asked_at DESC);
-- 保留期清理按最后一次提问的时间找到期的会话。
CREATE INDEX conversation_last_asked_idx ON conversation (last_asked_at);

CREATE TABLE conversation_turn (
    id                 bigserial PRIMARY KEY,
    conversation_id    bigint       NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    parent_turn_id     bigint       REFERENCES conversation_turn (id) ON DELETE SET NULL,
    superseded         boolean      NOT NULL DEFAULT false,
    question           varchar(500) NOT NULL,
    status             varchar(10)  NOT NULL CHECK (status IN ('ANSWERED', 'STOPPED', 'FAILED')),
    answer_record_id   bigint       REFERENCES answer_record (id) ON DELETE SET NULL,
    knowledge_base_ids bigint[]     NOT NULL,
    asked_at           timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX conversation_turn_conversation_idx ON conversation_turn (conversation_id, id);
