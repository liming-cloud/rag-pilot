# AGENTS.md

目标：做通用的可信运营助手（最终是 RAG + AI 大脑），1.0 面向新零售。路线和里程碑见 ROADMAP.md，指标见 README。

## 开始任务

从 ROADMAP.md 当前里程碑取带 `status/ready` 的结果型 Issue（GitHub Milestone 下）→ 建分支 `feat|fix/<Issue 号>-<短名>` → 先写失败的测试，再写实现。

- Issue 用模板“结果”新建，默认带 `status/needs-decision`：先把需要人决定的事写进“未决问题”，人在 Issue 里答复、答复写进 DECISIONS.md 后，改为 `status/ready` 再开工。卡在未决问题上时，转去做其他已就绪的 Issue，不自己拍板。
- 进度、决定、实施记录更新在 Issue 描述里，不靠评论；ROADMAP.md 不写进度。

## 先停下来问人

以下事项由人决定。能在开工前看出来的，写进 Issue 的“未决问题”；开工后才发现的，在 PR 里提出，等答复再继续：

- 改变 README 指标的目标值或口径，或接受某项指标不达标（如 D-027、D-030）。
- 权限语义：检索过滤、角色与权限点、超级管理员、会话失效。
- 新增 Maven 模块、外部服务、依赖或抽象层。
- 删除或放宽测试、断言、评测题（尤其是越权题）、架构规则。
- 数据库的不兼容变更；客户数据、密钥的处理方式。

## 本地验证

- 后端：`./mvnw verify`（含 Testcontainers 和 Spring Modulith 边界测试，需要 Docker）。
- 前端：在 `frontend/` 下运行 `npm run typecheck && npm test && npm run build`。
- 改了检索或生成：加跑评测。PR 改到检索、问答、模型或评测集时 CI 的 `eval` 任务会自动用真实模型跑；本地运行：
  `MODEL_API_KEY=… ./mvnw verify -pl backend/rag-app -am -Dtest.groups=eval -Dtest.excludedGroups=none -Dtest=EvaluationRun -Dsurefire.failIfNoSpecifiedTests=false`，报告在 `backend/rag-app/target/eval-report.md`。
- 评测集是合成数据（`backend/rag-app/src/test/resources/eval/`）；客户真实评测集只放在客户环境。

## 设计原则

完整说明见 ROADMAP.md 第二节。

- 通用内核 + 行业包：内核不写死行业内容，提示词角色、话术、文案、推荐问题、评测集放在行业包配置里。
- 权限由系统判定，不交给模型；功能权限（RBAC，细到按钮）和资源权限（知识库授权）分开，管理员也不绕过资源授权。
- 每个结论都能追溯；依据不足时明说。预测交给专门的模型；写操作先经人确认。
- 能评测才算完成：改动以多轮评测结果为准。

## 约束

- 权限过滤只在 rag-knowledge 的检索服务里写一处，禁止绕过。
- 模型调用只经 rag-ai 模块（Spring AI 的 ChatClient / EmbeddingModel 和 RerankClient）；密钥只从环境变量读，不进日志。
- DDD：聚合只在 knowledge、answering；上下文模块之间只用对方的 `api` 包或监听事件。
- MyBatis-Plus 的 DO、Wrapper、Page 只出现在 infrastructure 包；不引入 MapStruct，不做多层 DTO 转换。
- SQL 只写在 Mapper XML（`src/main/resources/mapper/<模块名>/<Mapper 名>.xml`）里；不用 `@Select` 等 SQL 注解，条件构造器不拼原始 SQL（`apply`、`last`、`inSql` 等）。简单的单表条件用 `LambdaQueryWrapper`。
- 登录和角色校验只用 Sa-Token 注解。
- 不新增 Maven 模块、外部服务、依赖或抽象层，除非 DECISIONS.md 已记录理由。
- Flyway 迁移放在所属模块的 `db/migration/<模块名>/`，版本号用 `V<yyyyMMddNNNN>__说明.sql`。
- 界面只用 shadcn/ui 组件和 tokens.css 变量；新页面先做 MSW 假数据原型，确认后再接接口；不做像素级调整。
- 客户数据、密钥、截图不进仓库（本仓库当前为公开仓库）。

能明确判定的约束和下面的目录结构由 `ArchitectureRulesTest`、`PackageLayoutTest`、`MigrationLayoutTest`、`MapperXmlLayoutTest`、`ModularityTests` 和前端的 `src/app/structure.test.ts` 自动检查。规则误判或漏判时，修改规则并补违规样例（`archfixture` 包），不绕过、不加例外。

## 目录结构

后端每个 Maven 模块 `rag-<名>` 对应根包 `com.liming.rag.<名>`：

- **上下文模块**（identity、knowledge、answering、evaluation）：根包只放 `package-info.java`，类只能在五个子包里：
  - `api`：对其他模块公开的合同（接口、record、枚举、事件），不暴露模块内部类型；
  - `web`：Controller、请求响应 record；
  - `application`：用例服务，以及它需要的端口接口（如 `ChunkIndex`、`FileStorage`）；
  - `domain`：聚合、值对象、领域规则，只在 knowledge、answering；
  - `infrastructure`：端口的实现，按技术分子包（`persistence`、`storage`）。
