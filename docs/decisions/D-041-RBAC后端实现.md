# D-041 RBAC 的后端实现：内置角色按租户、权限每次读库、检索过滤仍只有一处（2026-10-30）

#115 后端按 D-038、D-039 实现时的具体做法（“权限每次读库”“各上下文各有一个 `Paged`”已由 D-053 修订）：

- **角色与超级管理员**：角色按租户存（`app_role`），内置角色用 `builtin` 列标出，启动时补齐配置租户缺的内置角色。超级管理员就是持有内置“超级管理员”角色的账号，接口不能分配这个角色，所以全系统只有初始化时建的那一个；它的权限由代码给出（清单里的全部权限点），不存表。迁移按 D-038 的规则把旧 `role` 列换成角色后删掉该列。
- **权限判定**：`StpInterface` 每次校验都读库，停用、改角色、改角色权限立即生效。停用的账号和“必须先改密码”的账号没有任何权限点，只能调 `/api/auth` 下只要求登录的接口（当前用户、改密码、退出），这样强制改密在后端也成立，不只靠前端跳转。
- **一个接口一个权限点**：原来一个接口做两件事的拆开：`PATCH /api/users/{id}` 拆成 `PUT /api/users/{id}/enabled`（user:disable）和 `PUT /api/users/{id}/roles`（user:assign-role）；上传新版本从 `?replaces=` 改为 `POST /api/knowledge-bases/{id}/documents/{documentId}/versions`（doc:replace）。角色列表在角色页、用户页、知识库授权都要用，`role:view`、`user:view`、`kb:grant` 任一即可读。清单里暂时没有接口的权限点（kb:update、kb:delete、user:import、model:*、setting:*）只用于界面；answer:export、stats:export 在前端导出，后端只要求对应的查看权限。
- **越权规则的落点**：分配角色、给角色的权限点都不能超出操作人自己的；被停用、重置密码、改角色的对象权限不能不少于操作人（含自己）。违反时 403、错误码 `IDENTITY_ESCALATION`，与没有权限点的 `FORBIDDEN` 区分开。
- **登录锁定**：第 5 次输错就返回锁定提示（403、`IDENTITY_LOCKED`，说明还要等几分钟），锁定期间密码对了也不能登录；不为此在共享内核新增错误分类。
- **检索过滤仍只有一处**：“能访问的库 = 直接授权 ∪ 所属角色被授权”写成 `ChunkSearchMapper.xml` 里唯一的 SQL 片段 `accessibleKnowledgeBases`，两条召回、引用原文、可访问库列表都引用它。用户属于哪些角色由检索服务自己经 identity 的 `UserDirectory.roleIds` 查，调用方不能传入。
- **管理范围**：知识库管理端（列表、详情、文档、授权）只对自己能访问的库有效，延续 D-017；超级管理员例外，能看到和授权本租户全部的库，避免出现没人能授权回来的库，但检索同样按授权过滤。员工没有 kb:view，不再能读文档列表。删除角色时发布 `RoleDeleted`，knowledge 在同一事务里收回授权给它的库。
- **分页结构**：每个上下文各有一个几行的 `Paged` record，不放进共享内核（D-034：共享内核只放模型概念）。
- **评测**：合成集店员只通过自定义角色“评测店员”获得门店库授权；店长专用库先授权给该角色再收回，越权题因此覆盖“通过角色获得授权”和“收回角色授权后”。
- **自动检查**：`ArchitectureRulesTest` 新增两条规则——`/api/auth` 以外的接口都要标 `@SaCheckPermission`、权限点都在 `Permissions` 清单里，各配违规样例。
