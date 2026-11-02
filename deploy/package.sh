#!/usr/bin/env bash
# 打离线包（D-048），由 CI 在打 tag 时调用，也可以在本机手动打。要求 app、web 镜像已按版本号构建好：
#   docker build -f deploy/backend.Dockerfile --build-arg RAG_VERSION=<版本> -t rag-pilot-app:<版本> .
#   docker build -f deploy/web.Dockerfile --build-arg RAG_VERSION=<版本> -t rag-pilot-web:<版本> .
# 用法：deploy/package.sh <版本，如 1.0.0.1> <输出目录>
# 输出：<输出目录>/rag-pilot-<版本>.tar.gz（部署目录：Compose、脚本、手册、.env.example、release.env）
#       <输出目录>/rag-pilot-<版本>-{app,web,db}.image.tar.gz（三个镜像，放进部署目录的 images/ 下再 ./load.sh）
# 镜像分开放，是因为 GitHub Release 单个附件不能超过 2 GB。
set -euo pipefail

version="${1:-}"
out="${2:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "版本号要是 4 位数字，如 1.0.0.1" >&2; exit 1; }
[ -n "$out" ] || { echo "用法：deploy/package.sh <版本> <输出目录>" >&2; exit 1; }

source_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
name="rag-pilot-$version"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

# 数据库镜像按摘要锁定在 compose.yml 里；docker load 之后摘要引用不可用，所以重新打成带版本号的名字。
db_image="$(grep -o 'paradedb/paradedb@sha256:[0-9a-f]*' "$source_dir/compose.yml" | head -n 1)"
docker image inspect "$db_image" >/dev/null 2>&1 || docker pull "$db_image"
docker tag "$db_image" "rag-pilot-db:$version"

mkdir -p "$stage/$name/images"
cp "$source_dir"/{compose.yml,README.md,.env.example,lib.sh,load.sh,install.sh,backup.sh,restore.sh,upgrade.sh,verify.sh} \
  "$stage/$name/"
cat > "$stage/$name/release.env" <<EOF
# 这个部署目录的版本（D-048），由打包脚本生成，不要修改。
RAG_VERSION=$version
RAG_DB_IMAGE=rag-pilot-db:$version
EOF
chmod +x "$stage/$name"/*.sh
tar -czf "$out/$name.tar.gz" -C "$stage" "$name"

for part in app web db; do
  echo "==> 导出 rag-pilot-$part:$version"
  docker save "rag-pilot-$part:$version" | gzip > "$out/$name-$part.image.tar.gz"
done
sum() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
(cd "$out" && sum "$name.tar.gz" "$name"-*.image.tar.gz > "$name.SHA256SUMS")
ls -lh "$out"
