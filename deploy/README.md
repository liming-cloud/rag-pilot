# 运维手册

写给客户现场的运维人员：会用 Linux 和 Docker，不需要了解这个项目的代码。系统在 HTTP 下用 Docker Compose 部署；HTTPS、统一入口、监控告警按客户现有的方式加在前面（不在本手册范围）。决定见 DECISIONS.md D-048、D-050。

## 一、准备

- 一台 Linux 服务器（x86_64 或 arm64），4 核 8 GB 内存以上，磁盘 100 GB 以上（数据库、上传文件和备份）。
- Docker 24 以上，带 Compose 插件（`docker compose version` 能输出版本）；`curl`。
- 能访问模型服务（默认硅基流动 `https://api.siliconflow.cn`）。
- 安装和升级时能访问镜像仓库：系统的镜像在服务器上构建，要拉数据库镜像和 JRE、Nginx 基础镜像（第一次约 600 MB，之后只在它们变化时才下载）。默认从 Docker Hub 拉；用镜像源或内网仓库时见“三、安装”里的 `RAG_DB_IMAGE` 等三项。
- 一个运维账号，能执行 `docker` 命令。下面的命令都用这个账号执行。

## 二、发布包

每个版本在 GitHub Release（`https://github.com/liming-cloud/rag-pilot/releases`）上有 2 个文件：

| 文件 | 内容 |
| --- | --- |
| `rag-pilot-<版本>.tar.gz` | 部署目录：本手册、`compose.yml`、脚本、`.env.example`、`release.env`（版本号），编译好的后端 `app/rag-app.jar` 和前端 `web/dist/`，以及在服务器上构建镜像用的 `app.Dockerfile`、`web.Dockerfile` |
| `rag-pilot-<版本>.SHA256SUMS` | 校验和 |

镜像不随发布包提供：`install.sh`、`upgrade.sh` 会调用 `build.sh`，在服务器上用包里的 jar 和 dist 构建 `rag-pilot-app:<版本>`、`rag-pilot-web:<版本>`。数据库镜像和两个基础镜像按摘要锁定，保证每台服务器拿到的是同一个镜像。

版本号是 4 位：主.次.修订.构建号（如 `1.0.0.1`）。主版本变了说明有不兼容的数据库变更，升级前要看 Release 说明。

建议目录结构（每个版本一个目录，备份放在旁边，换版本不影响备份）：

```
/opt/rag-pilot/
├── rag-pilot-1.0.0.1/     当前版本的部署目录
├── rag-pilot-0.9.0.1/     上一个版本，回退时用，确认新版本没问题后可以删
└── backups/               备份（也可以在 .env 里用 RAG_BACKUP_DIR 指到单独的磁盘）
```

## 三、安装

```bash
cd /opt/rag-pilot
sha256sum -c rag-pilot-1.0.0.1.SHA256SUMS        # 校验下载的文件
tar -xzf rag-pilot-1.0.0.1.tar.gz
cd rag-pilot-1.0.0.1
cp .env.example .env && chmod 600 .env
vi .env                                           # 至少填 DB_PASSWORD、REDIS_PASSWORD、MODEL_API_KEY、RAG_ADMIN_PASSWORD、RAG_MASTER_KEY
./install.sh
```

`install.sh` 依次：构建镜像（`build.sh`：拉数据库、Redis 镜像和基础镜像，用包里的 jar 和 dist 构建 app、web 镜像）→ 启动 → 等健康检查通过 → 运行 `verify.sh`。看到“部署验证通过”就装好了，浏览器打开 `http://<服务器地址>`（端口是 `.env` 里的 `WEB_PORT`），用 `RAG_ADMIN_USERNAME` / `RAG_ADMIN_PASSWORD` 登录。

`.env` 里每一项的含义见 `.env.example` 的注释。几项要特别注意：

