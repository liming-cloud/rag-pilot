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
