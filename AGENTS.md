# AGENTS.md

目标：做通用的可信运营助手（最终是 RAG + AI 大脑），1.0 面向新零售。产品方向见 [ROADMAP.md](ROADMAP.md)，进度看 GitHub Milestone，指标见 [README](README.md)，工程北极星见 [北极星](docs/engineering/北极星.md)。

本文件只做路由：先按任务类型读对应规则卡，规则正文只写在 [docs/rules/](docs/rules/00-索引.md)，决策见 [docs/decisions/](docs/decisions/README.md)。

## 开始任务

取当前 Milestone 里带 `status/ready`、前置已合并的结果型 Issue → 建分支 `feat|fix/<Issue 号>-<短名>` → 先写失败的测试，再写实现。详见 P-01～P-03（[流程](docs/rules/10-流程.md)）。

## 按任务读规则卡

| 任务 | 读哪些卡 |
| --- | --- |
| 任何任务 | [流程 P-01～P-13](docs/rules/10-流程.md)、[测试 T-01～T-03](docs/rules/50-测试.md) |
| 新增或移动后端类、改模块依赖 | [架构 A-06、A-08、A-10～A-18](docs/rules/20-架构.md) |
| 检索、权限、知识库授权 | A-02、A-05、A-09、B-08 |
| 调模型、提示词、向量化、重排 | A-07、A-01、T-05 |
| 缓存 | A-09、T-07 |
| 数据库、SQL、迁移 | [后端写法 B-01、B-02、B-09](docs/rules/30-后端写法.md) |
| 接口、异常、日志、审计、配置 | A-14、A-19、B-03、B-06、B-07 |
| 时间、编译告警、空值 | B-04、B-05 |
| 前端页面和组件 | [前端 F-01～F-04](docs/rules/40-前端.md) |
| 改了接口字段或权限点 | T-04 |
| 改了检索或生成 | T-05、T-06 |
| 新增模块、依赖、抽象层 | A-21、P-04，并先写[决策](docs/decisions/README.md) |
| 提交、PR、合并 | P-05～P-13 |

## 先停下来问人

以下事项由人决定（P-04）。能在开工前看出来的，写进 Issue 的“未决问题”；开工后才发现的，在 PR 里提出，等答复再继续：

- 改变 README 指标的目标值或口径，或接受某项指标不达标（如 D-027、D-030）。
- 权限语义：检索过滤、角色与权限点、超级管理员、会话失效。
- 新增 Maven 模块、外部服务、依赖或抽象层。
- 删除或放宽测试、断言、评测题（尤其是越权题）、架构规则。
- 数据库的不兼容变更；客户数据、密钥的处理方式。

## 本地验证

- 后端：`./mvnw verify`（含 Testcontainers、Spring Modulith 边界测试和文档自检 `DocLinksTest`，需要 Docker）。
- 前端：在 `frontend/` 下运行 `npm run typecheck && npm run lint && npm test && npm run build`（Node 24）。
- 改了接口字段或权限点：见 T-04；改了检索或生成：见 T-05（命令在卡里）。

## 硬规则摘要

自动检查的规则违反了测试会失败；这里只列最容易踩的，正文以规则卡为准。

- 权限过滤只在 rag-knowledge 的检索服务里写一处（A-05）；权限由系统判定，不交给模型（A-02）。
- AI 能力只经 rag-ai，缓存只经 rag-cache，上下文只在 `infrastructure` 里使用外部能力（A-06、A-07、A-09）。
- SQL 只写在 Mapper XML，不用 `@Value`，时间用注入的 `Clock`（B-02～B-04）。
- 不新增 Maven 模块、外部服务、依赖或抽象层，除非决策记录已写明理由（A-21）。
- 测试失败不改预期、不删断言（T-02）；客户数据、密钥、截图不进仓库（A-22）。
- 规则误判或漏判时，修改规则并补违规样例，不绕过、不加例外（A-23）。
