# 运维手册

写给客户现场的运维人员：会用 Linux 和 Docker，不需要了解这个项目的代码。系统在 HTTP 下用 Docker Compose 部署；HTTPS、统一入口、监控告警按客户现有的方式加在前面（不在本手册范围）。决定见 DECISIONS.md D-048。

## 一、准备

- 一台 Linux 服务器（x86_64），4 核 8 GB 内存以上，磁盘 100 GB 以上（数据库、上传文件和备份）。
- Docker 24 以上，带 Compose 插件（`docker compose version` 能输出版本）；`curl`。
- 能访问模型服务（默认硅基流动 `https://api.siliconflow.cn`）；除此之外不需要访问外网。
- 一个运维账号，能执行 `docker` 命令。下面的命令都用这个账号执行。

## 二、离线包

每个版本在 GitHub Release（`https://github.com/liming-cloud/rag-pilot/releases`）上有 5 个文件：

| 文件 | 内容 |
| --- | --- |
| `rag-pilot-<版本>.tar.gz` | 部署目录：本手册、`compose.yml`、脚本、`.env.example`、`release.env`（版本号） |
| `rag-pilot-<版本>-app.image.tar.gz` | 后端镜像 |
| `rag-pilot-<版本>-web.image.tar.gz` | 前端和 Nginx 镜像 |
| `rag-pilot-<版本>-db.image.tar.gz` | 数据库镜像（ParadeDB / PostgreSQL 18） |
| `rag-pilot-<版本>.SHA256SUMS` | 校验和 |

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
mv rag-pilot-1.0.0.1-*.image.tar.gz rag-pilot-1.0.0.1/images/
cd rag-pilot-1.0.0.1
cp .env.example .env && chmod 600 .env
vi .env                                           # 至少填 DB_PASSWORD、MODEL_API_KEY、RAG_ADMIN_PASSWORD、RAG_MASTER_KEY
./install.sh
```

`install.sh` 依次：加载三个镜像 → 启动 → 等健康检查通过 → 运行 `verify.sh`。看到“部署验证通过”就装好了，浏览器打开 `http://<服务器地址>`（端口是 `.env` 里的 `WEB_PORT`），用 `RAG_ADMIN_USERNAME` / `RAG_ADMIN_PASSWORD` 登录。

`.env` 里每一项的含义见 `.env.example` 的注释。几项要特别注意：

- `RAG_MASTER_KEY`：用 `openssl rand -base64 32` 生成，**装好后不要再改**，并抄存一份到安全的地方（见“七、主密钥”）。
- `RAG_ADMIN_PASSWORD`：只在第一次启动时用来创建超级管理员，之后在界面上改密码；改了以后 `verify.sh` 要用环境变量 `VERIFY_ADMIN_PASSWORD` 给新密码。
- `.env` 里有密码和密钥，不要发给别人，不要放进备份。

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

备份**不包含 `.env` 和主密钥**，请另外保管。备份里有客户的资料和员工的提问，要放在只有运维能访问的地方（脚本已把文件权限设为 600）。

定时备份由运维配置，例如每天凌晨 2 点备份、只保留最近 7 份（`crontab -e`）：

```
0 2 * * * cd /opt/rag-pilot/rag-pilot-1.0.0.1 && ./backup.sh >> /opt/rag-pilot/backups/backup.log 2>&1 && ls -1dt /opt/rag-pilot/backups/2*/ | tail -n +8 | xargs -r rm -rf
```

升级到新版本后，记得把 cron 里的目录改成新版本的。

### 恢复

```bash
./restore.sh /opt/rag-pilot/backups/20261102-020000
```

恢复会**覆盖当前的全部数据**（输入 yes 确认）。备份的版本必须与部署目录的版本一致：要恢复旧版本的备份，到旧版本的部署目录里执行（目录删了就重新下载那个版本的离线包、`./load.sh`）。恢复完会自动启动，然后运行 `./verify.sh` 检查。

恢复到一台新服务器：按“三、安装”解压同一个版本的离线包，`.env` 用原来的（尤其是同一个 `RAG_MASTER_KEY`），`./load.sh` 加载镜像后直接 `./restore.sh <备份目录>`，不需要先 `install.sh`。

## 六、升级与回退

### 升级

```bash
cd /opt/rag-pilot
tar -xzf rag-pilot-1.1.0.1.tar.gz
mv rag-pilot-1.1.0.1-*.image.tar.gz rag-pilot-1.1.0.1/images/
cp rag-pilot-1.0.0.1/.env rag-pilot-1.1.0.1/.env
cd rag-pilot-1.1.0.1
./load.sh
./upgrade.sh
```

`upgrade.sh` 依次：检查镜像和备份目录的剩余空间（至少 2 GB）→ **自动备份**（备份目录名带 `before-<新版本>`）→ 用新版本启动（数据库结构在启动时自动升级）→ 等健康检查通过 → 运行 `verify.sh`。整个过程停服务几分钟。

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
| 提问都答“找不到”，或上传的文档一直“失败：向量化失败” | 模型服务不可用或密钥失效：在管理端“模型管理”测试连通，检查 `MODEL_API_KEY` 和服务器能否访问模型服务 |
| `app` 反复重启，日志里有 `Flyway` / `Migration` 报错 | 数据库结构升级失败：按“六、回退”用升级前的备份恢复，把日志发给开发方 |
| `app` 启动失败，日志里有“rag.xxx 必须…” | `.env` 里的配置值不合法，按提示改正后 `up -d app` |
| 磁盘满 | `df -h` 看哪个分区；清理旧备份（`backups/`）和不用的镜像（`docker image prune`，不要删正在用的版本） |
| `restore.sh` 提示“这份备份是版本 X 的” | 到版本 X 的部署目录里恢复，见“五、恢复” |
| 超级管理员登录不了 | 见“八” |

需要开发方协助时，把 `./verify.sh` 的输出和 `logs --tail 500 app` 一起发过来（日志里没有密码和密钥）。
