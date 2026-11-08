-- 所属模块：rag-data。
-- 目的：数据问答的授权（D-056、D-059）：授权给用户或角色的工具、站点 / 区域数据范围。可用范围 = 用户本人 ∪ 所属角色。
-- 影响：新建两张表，不改动已有数据。
-- 回滚：可以删除这两张表，数据问答的授权随之全部失效（判定为没有授权，即拒绝）。
CREATE TABLE data_tool_grant (
    tenant_id    bigint      NOT NULL,
    -- USER 或 ROLE；账号和角色在 rag-identity，跨模块不建外键，删除角色时由 RoleDeleted 事件一并收回。
    grantee_type text        NOT NULL CHECK (grantee_type IN ('USER', 'ROLE')),
    grantee_id   bigint      NOT NULL,
    tool         text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, grantee_type, grantee_id, tool)
);

CREATE TABLE data_scope_grant (
    tenant_id    bigint      NOT NULL,
    grantee_type text        NOT NULL CHECK (grantee_type IN ('USER', 'ROLE')),
    grantee_id   bigint      NOT NULL,
    -- UNIT：站点编号；REGION：区域编号，判定时按客户的站点主数据展开。
    dimension    text        NOT NULL CHECK (dimension IN ('UNIT', 'REGION')),
    value        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, grantee_type, grantee_id, dimension, value)
);
