#!/usr/bin/env bash
# 打发布包（#138），由 CI 在打 tag 时调用，也可以在本机手动打（开发时用来在本机安装）。需要 JDK 25 和 Node 24。
# 编译后端 jar 和前端 dist，连同部署目录一起打成一个包；不构建镜像，镜像由宿主机上的 build.sh 构建。
# 用法：deploy/package.sh <版本，如 1.0.0.1> <输出目录>
# 输出：<输出目录>/rag-pilot-<版本>.tar.gz（Compose、脚本、手册、.env.example、release.env、Dockerfile、
#       app/rag-app.jar、web/dist）和 <输出目录>/rag-pilot-<版本>.SHA256SUMS
set -euo pipefail

version="${1:-}"
out="${2:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "版本号要是 4 位数字，如 1.0.0.1" >&2; exit 1; }
[ -n "$out" ] || { echo "用法：deploy/package.sh <版本> <输出目录>" >&2; exit 1; }

source_dir="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$source_dir/.." && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
name="rag-pilot-$version"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

echo "==> 编译后端 jar"
(cd "$repo" && ./mvnw -B -ntp -q -pl backend/boot/rag-app -am package -DskipTests)

echo "==> 构建前端（版本号 $version 写进管理端左下角）"
(cd "$repo/frontend" && npm ci --no-audit --no-fund --loglevel=error && VITE_APP_VERSION="$version" npm run build)

dir="$stage/$name"
mkdir -p "$dir/app" "$dir/web"
cp "$source_dir"/{compose.yml,README.md,.env.example,.dockerignore,app.Dockerfile,web.Dockerfile,nginx.conf} "$dir/"
cp "$source_dir"/{lib.sh,build.sh,install.sh,backup.sh,restore.sh,upgrade.sh,verify.sh} "$dir/"
cp "$repo/backend/boot/rag-app/target/rag-app.jar" "$dir/app/"
cp -R "$repo/frontend/dist" "$dir/web/dist"
cat > "$dir/release.env" <<ENV
# 这个部署目录的版本（D-048），由打包脚本生成，不要修改。
RAG_VERSION=$version
ENV
chmod +x "$dir"/*.sh
tar -czf "$out/$name.tar.gz" -C "$stage" "$name"

sum() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
(cd "$out" && sum "$name.tar.gz" > "$name.SHA256SUMS")
ls -lh "$out/$name.tar.gz"
