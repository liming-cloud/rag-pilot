-- 所属模块：rag-identity。
-- 目的：管理操作的审计记录（#145 C2）：知识库授权增减、角色和权限点变更、账号新建/启停/重置密码/批量导入、
--       删除和替换资料、系统设置修改。只增不改，保留 1 年（每天清理）。模型变更另有 model_change 表（D-043）。
-- 影响：新表，不改已有数据。
-- 回滚：删除这张表。
CREATE TABLE audit_log (
    id          bigserial    PRIMARY KEY,
    tenant_id   bigint       NOT NULL,
    actor_id    bigint       NOT NULL,
    action      varchar(50)  NOT NULL,
    target_type varchar(30)  NOT NULL,
    target_id   varchar(100) NOT NULL,
    detail      varchar(500) NOT NULL,
    occurred_at timestamptz  NOT NULL
);

CREATE INDEX audit_log_tenant_time_idx ON audit_log (tenant_id, occurred_at DESC);
