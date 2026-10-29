# rag-pilot

面向连锁多网点企业的通用运营助手，第一版面向新零售门店。它把总部的规则（制度、操作规范、商品知识）和业务数据，变成一线员工能直接用的答案、预警和待办；每个答案都能追溯到出处。最终形态是 RAG + AI 大脑，通过 tools / MCP 对接客户已有的系统；路线见 [ROADMAP.md](ROADMAP.md)。

## 当前阶段：1.0 研发，完成后进入客户试点

1.0 是可信知识问答（通用内核 + 新零售行业包）：上传文档 → 解析与分块 → 混合检索与重排 → 带引用的流式回答，员工端支持会话和持续对话，按知识库授权，管理端按钮级权限。里程碑见 [ROADMAP.md](ROADMAP.md)。研发阶段用合成数据和开源数据评测，客户真实资料在试点时提供。

| 验收指标 | 目标 |
| --- | --- |
| 检索召回 Recall@10 | ≥ 0.85 |
| 引用正确率 | ≥ 0.90 |
| 拒答正确率 | ≥ 0.90 |
| 越权泄漏 | 0 |
| 检索延迟 p95 | < 800 ms |
| 首字延迟 p95 | 参考指标：照常统计，不作为验收硬指标；首字前须有处理中提示（D-030） |

## 技术栈

JDK 25、Spring Boot 4.1、Spring AI 2.0、Spring Modulith 2.1、MyBatis-Plus、Sa-Token、Flyway、PostgreSQL 18（ParadeDB：pgvector 向量检索 + BM25 中文分词）。前端为 React 19 + Vite + Tailwind CSS v4 + shadcn/ui。选型理由见 [DECISIONS.md](DECISIONS.md)。

## 模块

| 模块 | 职责 |
| --- | --- |
| `rag-common` | 共享内核：租户、用户等无业务规则的值对象 |
| `rag-ai` | 外部 AI 系统适配：模型调用、重排、文档解析 |
| `rag-identity` | 账号、登录、角色 |
| `rag-knowledge` | 核心：知识库、文档、分块、授权、混合检索 |
| `rag-answering` | 核心：会话、回答、引用校验、拒答、反馈 |
| `rag-evaluation` | 评测集、评测运行、指标 |
| `rag-app` | 唯一的启动模块，只负责组装和配置 |

## 本地运行

需要 Docker。

```sh
cp deploy/.env.example deploy/.env   # 填写 DB_PASSWORD、MODEL_API_KEY 和 RAG_ADMIN_PASSWORD
docker compose -f deploy/compose.yml --env-file deploy/.env up --build
curl http://localhost:8080/actuator/health   # 后端健康检查，只在本机可访问
```

浏览器打开 `http://<服务器地址>`（端口改 `WEB_PORT`），用管理员账号登录。页面、字体和接口都从这一个地址提供，不访问外网（模型调用除外）。

## 开发

需要 JDK 25 和 Docker（集成测试用 Testcontainers 启动真实 PostgreSQL）。

```sh
./mvnw verify
```

前端需要 Node 24：

```sh
cd frontend
npm ci
npm run dev:mock   # 用 MSW 假接口启动，不需要后端，做可点击原型时用
npm run dev        # 接口转发到本机 8080 端口的后端
npm test && npm run build
```

协作流程见 [AGENTS.md](AGENTS.md)：每项工作先开 Issue，再建分支、开 PR，CI 通过后 squash 合并，不直接推送 main。
