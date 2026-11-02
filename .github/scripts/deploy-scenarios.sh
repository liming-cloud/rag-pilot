#!/usr/bin/env bash
# 部署验证的 CI 场景（D-048），由 .github/workflows/deploy.yml 调用。全部用离线包和部署脚本完成，与客户现场的步骤一致；
# 模型用本地确定性实现，不访问外网。
#   ./deploy-scenarios.sh <新版本号> [上一版本的离线包目录]
# 场景：(a) 全新安装（没有上一版本时）或从上一版本升级；(b) 停服务备份 → 删卷 → 恢复；
#       (c) 升级到一个迁移会失败的版本：升级脚本停下并提示回退，用升级前的备份回退后系统正常。
set -euo pipefail

version="$1"
previous="${2:-}"
work="$(mktemp -d)"
export RAG_BACKUP_DIR="$work/backups"

env_file() {
  cat > "$1/.env" <<'EOF'
DB_PASSWORD=ci-db-password
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

# 解开离线包：部署目录 + 三个镜像放进 images/。
unpack() {
  local packages="$1" ver="$2"
  tar -xzf "$packages/rag-pilot-$ver.tar.gz" -C "$work"
  cp "$packages"/rag-pilot-"$ver"-*.image.tar.gz "$work/rag-pilot-$ver/images/"
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
  "$current/load.sh"
  "$current/upgrade.sh" --yes
else
  title "(a) 全新安装 $version（没有上一版本，跳过升级）"
  "$current/install.sh"
fi

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
docker tag "rag-pilot-web:$version" "rag-pilot-web:$broken_version"
docker tag "rag-pilot-db:$version" "rag-pilot-db:$broken_version"
sed -i.bak "s/^RAG_DB_IMAGE=.*/RAG_DB_IMAGE=rag-pilot-db:$broken_version/" "$broken/release.env"
# 在正常镜像上多加一个必然失败的迁移。
mkdir -p "$work/broken-image"
echo "SELECT 1 / 0;" > "$work/broken-image/V209912310001__ci_broken.sql"
cat > "$work/broken-image/Dockerfile" <<EOF
FROM rag-pilot-app:$version
COPY V209912310001__ci_broken.sql /opt/rag/broken/
ENV SPRING_FLYWAY_LOCATIONS=classpath:db/migration/identity,classpath:db/migration/knowledge,classpath:db/migration/answering,classpath:db/migration/evaluation,classpath:db/migration/settings,filesystem:/opt/rag/broken
EOF
docker build -q -t "rag-pilot-app:$broken_version" "$work/broken-image"
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
