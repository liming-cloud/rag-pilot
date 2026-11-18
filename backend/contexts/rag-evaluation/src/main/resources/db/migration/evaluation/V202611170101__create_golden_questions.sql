-- 所属模块：rag-evaluation。
-- 目的：知识质量的检索测试与标准题回归（#224、D-069、D-070）：题集、标准题和期望文档、每次运行和每道题的结果。
--       一道题在一个知识库里检索；知识库、文档、账号在别的模块，跨模块不建外键：期望文档被新版本替换时由
--       DocumentReplaced 事件接到新版本，被删除时这道题按“过期”处理（运行时跳过）。
-- 影响：新建五张表，不改动已有数据。
-- 回滚：可以删除这五张表，“知识质量”页面变空；不影响问答、检索和其他统计。
CREATE TABLE eval_set (
    id          bigserial    PRIMARY KEY,
    tenant_id   bigint       NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500) NOT NULL,
    created_by  bigint       NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);
CREATE INDEX eval_set_tenant ON eval_set (tenant_id);

CREATE TABLE eval_question (
    id                bigserial    PRIMARY KEY,
    tenant_id         bigint       NOT NULL,
    set_id            bigint       NOT NULL REFERENCES eval_set (id) ON DELETE CASCADE,
    question          varchar(500) NOT NULL,
    -- 在哪个知识库里检索；查看人或运行人无权访问这个库时，这道题当作不存在。
    knowledge_base_id bigint       NOT NULL,
    -- DOCUMENTS：期望命中文档；REFUSAL：应当拒答。
    expectation       text         NOT NULL CHECK (expectation IN ('DOCUMENTS', 'REFUSAL')),
    -- 期望多个文档时：ALL 全部命中、ANY 任一命中。
    match_mode        text         NOT NULL CHECK (match_mode IN ('ALL', 'ANY')),
    created_at        timestamptz  NOT NULL
);
CREATE INDEX eval_question_set ON eval_question (set_id);

CREATE TABLE eval_expected_document (
    question_id bigint       NOT NULL REFERENCES eval_question (id) ON DELETE CASCADE,
    tenant_id   bigint       NOT NULL,
    document_id bigint       NOT NULL,
    -- 保存时的文件名：文档删除后仍能显示“期望的是哪份”。
    filename    varchar(255) NOT NULL,
    PRIMARY KEY (question_id, document_id)
);
CREATE INDEX eval_expected_document_document ON eval_expected_document (tenant_id, document_id);

CREATE TABLE eval_run (
    id          bigserial   PRIMARY KEY,
    tenant_id   bigint      NOT NULL,
    set_id      bigint      NOT NULL REFERENCES eval_set (id) ON DELETE CASCADE,
    runner_id   bigint      NOT NULL,
    -- RUNNING：后台逐题运行中；DONE：跑完；FAILED：中途失败（模型不可用、应用重启），已跑完的题照常保留。
    status      text        NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    started_at  timestamptz NOT NULL,
    finished_at timestamptz
);
CREATE INDEX eval_run_set ON eval_run (set_id, id);
-- 同一个题集同时只能有一次运行。
CREATE UNIQUE INDEX eval_run_one_running ON eval_run (set_id) WHERE status = 'RUNNING';

-- 每道题一行：开始运行时按当时的题目写入（passed 为空表示还没跑到），跑完一道填一道。题目、期望存快照，
-- 之后改题、删题不影响历史结果；对比上一次运行按 question_id。
CREATE TABLE eval_result (
    run_id                bigint       NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
    question_id           bigint       NOT NULL,
    position              int          NOT NULL,
    question              varchar(500) NOT NULL,
    knowledge_base_id     bigint       NOT NULL,
    expectation           text         NOT NULL,
    match_mode            text         NOT NULL,
    expected_document_ids bigint[]     NOT NULL,
    expected_documents    text[]       NOT NULL,
    passed                boolean,
    refused               boolean,
    recalled_document_ids bigint[],
    cited_document_ids    bigint[],
    cited_documents       text[],
    answer_text           text,
    PRIMARY KEY (run_id, question_id)
);