- `RAG_MASTER_KEY`：用 `openssl rand -base64 32` 生成，**装好后不要再改**，并抄存一份到安全的地方（见“七、主密钥”）。
- `RAG_ADMIN_PASSWORD`：只在第一次启动时用来创建超级管理员，之后在界面上改密码；改了以后 `verify.sh` 要用环境变量 `VERIFY_ADMIN_PASSWORD` 给新密码。
- `.env` 里有密码和密钥，不要发给别人，不要放进备份。构建镜像时只用到 jar、dist 和 `nginx.conf`，`.env` 不会进镜像（见包里的 `.dockerignore`）。
- `REDIS_PASSWORD`：Redis 的密码，用 `openssl rand -hex 24` 生成。Redis 存二级缓存和登录会话，只在 Compose 内部网络里，不对外开放端口。`compose.yml` 已给 app 设好 `RAG_CACHE_REDIS_ENABLED=true`；不经 Compose 直接运行 jar 时默认不连 Redis，缓存只在进程内、重启后要重新登录（D-054）。
- `RAG_DB_IMAGE`、`RAG_REDIS_IMAGE`、`RAG_JRE_IMAGE`、`RAG_NGINX_IMAGE`：服务器不能直接访问 Docker Hub 时，改成镜像源或内网仓库里的地址，`@sha256:` 后面的摘要保持与 `compose.yml` 里的一致。不填时从 Docker Hub 拉。

`verify.sh` 会建一个叫“部署验证”的知识库和一个测试账号 `deploy-verify`，用来检查检索、问答和权限；以后每次运行都复用它们。不想留可以在管理端删掉，下次运行会重新建。

## 四、日常检查

```bash
cd /opt/rag-pilot/rag-pilot-1.0.0.1
docker compose --env-file .env --env-file release.env ps       # 三个服务都是 running（healthy）
curl http://127.0.0.1:8080/actuator/health                    # {"status":"UP"}
curl http://127.0.0.1:8080/actuator/info                      # 当前版本
./verify.sh                                                   # 完整检查一遍（约 1 分钟）
docker compose --env-file .env --env-file release.env logs --tail 200 app   # 看后端日志
```

容器日志按大小自动轮转（每个服务最多约 100 MB）。管理端左下角也显示版本号。

关键事件在日志里各占一行，以 `event=` 开头、后面是 `键=值`，用 `grep` 就能筛出来（日志里没有密码、密钥和员工的问题原文）：

| 事件 | 什么时候出现 |
| --- | --- |
| `event=model_call_failed` | 模型调用失败或超时：`usage` 是用途（chat、rewrite、embedding、rerank），`error` 是异常类型（如 `HttpTimeoutException`、`ConnectException`） |
| `event=answer_refused` | 答不上来：`reason` 是原因（`NO_EVIDENCE` 没检索到、`LOW_RELEVANCE` 相关度太低、`MODEL_DECLINED` 模型判断依据不足、`UNCITED` 回答没有有效引用），`top` 是最高相关度 |
| `event=query_rewritten` | 相关度偏低时换个说法再找一次：`outcome` 是结果（`REWRITTEN` 改写了、`NO_CHANGE` 没改、`TIMED_OUT` 改写超时、`SKIPPED_BELOW_FLOOR` 分数太低不改写直接拒答） |
| `event=document_failed` | 文档入库失败：`kind=content` 是文件本身的问题，`kind=model` 是模型暂时不可用（10 分钟后自动重试一次） |
| `event=login_failed`、`event=account_locked` | 登录失败（`reason`）、连续输错被锁 |
| `event=access_denied` | 越权被拒：缺的权限点（`permission`）或不能管理的知识库（`knowledgeBase`） |
| `event=cache_failure` | Redis 出错或超时：`cache` 是哪种缓存、`op` 是什么操作。之后 30 秒内跳过 Redis（进程内的一级缓存照常用，没有时读数据库），问答照常；恢复后先清空 Redis 里的缓存再用。登录会话也在 Redis 里，Redis 不可用时**不能登录、已登录的请求会被拒绝** |

