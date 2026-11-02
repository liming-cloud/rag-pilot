-- 所属模块：rag-identity。
-- 目的：新权限点 doc:update（修改资料信息，先用于设置文档有效期，#124、D-046）默认给内置“管理员”。
-- 影响：已有租户里还有 doc:upload 的内置“管理员”角色加上 doc:update；被改掉上传权限的不加，自定义角色不变，需要时在界面上勾选。
-- 回滚：DELETE FROM app_role_permission WHERE permission = 'doc:update'。
INSERT INTO app_role_permission (role_id, permission)
SELECT r.id, 'doc:update'
FROM app_role r
WHERE r.builtin = 'ADMIN'
  AND EXISTS (SELECT 1 FROM app_role_permission p WHERE p.role_id = r.id AND p.permission = 'doc:upload')
ON CONFLICT DO NOTHING;
