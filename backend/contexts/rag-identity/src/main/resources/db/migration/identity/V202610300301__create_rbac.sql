-- 所属模块：rag-identity。
-- 目的：按钮级 RBAC（D-038）：角色、角色的权限点、账号的角色（多对多）；账号加“必须先改密码”和登录失败锁定。
-- 影响：每个已有租户建三个内置角色；最早创建的 ADMIN 成为超级管理员，其余 ADMIN 成为“管理员”，STAFF 成为“员工”；
--       之后删除 app_user.role。知识库的逐人授权在 rag-knowledge，不受影响。
-- 回滚：不能直接回滚：role 列已删除。需要回退时，按 app_user_role 重新算出 role（超级管理员和管理员为 ADMIN，其余为 STAFF）。
CREATE TABLE app_role (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint      NOT NULL,
    name       varchar(20) NOT NULL,
    -- 内置角色：SUPER_ADMIN 全部权限、不能改；ADMIN、STAFF 能改权限、不能删。自定义角色为 null。
    builtin    varchar(20) CHECK (builtin IN ('SUPER_ADMIN', 'ADMIN', 'STAFF')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_role_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT app_role_builtin_unique UNIQUE (tenant_id, builtin)
);

-- 超级管理员的权限由代码给出（全部权限点），不在这张表里。
CREATE TABLE app_role_permission (
    role_id    bigint      NOT NULL REFERENCES app_role (id) ON DELETE CASCADE,
    permission varchar(50) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE app_user_role (
    user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id bigint NOT NULL REFERENCES app_role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX app_user_role_role_idx ON app_user_role (role_id);

ALTER TABLE app_user
    ADD COLUMN must_change_password boolean     NOT NULL DEFAULT false,
    ADD COLUMN failed_attempts      integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         timestamptz;

INSERT INTO app_role (tenant_id, name, builtin)
SELECT tenant_id, role.name, role.builtin
FROM (SELECT DISTINCT tenant_id FROM app_user) tenants
CROSS JOIN (VALUES ('超级管理员', 'SUPER_ADMIN'), ('管理员', 'ADMIN'), ('员工', 'STAFF')) AS role (name, builtin);

-- 内置“管理员”默认有知识库、用户、回答记录、使用统计的全部权限和问答（D-038）。
INSERT INTO app_role_permission (role_id, permission)
SELECT r.id, p.permission
FROM app_role r
CROSS JOIN unnest(ARRAY[
    'chat:use',
    'kb:view', 'kb:create', 'kb:update', 'kb:delete', 'doc:upload', 'doc:replace', 'doc:delete', 'kb:grant',
    'user:view', 'user:create', 'user:update', 'user:disable', 'user:reset-password', 'user:assign-role', 'user:import',
    'answer:view', 'answer:review', 'answer:recheck', 'answer:export',
    'stats:view', 'stats:export'
]) AS p (permission)
WHERE r.builtin = 'ADMIN';

INSERT INTO app_role_permission (role_id, permission)
SELECT id, 'chat:use' FROM app_role WHERE builtin = 'STAFF';

-- 全系统只有一个超级管理员：最早创建的 ADMIN。
INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id
FROM app_user u
JOIN app_role r ON r.tenant_id = u.tenant_id
 AND r.builtin = CASE
        WHEN u.id = (SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1) THEN 'SUPER_ADMIN'
        WHEN u.role = 'ADMIN' THEN 'ADMIN'
        ELSE 'STAFF'
     END;

ALTER TABLE app_user DROP COLUMN role;
