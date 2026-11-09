# D-003 Maven 聚合 7 个模块、单进程部署（2026-10-25）

4 个限界上下文（identity、knowledge、answering、evaluation）各一个模块，加 rag-common、rag-ai、rag-app。模块依赖方向由 Maven 强制，包级边界由 Modulith 测试检查。试点只部署 rag-app 一个进程；需要时新增 rag-worker 启动模块拆出解析进程。
