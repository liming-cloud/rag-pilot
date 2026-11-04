-- 所属模块：rag-identity。
-- 目的：账号表；密码只存 BCrypt 摘要，角色先只有 ADMIN（管理知识库和账号）与 STAFF（问答）。
-- 影响：新建表，不改动已有数据。
-- 回滚：可以删除此表；登录接口依赖它。
CREATE TABLE app_user (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint       NOT NULL,
    username      varchar(50)  NOT NULL,
    display_name  varchar(50)  NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('ADMIN', 'STAFF')),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    -- 登录时只凭用户名找人，所以用户名全局唯一，不按租户区分。
    CONSTRAINT app_user_username_unique UNIQUE (username)
);
