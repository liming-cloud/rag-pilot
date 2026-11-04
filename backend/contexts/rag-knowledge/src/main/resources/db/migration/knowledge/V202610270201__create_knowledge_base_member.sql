-- 所属模块：rag-knowledge。
-- 目的：知识库成员表，检索和列表都只在用户是成员的知识库里进行；建库的管理员自动成为成员。
-- 影响：新建表；已有知识库没有成员，升级后需要管理员重新授权。
-- 回滚：可以删除此表；检索 SQL 依赖它。
CREATE TABLE knowledge_base_member (
    tenant_id         bigint      NOT NULL,
    knowledge_base_id bigint      NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    user_id           bigint      NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (knowledge_base_id, user_id)
);

CREATE INDEX knowledge_base_member_user_idx ON knowledge_base_member (tenant_id, user_id);
