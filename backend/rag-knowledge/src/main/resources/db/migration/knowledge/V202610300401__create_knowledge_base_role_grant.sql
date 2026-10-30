-- 所属模块：rag-knowledge。
-- 目的：知识库授权给角色（D-038）。能检索一个库的人 = 直接授权的账号 + 属于被授权角色的账号。
-- 影响：新建表，不改动已有数据；已有的逐人授权（knowledge_base_member）照常生效。
-- 回滚：可以删除此表，角色授权随之失效；检索 SQL 依赖它。
CREATE TABLE knowledge_base_role_grant (
    tenant_id         bigint      NOT NULL,
    knowledge_base_id bigint      NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    -- 角色在 rag-identity，跨模块不建外键；删除角色时由 RoleDeleted 事件一并收回。
    role_id           bigint      NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (knowledge_base_id, role_id)
);

CREATE INDEX knowledge_base_role_grant_role_idx ON knowledge_base_role_grant (tenant_id, role_id);
