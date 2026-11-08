#!/usr/bin/env bash
# 部署验证的 CI 场景（D-048、#138），由 .github/workflows/deploy.yml 调用。全部用发布包和部署脚本完成，与客户现场的步骤一致：
# 镜像由 build.sh 在本机构建；模型用本地确定性实现。
#   ./deploy-scenarios.sh <新版本号> [上一版本的 Release 附件目录]
# 场景：(a) 全新安装（没有上一版本时）或从上一版本升级，之后重启 app，登录会话仍有效（会话在 Redis，D-053）；
#       (b) 停服务备份 → 删卷 → 恢复；
#       (c) 升级到一个迁移会失败的版本：升级脚本停下并提示回退，用升级前的备份回退后系统正常。
set -euo pipefail

version="$1"
previous="${2:-}"
work="$(mktemp -d)"
export RAG_BACKUP_DIR="$work/backups"

env_file() {
  cat > "$1/.env" <<'EOF'
DB_PASSWORD=ci-db-password
REDIS_PASSWORD=ci-redis-password
MODEL_API_KEY=ci
RAG_EMBEDDING_PROVIDER=hashing
RAG_RERANK_PROVIDER=lexical
RAG_CHAT_PROVIDER=extractive
RAG_ADMIN_USERNAME=admin
RAG_ADMIN_PASSWORD=ci-admin-password
RAG_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
WEB_PORT=8081
EOF
}

# 解开发布包。0.9.0.1 及以前的旧格式离线包另带三个镜像文件，放进 images/ 由它自己的 install.sh 加载。
unpack() {
  local packages="$1" ver="$2"
  tar -xzf "$packages/rag-pilot-$ver.tar.gz" -C "$work"
  if compgen -G "$packages/rag-pilot-$ver-*.image.tar.gz" >/dev/null; then
    cp "$packages"/rag-pilot-"$ver"-*.image.tar.gz "$work/rag-pilot-$ver/images/"
  fi
  env_file "$work/rag-pilot-$ver"
  echo "$work/rag-pilot-$ver"
}

title() { echo; echo "######## $* ########"; }

current="$(unpack "$PWD/packages" "$version")"

if [ -n "$previous" ]; then
  previous_version="$(cat "$previous/VERSION")"
  old="$(unpack "$previous" "$previous_version")"
  title "(a) 安装上一版本 $previous_version"
  "$old/install.sh"
  title "(a) 升级 $previous_version → $version"
  "$current/upgrade.sh" --yes
else
  title "(a) 全新安装 $version（没有上一版本，跳过升级）"
  "$current/install.sh"
fi

title "(a) 重启 app 后登录会话仍有效"
app_url="http://127.0.0.1:8080"
token="$(curl -fsS --max-time 10 -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ci-admin-password"}' "$app_url/api/auth/login" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)"
[ -n "$token" ] || { echo "登录失败" >&2; exit 1; }
(cd "$current" && docker compose -f compose.yml --env-file .env --env-file release.env restart app)
for _ in $(seq 60); do
  curl -fsS --max-time 5 "$app_url/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 5
done
status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $token" "$app_url/api/auth/me")"
[ "$status" = "200" ] || { echo "重启 app 后会话失效了（HTTP $status）" >&2; exit 1; }
echo "重启后同一个令牌仍能访问 /api/auth/me"

title "(a) Redis 停掉：已登录的请求被拒绝、app 不挂；恢复后同一个令牌继续有效"
compose() { (cd "$current" && docker compose -f compose.yml --env-file .env --env-file release.env "$@"); }
compose stop redis
status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 -H "Authorization: Bearer $token" "$app_url/api/auth/me" || true)"
echo "Redis 停掉时 /api/auth/me 返回 HTTP $status"
[ "$status" != "200" ] && [ "$status" != "000" ] || { echo "Redis 停掉时应拒绝请求并及时返回（HTTP $status）" >&2; exit 1; }
curl -fsS --max-time 10 "$app_url/actuator/health/liveness" | grep -q '"status":"UP"' || { echo "Redis 停掉后 app 进程不应退出或失去存活" >&2; exit 1; }
compose start redis
for _ in $(seq 30); do
  [ "$(compose ps --format '{{.Health}}' redis)" = "healthy" ] && break
  sleep 2
done
# 缓存出错后 30 秒内跳过 Redis；会话没有这个窗口，恢复后立即可用，这里留出重连时间。
for _ in $(seq 15); do
  status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $token" "$app_url/api/auth/me" || true)"
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "Redis 恢复后同一个令牌没有恢复有效（HTTP $status）" >&2; exit 1; }
echo "Redis 恢复后同一个令牌仍有效"

title "(b) 停服务备份 → 删卷 → 恢复"
backup="$("$current/backup.sh" | tail -n 1)"
(cd "$current" && docker compose -f compose.yml --env-file .env --env-file release.env down -v)
"$current/restore.sh" "$backup" --yes
"$current/verify.sh"

title "(c) 升级到迁移会失败的版本"
broken_version="${version%.*}.$(( ${version##*.} + 1000 ))"
broken="$work/rag-pilot-$broken_version"
cp -R "$current" "$broken"
sed -i.bak "s/^RAG_VERSION=.*/RAG_VERSION=$broken_version/" "$broken/release.env"
# 发布包里带着 Dockerfile：在这个版本的 app 镜像里多加一个必然失败的迁移，upgrade.sh 照常用 build.sh 构建它。
mkdir -p "$broken/broken"
echo "SELECT 1 / 0;" > "$broken/broken/V209912310001__ci_broken.sql"
echo '!broken' >> "$broken/.dockerignore"
cat >> "$broken/app.Dockerfile" <<'EOF'
COPY broken/V209912310001__ci_broken.sql /opt/rag/broken/
ENV SPRING_FLYWAY_LOCATIONS=classpath:db/migration/identity,classpath:db/migration/knowledge,classpath:db/migration/answering,classpath:db/migration/evaluation,classpath:db/migration/settings,classpath:db/migration/data,filesystem:/opt/rag/broken
EOF
if "$broken/upgrade.sh" --yes 2> "$work/upgrade-error.txt"; then
  echo "迁移失败的版本居然升级成功了" >&2
  exit 1
fi
cat "$work/upgrade-error.txt"
grep -q "升级失败" "$work/upgrade-error.txt"
rollback="$(grep -o "restore.sh [^ ]*" "$work/upgrade-error.txt" | cut -d' ' -f2)"
title "(c) 回退：在版本 $version 的目录里用升级前的备份恢复"
"$current/restore.sh" "$rollback" --yes
"$current/verify.sh"
echo
echo "部署验证全部通过"
