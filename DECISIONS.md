# 决策记录

每条不超过 5 行。新增 Maven 模块、外部服务、依赖或抽象层之前，先在这里加一条。

## D-001 新仓库精简重建（2026-10-25）

可信 RAG 试点在新仓库重建，旧仓库 `ai-platform` 只读归档。原因：旧项目流程和文档成本过高，5 个多月没有闭合“文档 → 问答”主链路。只挑旧代码中的分块逻辑、pgvector 查询和模型对接细节改写进来。

## D-002 技术栈（2026-10-25）

JDK 25 LTS、Spring Boot 4.1.0、Spring AI 2.0.0、Spring Modulith 2.1.0、MyBatis-Plus 3.5.17、Sa-Token 1.45.0、Flyway。版本组合沿用旧项目在 Boot 4.1 上已构建通过的组合；旧项目的 JDK 26 不是 LTS，换为 25。

## D-003 Maven 聚合 7 个模块、单进程部署（2026-10-25）

4 个限界上下文（identity、knowledge、answering、evaluation）各一个模块，加 rag-common、rag-ai、rag-app。模块依赖方向由 Maven 强制，包级边界由 Modulith 测试检查。试点只部署 rag-app 一个进程；需要时新增 rag-worker 启动模块拆出解析进程。

## D-004 轻量 DDD 与 MyBatis-Plus 边界（2026-10-25）

聚合只用在 knowledge、answering；其他模块用 Service 加 Mapper。核心模块的聚合是纯 Java 类，DO、Wrapper、Page 只在 infrastructure 包，仓储实现里手写转换。不用 MapStruct。

## D-005 Sa-Token 会话先存内存（2026-10-25）

试点单实例部署，会话存在内存，应用重启后需要重新登录。需要多实例时改用 Redis 和 `sa-token-redis-template`。

## D-006 迁移版本号用时间戳（2026-10-25）

每个模块一个迁移目录，版本号统一 `yyyyMMddNNNN`，避免多个目录之间版本冲突。

## D-007 数据库用 ParadeDB（2026-10-26 更新）

改用 ParadeDB 镜像（PostgreSQL 18，按摘要 `sha256:9b15fc38…` 锁定），同时提供 pgvector 向量检索和 pg_search BM25。PR #7 的集成测试验证了 jieba 中文分词、中文关键词 BM25 命中和向量距离排序。升级镜像时更新 `TestDatabase` 和 `deploy/compose.yml` 两处摘要。

## D-008 前端：原型即代码（2026-10-25）

React 19 + Vite + TypeScript + Tailwind CSS v4 + shadcn/ui。页面先用 MSW 假数据做可点击原型，确认后再接真实接口；不做像素级一致性验收。

## D-009 Maven Wrapper（2026-10-25）

Maven Wrapper 3.3.4（only-script）从旧项目复制，固定 Maven 3.9.16。

## D-010 原型方案选定（2026-10-26）

问答页用画布方案 A（对话式，含“查看引用原文”“找不到答案”两个状态），知识库管理页用方案 B（上传优先，显示处理进度和失败提醒）。第 2 周按此做可点击原型。

## D-011 字体先用 Google Fonts（2026-10-26）

思源黑体 / 思源宋体（Noto Sans SC / Noto Serif SC）先从 Google Fonts 加载。客户内网可能访问不了外网，部署到客户环境前改为自托管字体文件。

## D-012 前端依赖版本与 Node 24（2026-10-26）

React 19.2、Vite 8.1、TypeScript 6.0、Vitest 4.1 沿用旧项目验证过的版本；Tailwind CSS 4.3、MSW 2、React Router 7、TanStack Query 5。前端用 Node 24（自带 npm 11），因为 npm 10 安装 Vite 8 依赖时会崩溃。

## D-013 嵌入模型与混合检索（2026-10-26）

嵌入用硅基流动 `BAAI/bge-m3`（1024 维），经 Spring AI 的 OpenAI 兼容客户端调用；嵌入、重排、对话共用一个 `MODEL_API_KEY`。检索为向量 top50 + BM25（jieba）top50，用 RRF（k=60）融合，权限过滤只在 `ChunkSearchMapper` 的 SQL 里。测试和无密钥的离线开发用本地哈希向量（`RAG_EMBEDDING_PROVIDER=hashing`），它不具备语义能力，不能用于评测。

## D-014 重排、生成与拒答（2026-10-26）