```bash
docker compose --env-file .env --env-file release.env logs --since 24h app | grep 'event=model_call_failed'
curl -s http://127.0.0.1:8080/actuator/metrics/gen_ai.client.operation         # 模型调用次数和耗时
curl -s 'http://127.0.0.1:8080/actuator/metrics/gen_ai.client.token.usage?tag=gen_ai.token.type:total'  # 模型调用用掉的 token（可再按 gen_ai.request.model 筛）
curl -s 'http://127.0.0.1:8080/actuator/metrics/http.server.requests?tag=status:500'   # 出错的接口请求
curl -s 'http://127.0.0.1:8080/actuator/metrics/rag.cache.gets?tag=result:local_hit'  # 进程内一级缓存命中次数（result 还有 hit=Redis 命中、miss、error）
```

指标只在服务器本机的 8080 端口能看，页面所在的 80 端口不转发。

## 五、备份与恢复

### 备份

```bash
./backup.sh                       # 备份到 backups/<日期-时间>/
./backup.sh /data/backup/manual   # 或指定目录（必须是空目录）
```

备份期间会**停掉后端几分钟**，页面上的操作提示“系统维护中”，完成后自动恢复，所以请在下班后等低峰时间做。停后端时会先等正在生成的回答保存完，最多等一分钟左右。备份目录里有：

- `db.dump`：数据库（问答记录、账号、知识库、加密后的服务商密钥）；
- `files.tar.gz`：上传的原始文件；
- `VERSION`：备份时运行的版本；`SHA256SUMS`：校验和。

备份**不包含 `.env` 和主密钥**，请另外保管。备份也不含 Redis：里面只有缓存（会重新生成）和登录会话，恢复后员工重新登录即可。备份里有客户的资料和员工的提问，要放在只有运维能访问的地方（脚本已把文件权限设为 600）。

定时备份由运维配置，例如每天凌晨 2 点备份、只保留最近 7 份（`crontab -e`）：

```
0 2 * * * cd /opt/rag-pilot/rag-pilot-1.0.0.1 && ./backup.sh >> /opt/rag-pilot/backups/backup.log 2>&1 && ls -1dt /opt/rag-pilot/backups/2*/ | tail -n +8 | xargs -r rm -rf
```

升级到新版本后，记得把 cron 里的目录改成新版本的。

### 恢复

```bash
./restore.sh /opt/rag-pilot/backups/20261102-020000
```

恢复会**覆盖当前的全部数据**（输入 yes 确认）。备份的版本必须与部署目录的版本一致：要恢复旧版本的备份，到旧版本的部署目录里执行（目录删了就重新下载那个版本的发布包解压；镜像被清理了也没关系，`restore.sh` 会先用 `build.sh` 重新构建）。恢复完会自动启动，然后运行 `./verify.sh` 检查。

恢复到一台新服务器：按“三、安装”解压同一个版本的发布包，`.env` 用原来的（尤其是同一个 `RAG_MASTER_KEY`），直接 `./restore.sh <备份目录>`（会先构建镜像），不需要先 `install.sh`。

## 六、升级与回退

### 升级

```bash
cd /opt/rag-pilot
sha256sum -c rag-pilot-1.1.0.1.SHA256SUMS
tar -xzf rag-pilot-1.1.0.1.tar.gz
cp rag-pilot-1.0.0.1/.env rag-pilot-1.1.0.1/.env
cd rag-pilot-1.1.0.1
./upgrade.sh
```

`upgrade.sh` 依次：构建新版本的镜像（构建时旧版本照常服务）→ 检查备份目录的剩余空间（至少 2 GB）→ **自动备份**（备份目录名带 `before-<新版本>`）→ 用新版本启动（数据库结构在启动时自动升级）→ 等健康检查通过 → 运行 `verify.sh`。停服务的时间只有备份和启动这几分钟。

从没有 Redis 的版本升级时，先在复制过来的 `.env` 里加上 `REDIS_PASSWORD`（见“三、安装”），否则 `upgrade.sh` 会提示后退出。升级、重启 app 后员工不用重新登录（会话在 Redis 里）。

从 0.9.0.1（旧格式离线包，镜像随包提供）升级也是同样的步骤：新版本的镜像在服务器上构建，数据库镜像从镜像仓库重新拉一次（与旧版本是同一个镜像）。

### 回退

