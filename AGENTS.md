# AGENTS.md

目标：做通用的可信运营助手（最终是 RAG + AI 大脑），1.0 面向新零售。路线和里程碑见 ROADMAP.md，指标见 README。

## 开始任务

从 ROADMAP.md 当前里程碑取结果型 Issue（GitHub Milestone 下）→ 建分支 `feat|fix/<Issue 号>-<短名>` → 先写失败的测试，再写实现。

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
- 登录和角色校验只用 Sa-Token 注解。
- 不新增 Maven 模块、外部服务、依赖或抽象层，除非 DECISIONS.md 已记录理由。
- Flyway 迁移放在所属模块的 `db/migration/<模块名>/`，版本号用 `V<yyyyMMddNNNN>__说明.sql`。
- 界面只用 shadcn/ui 组件和 tokens.css 变量；新页面先做 MSW 假数据原型，确认后再接接口；不做像素级调整。
- 客户数据、密钥、截图不进仓库（本仓库当前为公开仓库）。

## 交付

开 PR，按模板写清改了什么、怎么验证的、评测指标变化。一个 Issue 对应一个 PR，PR 合并即关闭 Issue；一个 PR 做完一件完整的事，不为控制行数拆分。不写单独的证据文件。

- 只为路线图上的结果开 Issue，写明验收标准。工作中发现的问题属于当前结果的，在当前 PR 里修（包括决策记录、文档、测试）；不属于的，记进 ROADMAP.md 的待办，排期时合并成结果型 Issue。
- 质量类 Issue 以指标为验收标准，一个 PR 里可以多轮“改动 → 评测”，每轮数据写进 PR 描述。
- 界面类 Issue 先交 MSW 原型给人确认，确认后在同一个 Issue 里接接口。
- 唯一的例外：main 变红时可以直接开小的 `fix` PR，不单开 Issue。

## 语言

提交信息、注释、文档用简体中文；代码标识保留英文。提交信息前缀用 `feat`、`fix`、`build`、`docs`。