- **依赖方向**：`domain` 只依赖 JDK、rag-shared 和本模块的 `domain`、`api`；`application` 不依赖 `infrastructure`、`web`；`web` 不依赖 `infrastructure`；`api` 不依赖本模块其他子包。
- **共享内核**（rag-shared）：只放多个上下文共同依赖、用纯 Java 表达、很少变动的模型概念——租户和用户标识、错误分类 `ErrorKind` 与 `BusinessException`、聚合根标记 `AggregateRoot`。只依赖 JDK，没有资源文件（迁移、配置归各自模块），不放业务规则；当前用户、权限码、各上下文自己的标识不放这里。新增类型前先在 DECISIONS.md 记录理由（D-034）。
- **技术模块**（shared、ai）扁平：对外只公开接口、record、枚举和异常，实现类包内可见；rag-app 只放启动类和全局配置（含唯一的异常出口 `ApiExceptionHandler`）。
- **聚合**：聚合根实现 `AggregateRoot`，只在 knowledge、answering 的 `domain` 包；`domain` 里的 `XxxRepository` 只为同包的聚合根 `Xxx` 而建。
- **错误**：业务失败抛本模块继承 `BusinessException` 的异常（错误码 `<模块>_<原因>`，说明是给用户看的中文，不含内部信息）；领域对象检查不变量时抛的 `IllegalArgumentException` 由 application 转成业务异常。模块不写 `@RestControllerAdvice`，统一由 `ApiExceptionHandler` 转成带 `code` 的问题详情。
- **资源**：Flyway 迁移在 `db/migration/<模块名>/`，Mapper XML 在 `mapper/<模块名>/`。单元测试与被测类同包；跨模块的集成测试放在 rag-app。

前端 `frontend/src`：

- `app/`：路由、布局；`components/ui/`：只放 shadcn 生成的组件；`lib/`：通用工具与请求封装；`mocks/`：MSW 假接口；`styles/`：`tokens.css`；`test/`：测试初始化。
- `features/<领域>/`：页面（`XxxPage.tsx`）、`api.ts`（接口调用和类型）、子组件，测试与源文件同目录。feature 之间只能引用对方的 `api.ts`，不引用 `app/`。

## 测试

- 断言约定的结果，而不只是方法被调用或返回 200；越权、拒绝类场景还要断言没有返回、没有写入不该有的数据。
- 测试失败先判断是实现、测试还是需求的问题；不能为了通过而改预期、删断言。
- 不稳定测试（同一代码时而通过时而失败）：第一次出现，确认与当前改动无关后可以重跑一次，并用模板“不稳定测试”登记 Issue；再次出现且没修好，先 `@Disabled("#Issue 号")` 隔离，让 main 恢复可靠，再定位根因；修好后恢复测试。评测指标的波动不按这条处理，按多轮评测判断。

## 交付

开 PR，按模板写清改了什么、验收标准逐条的证据、评测指标变化、风险与未完成项。一个 Issue 对应一个 PR；一个 PR 做完一件完整的事，不为控制行数拆分。不写单独的证据文件。

- 只为路线图上的结果开 Issue，写明验收标准。工作中发现的问题属于当前结果的，在当前 PR 里修（包括决策记录、文档、测试）；不属于的，记进 ROADMAP.md 的待办，排期时合并成结果型 Issue。
- 质量类 Issue 以指标为验收标准，一个 PR 里可以多轮“改动 → 评测”，每轮数据写进 PR 描述。
- 界面类 Issue 先交 MSW 原型给人确认，确认后在同一个 Issue 里接接口。
- 唯一的例外：main 变红时可以直接开小的 `fix` PR，不单开 Issue。
- 完成定义：验收标准逐条打勾，每条在 PR 描述里有证据（测试名、eval 运行链接和指标、或手工步骤结果）。有一条没达到，PR 写 `Refs #n`，Issue 不关闭；经人同意后，缺口拆成新的结果型 Issue（写明理由）再关闭。缺口不能只写在“风险与未完成项”里。
- 行为、配置、指标口径变化时，README / AGENTS.md / ROADMAP.md / DECISIONS.md 在同一个 PR 里更新；每条规则只在一处写正文，其他地方链接过去。

### 合并

- 从 main 建分支，不以另一个未合并的分支为 base；上一个 PR 合并后再开下一个（squash 合并后，叠放的上层 PR 必然冲突）。
- CI 的 `backend`、`frontend`、`image` 全绿才能合并（分支保护），涉及检索或生成时 `eval` 的结果写进 PR。改 `ci.yml` 的任务名时同步改分支保护。
- CI 失败、合并冲突、评审意见由开 PR 的一方（含 AI）自己修并推送，不等提醒。推送后核对远端提交号。
- 人读过 PR 后开启 auto-merge，CI 全绿由 GitHub 合并；AI 不自己合并。

### 评审清单

自动检查已覆盖的（模块边界、架构规则、迁移命名）不必再看。

| 方面 | 看什么 |
| --- | --- |
| 权限与越权 | 检索过滤是否仍只在一处；新接口有没有登录和角色注解；越权场景是否有测试和评测题 |
| 范围 | 是否对应一个 `status/ready` 的 Issue，验收标准是否真的验证了；有没有顺带做没要求的事 |
| 设计 | 业务规则归哪个聚合或服务；有没有只转发的层；是否触及“先停下来问人”的事项 |
| 正确性 | 边界输入、并发、事务范围、模型调用失败和超时时的行为 |
| 测试与评测 | 正常和关键失败路径都有测试；断言的是结果；评测数据是否支持结论（看多轮，不看单次） |
| 文档 | 决定已写进 DECISIONS.md；文档写的是当前结论 |

## 语言

提交信息、注释、文档用简体中文；代码标识保留英文。提交信息前缀用 `feat`、`fix`、`build`、`docs`。
