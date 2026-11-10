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
| 工具选择正确率（数据问答，#183） | ≥ 0.80 |
| 工具参数正确率（数据问答，#183） | ≥ 0.80 |
| 越权调用（数据问答，#183） | 0 |
| 检索延迟 p95 | < 800 ms |
| 首字延迟 p95 | 参考指标：照常统计，不作为验收硬指标；首字前须有处理中提示（D-030） |

评测基线（2026-11-06，#159，D-057；回答模型 DeepSeek-V3，评判模型 GLM-4-9B-0414）：

| 评测集 | 题数 | 召回 | 引用正确 | 拒答正确 | 越权 / 被带偏 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 新零售合成集（`eval`，3 轮） | 201 | 98.8% | 97.5% | 100% | 0 / 0 | 误拒 3.2%（目标 ≤ 3%，每轮 2.4% ～ 3.6%） |
| CRUD-RAG（`eval-public`，3 轮） | 500 | 99.8% | 92.3% | 93.7% | 0 / 0 | 清洗应拒答题后；1.0 验收的开源集一项 |
| T2Ranking 大规模检索 | 300 题、2 万段落 | 93.0% | 90.9% | — | 0 / 0 | 只有相关段落标注，作检索基线 |
| TableEval 简体中文表格 | 100 | 97.1% | 72.4% | 80.0% | 0 / 0 | 首次基线，未设目标 |
| 中文注入集（参照 BIPIA，3 轮） | 30 | 100% | 100% | — | 0 / 0 | 补强提示词后；补强前被带偏 3 |
| 工具问答（`eval` 的 `ToolEvaluationRun`，3 轮，#183） | 57 | — | — | — | 0 / 0 | 工具选择 98.8%（100/96.3/100）、参数 100%、回答含期望数值 85.2%～88.9%、无需工具题误调用 0%～12.5%；口径见 [eval/README.md](backend/boot/rag-app/src/test/resources/eval/README.md) |

产品指标（运营概览显示，反映有没有人用、好不好用，不作验收硬指标；按统计时区的自然日、自然周统计）：

| 产品指标 | 口径 |
| --- | --- |
| 提问人数占比 | 期内提问过的员工数 ÷ 现在能用问答的在职员工数（账号启用、所属角色有“使用问答”，还没改初始密码的也算）。没有账号历史，各段、上一周对比都用当前人数 |
| 人均提问数 | 期内提问数 ÷ 期内提问过的员工数 |
| 短时间重复提问比例 | 同一员工 10 分钟内又问了意思相近的问题（问题向量与此前一题的余弦相似度达到知识缺口归类的门槛 `rag.answering.gaps.similarity`）的题数 ÷ 提问数，每题只算一次；重新生成同一问题也算；还没有问题向量的记录（旧记录、后台没算成）不参与 |
| 引用点开率 | 期内被点开过至少一个引用的回答数 ÷ 期内带引用的回答数（拒答、寒暄没有引用，不算分母）。员工点开引用时前端上报（`POST /api/answers/{id}/citations/{index}/opened`，只有提问人能报），库里只记哪条回答的第几个引用和时间，不记人；同一条回答点多次只算一条，点开记在回答被问的那一天所在的时段 |

## 技术栈

JDK 25、Spring Boot 4.1、Spring AI 2.0、Spring Modulith 2.1、MyBatis-Plus、Sa-Token、Flyway、PostgreSQL 18（ParadeDB：pgvector 向量检索 + BM25 中文分词）。前端为 React 19 + Vite + Tailwind CSS v4 + shadcn/ui。选型理由见 [决策记录](docs/decisions/README.md)。

## 模块

后端按类型分三层聚合：`backend/platform`（平台层）、`backend/contexts`（上下文层）、`backend/boot`（启动层），规则见规则卡 [A-11](docs/rules/20-架构.md)。

| 模块 | 层 | 职责 |
| --- | --- | --- |
| `rag-shared` | 平台 | 共享内核与通用工具：租户和用户标识、错误分类、聚合根标记、分页和输入解析，只依赖 JDK |
| `rag-ai` | 平台 | Spring AI 防腐层：按能力提供文字生成、问题改写、向量化、重排、连通测试，上下文不接触 Spring AI |
| `rag-persistence` | 平台 | MyBatis-Plus 组件：依赖、全局配置（Mapper XML 位置、分页插件）和分页转换 |
| `rag-cache` | 平台 | 缓存组件（Redis，经 Redisson）：读不到时加载、提交后清除、Redis 出错时降级读库，上下文看不到 Redis |
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

需要 JDK 25 和 Docker（集成测试用 Testcontainers 启动真实的 PostgreSQL 和 Redis）。

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

协作流程见 [AGENTS.md](AGENTS.md)（路由，规则在 [docs/rules/](docs/rules/00-索引.md)）：每项工作先开 Issue，再建分支、开 PR，CI 通过后 squash 合并，不直接推送 main。

工程指标（口径见[北极星](docs/engineering/北极星.md)）：规则可执行率（带自动检查的规则卡 ÷ 全部规则卡）基线为 27 ÷ 56 ≈ 48%（#196 合并时），#198 后为 28 ÷ 56 = 50%（随[规则卡索引](docs/rules/00-索引.md)更新）。
