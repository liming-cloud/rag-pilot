-- 所属模块：rag-identity。
-- 目的：新权限点 audit:view（查看审计记录，#145）默认给内置“管理员”。
-- 影响：已有租户的内置“管理员”角色加上 audit:view；自定义角色不变，需要时在界面上勾选。
-- 回滚：DELETE FROM app_role_permission WHERE permission = 'audit:view'。
INSERT INTO app_role_permission (role_id, permission)
SELECT r.id, 'audit:view'
FROM app_role r
WHERE r.builtin = 'ADMIN'
ON CONFLICT DO NOTHING;