数据库结构的升级不能反向执行，所以回退的办法是**用升级前的备份恢复，再用旧版本运行**。升级后新写入的数据（新的问答、上传的资料）会丢失，所以发现问题要尽早回退。

升级失败时 `upgrade.sh` 会停掉新版本并打印回退命令，照着执行即可：

```bash
cd /opt/rag-pilot/rag-pilot-1.0.0.1
./restore.sh /opt/rag-pilot/backups/20261102-153000-before-1.1.0.1
```

升级成功但用了一段时间后想回退，也是同样两步；注意这期间的新数据会丢。

## 七、主密钥

`RAG_MASTER_KEY` 用来加密在管理端“模型管理”里录入的服务商密钥（D-043）。

- 装好后不要改。改了以后，已存的服务商密钥解不开，需要在模型管理里重新录入；其他数据不受影响。
- 备份里没有它。丢了也只需要重新录入服务商密钥，所以抄存一份比备份它更安全。
- 没有设置时不能在管理端保存服务商密钥，系统仍可以用 `.env` 里的 `MODEL_API_KEY`。

## 八、超级管理员忘记密码

全系统只有一个超级管理员。忘记密码或被锁定时：

1. 在 `.env` 里设置 `RAG_ADMIN_RESET_PASSWORD=<新密码>`；
2. `docker compose --env-file .env --env-file release.env up -d app` 重启后端，密码被重置并解锁；
3. **删掉这一行**，再执行一次第 2 步（不删的话每次重启都会重置）。

其他账号的密码由超级管理员或有“重置密码”权限的管理员在管理端的“用户”里重置。

## 九、常见故障

| 现象 | 原因和处理 |
| --- | --- |
| 页面能打开，操作都提示“系统维护中” | 后端没在运行：备份、升级时是正常的；否则看 `logs app`，常见是数据库没起来或 `.env` 填错 |
| 提问都答“找不到”，或上传的文档一直“失败：向量化失败” | 模型服务不可用或密钥失效：`grep event=model_call_failed` 看是哪个用途、什么错误；在管理端“模型管理”测试连通，检查 `MODEL_API_KEY` 和服务器能否访问模型服务。恢复后在知识库里点“重新处理”，或等自动重试 |
| `app` 反复重启，日志里有 `Flyway` / `Migration` 报错 | 数据库结构升级失败：按“六、回退”用升级前的备份恢复，把日志发给开发方 |
| 所有人都登录不了，或刚登录就被退出；`logs app` 里有 `RedisConnectionException` 或 `event=cache_failure` | Redis 没在运行或密码不对：`docker compose ... ps redis` 看状态，`logs redis` 看原因；检查 `.env` 的 `REDIS_PASSWORD`，改了以后 `up -d redis app`。Redis 恢复后不用做别的，缓存会自动清空重建 |
| 在数据库里手工改了账号、角色、系统设置或模型配置，界面上没变 | 这些数据有缓存，只有经界面改才会立即清除：`up -d --force-recreate app` 重启后端（启动后先清空缓存），或等 1 小时缓存过期 |
| `app` 启动失败，日志里有“rag.xxx 必须…” | `.env` 里的配置值不合法，按提示改正后 `up -d app` |
| `build.sh` 拉镜像失败（`pull access denied`、超时） | 服务器访问不了镜像仓库：配置 Docker 的镜像源，或在 `.env` 里把 `RAG_DB_IMAGE`、`RAG_REDIS_IMAGE`、`RAG_JRE_IMAGE`、`RAG_NGINX_IMAGE` 改成能访问的仓库地址（摘要不变），再运行一次 |
| 磁盘满 | `df -h` 看哪个分区；清理旧备份（`backups/`）和不用的镜像（`docker image prune`，不要删正在用的版本；旧版本的镜像删了，回退时 `restore.sh` 会重新构建） |
| `restore.sh` 提示“这份备份是版本 X 的” | 到版本 X 的部署目录里恢复，见“五、恢复” |
| 超级管理员登录不了 | 见“八” |

需要开发方协助时，把 `./verify.sh` 的输出和 `logs --tail 500 app` 一起发过来（日志里没有密码和密钥）。
