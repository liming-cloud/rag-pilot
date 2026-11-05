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
| 被资料里的指令带偏（提示词注入，#145） | 0 |
| 检索延迟 p95 | < 800 ms |
| 首字延迟 p95 | 参考指标：照常统计，不作为验收硬指标；首字前须有处理中提示（D-030） |

## 技术栈

JDK 25、Spring Boot 4.1、Spring AI 2.0、Spring Modulith 2.1、MyBatis-Plus、Sa-Token、Flyway、PostgreSQL 18（ParadeDB：pgvector 向量检索 + BM25 中文分词）。前端为 React 19 + Vite + Tailwind CSS v4 + shadcn/ui。选型理由见 [DECISIONS.md](DECISIONS.md)。

## 模块

后端按类型分三层聚合：`backend/platform`（平台层）、`backend/contexts`（上下文层）、`backend/boot`（启动层），规则见 AGENTS.md“目录结构”。

| 模块 | 层 | 职责 |
| --- | --- | --- |
| `rag-shared` | 平台 | 共享内核与通用工具：租户和用户标识、错误分类、聚合根标记、分页和输入解析，只依赖 JDK |
| `rag-ai` | 平台 | Spring AI 防腐层：按能力提供文字生成、问题改写、向量化、重排、连通测试，上下文不接触 Spring AI |
| `rag-persistence` | 平台 | MyBatis-Plus 组件：依赖、全局配置（Mapper XML 位置、分页插件）和分页转换 |
| `rag-identity` | 上下文 | 账号、登录、角色与权限点（按钮级 RBAC） |
| `rag-knowledge` | 上下文 | 核心：知识库、文档解析与分块、授权、混合检索 |
| `rag-answering` | 上下文 | 核心：会话、回答、引用校验、拒答、反馈、知识缺口与运营统计 |
| `rag-evaluation` | 上下文 | 评测集、指标、报告，答案评判的规则 |
| `rag-settings` | 上下文 | 系统设置：模型管理（服务商、各用途的模型、变更记录）、行业包与界面文案 |
| `rag-app` | 启动 | 唯一的启动模块，只负责组装和配置 |

## 本地运行

需要 Docker、JDK 25 和 Node 24。客户现场用发布包安装、升级、备份恢复，见 [deploy/README.md](deploy/README.md)（运维手册）；开发时也按同样的方式，先打一个本地发布包再安装（D-050）：

```sh
deploy/package.sh 0.0.0.1 /tmp/rag-pilot          # 编译 jar 和 dist，打成发布包
tar -xzf /tmp/rag-pilot/rag-pilot-0.0.0.1.tar.gz -C /tmp/rag-pilot
cd /tmp/rag-pilot/rag-pilot-0.0.0.1
cp .env.example .env                              # 填写 DB_PASSWORD、MODEL_API_KEY 和 RAG_ADMIN_PASSWORD
./install.sh                                      # 在本机构建镜像、启动、跑部署验证
curl http://localhost:8080/actuator/health        # 后端健康检查，只在本机可访问
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
npm run lint && npm test && npm run build
```

协作流程见 [AGENTS.md](AGENTS.md)：每项工作先开 Issue，再建分支、开 PR，CI 通过后 squash 合并，不直接推送 main。
