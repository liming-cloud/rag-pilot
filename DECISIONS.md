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

## D-007 数据库镜像暂用 pgvector（2026-10-25）

暂用旧项目已验证的 `pgvector/pgvector:0.8.5-pg18-trixie`。计划改用 ParadeDB（BM25 + jieba 中文分词），在第 1 周的独立 PR 中验证镜像与中文检索后再切换；不通过则退回应用内分词加 Postgres 全文索引。

## D-008 前端：原型即代码（2026-10-25）

React 19 + Vite + TypeScript + Tailwind CSS v4 + shadcn/ui。页面先用 MSW 假数据做可点击原型，确认后再接真实接口；不做像素级一致性验收。

## D-009 Maven Wrapper（2026-10-25）

Maven Wrapper 3.3.4（only-script）从旧项目复制，固定 Maven 3.9.16。