重排用硅基流动 `BAAI/bge-reranker-v2-m3`，Spring AI 没有重排抽象，用 JDK HttpClient 调 `/rerank`；对话默认 `deepseek-ai/DeepSeek-V3`（`CHAT_MODEL` 可换）。候选 30 → 重排 → 门槛过滤 → 最多 8 条证据。拒答四道关口：无结果、低于门槛、模型回答 NO_ANSWER、没有有效 [n] 引用。门槛 0.2 是占位值，用评测集校准。先做非流式 `/api/ask`，流式输出随第 3 周问答页一起做。

## D-015 评测集与评测任务（2026-10-26）

评测集用虚构品牌“鲜邻便利”的合成资料（6 篇门店文档 + 1 篇店长专用文档，24 道可回答题、8 道应拒答题，其中 2 道越权题）。CI 的 `eval` 任务用真实模型跑完整链路，越权泄漏不为 0 时失败，其余指标先只报告，用来校准拒答门槛；改动检索、问答、模型或评测集的 PR 自动运行，也可手动指定门槛运行。

## D-016 账号与登录（2026-10-27）

登录用 Sa-Token，令牌放请求头 `Authorization: Bearer`；密码用 Sa-Token 自带的 BCrypt，不新增依赖。角色先只有 ADMIN（管理知识库和账号）与 STAFF（问答），每次校验都读账号表，停用立即生效。账号表为空时用 `RAG_ADMIN_USERNAME` / `RAG_ADMIN_PASSWORD` 建第一个管理员。用户名全局唯一，租户在登录时写进会话。原定第 4 周，提前做是为了让越权指标测到真实的权限边界。

## D-017 知识库按成员授权（2026-10-27）

成员表 `knowledge_base_member` 在 knowledge 模块；建库的人自动成为成员，管理员没有绕过成员的特权，因此检索只有一条规则。过滤仍只写在 `ChunkSearchMapper` 的两条召回 SQL 里，请求里点名无权限的库会被忽略。无权限的知识库与不存在的一样返回 404。评测以只被授权门店库的店员身份提问，并在请求里带上店长专用库。

## D-018 流式问答（2026-10-27）

`POST /api/ask/stream` 用 Spring MVC 的 `SseEmitter`，每次问答一个虚拟线程，阻塞读取 Spring AI 的流式输出，不新增依赖，answering 模块也不引入 Reactor 类型。事件为 `delta`、`done`、`error`；`done` 是最终结果，引用校验不通过时客户端要用拒答替换已显示的文字，NO_ANSWER 在服务端拦住、不流出。Sa-Token 拦截器对 ASYNC 再分派放行（登录已在原始请求里校验）。评测走流式接口，首字延迟按第一个事件计。

## D-019 文档解析：本地 PDFBox 与 POI（2026-10-27）

客户资料主要是 PDF、Word、Excel。新增 Apache PDFBox 3.0.7（PDF）和 Apache POI 5.5.1（`poi-ooxml`，Word / Excel），在 rag-ai 里本地解析，不接 docling 等外部服务：不多一个要部署的服务，客户内网也能用，结果确定可测。不用 Tika：依赖太多，而且输出是平铺文字，会丢掉 Word 标题层级和表格表头。Word 的标题样式转成 Markdown 标题，表格每行转成“表头：值”；PDF 只按阅读顺序提取文字。已知限制：扫描件（需要 OCR）、旧版 .doc、只靠加粗放大而没用标题样式的“标题”都不识别，等拿到脱敏的真实样例再定是否加规则或换解析服务。

## D-020 入库与问答流程按 Spring AI 接口组织（2026-10-27）

流程骨架用 Spring AI 的阶段接口，实现自己写：入库为 `DocumentReader`（rag-ai 的 `DocumentReaders`）→ `DocumentTransformer`（标题感知分块）→ `DocumentWriter`（向量化并写入 chunk）；问答侧之后按 `DocumentRetriever` / `DocumentPostProcessor` / `QueryAugmenter` 改。框架自带的组件（问题改写、Tika 读取器等）可以直接插进来做评测。边界：Spring AI 的 `Document` 只在流程内部使用，不进模块 `api`、不当领域模型，元数据键集中定义；流程编排仍在 `AnswerService`，不用 `RetrievalAugmentationAdvisor`，因为它覆盖不了“相关度低时不调模型直接拒答”和生成后的引用校验。问答侧为此引入 `spring-ai-rag`（版本由 Spring AI BOM 管理），只用它的阶段接口；检索器从 `Query.context` 取提问人身份，缺少时直接拒绝。
