#!/usr/bin/env bash
# 部署脚本的公共函数（D-048），由 backup.sh、restore.sh、upgrade.sh、verify.sh 引入，不单独执行。
# 只依赖 bash、docker（含 compose 插件）和 curl。脚本在部署目录（离线包解压后的目录，或仓库的 deploy/）里运行。

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEPLOY_DIR"

die() {
  echo "错误：$*" >&2
  exit 1
}

info() {
  echo "==> $*"
}

[ -f .env ] || die "没有找到 $DEPLOY_DIR/.env：先复制 .env.example 为 .env 并填写（见 README.md）"

# 读 .env 或 release.env 里的一项；环境变量优先，与 docker compose 的规则一致。
setting() {
  local key="$1" default="${2:-}" value=""
  if [ -n "${!key:-}" ]; then
    echo "${!key}"
    return
  fi
  for file in release.env .env; do
    if [ -f "$file" ] && grep -q "^${key}=" "$file"; then
      value="$(grep "^${key}=" "$file" | tail -n 1 | cut -d= -f2-)"
      break
    fi
  done
  echo "${value:-$default}"
}

compose() {
  local files=(--env-file .env)
  [ -f release.env ] && files+=(--env-file release.env)
  docker compose -f compose.yml "${files[@]}" "$@"
}

APP_URL="http://127.0.0.1:$(setting APP_PORT 8080)"

# 这个部署目录对应的版本：离线包的 release.env 里写着；开发时为 dev。
package_version() {
  setting RAG_VERSION dev
}

# 正在运行的版本，从 /actuator/info 读；app 没在运行时为空。
running_version() {
  curl -fsS --max-time 5 "$APP_URL/actuator/info" 2>/dev/null \
    | grep -o '"version":"[^"]*"' | head -n 1 | cut -d'"' -f4 || true
}

# 等 app 健康检查通过；超时返回 1（迁移失败时 app 起不来，就会超时）。
wait_healthy() {
  local timeout="${1:-300}" waited=0
  until curl -fsS --max-time 5 "$APP_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    if [ "$waited" -ge "$timeout" ]; then
      return 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
}

# 用 TCP 判断：新数据卷第一次启动时，镜像先用只监听本地套接字的临时实例做初始化，套接字上的 pg_isready
# 会提前返回就绪，接着临时实例关闭，正在执行的恢复会被中断。
wait_db() {
  local waited=0
  until compose exec -T db pg_isready -h 127.0.0.1 -U rag -d rag >/dev/null 2>&1; do
    [ "$waited" -ge 120 ] || { sleep 2; waited=$((waited + 2)); continue; }
    die "数据库 2 分钟内没有就绪"
  done
}

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

# 备份的默认位置：部署目录旁边的 backups/，换版本目录后仍在（可在 .env 里用 RAG_BACKUP_DIR 改）。
backup_root() {
  local root
  root="$(setting RAG_BACKUP_DIR "$DEPLOY_DIR/../backups")"
  mkdir -p "$root"
  (cd "$root" && pwd)
}
