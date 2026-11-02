#!/usr/bin/env bash
# 停服务备份（D-048）：停 app → 导出数据库 → 打包上传文件 → 启动 app。期间页面显示“系统维护中”，一般几分钟。
# 用法：./backup.sh [备份目录]   不给目录时备份到 backups/<时间>（见 RAG_BACKUP_DIR）。
# 备份不包含 .env 和主密钥 RAG_MASTER_KEY，请另行保管（见 README.md）。备份文件含客户数据，注意存放位置和权限。
source "$(dirname "$0")/lib.sh"

target="${1:-$(backup_root)/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$target"
target="$(cd "$target" && pwd)"
[ -z "$(ls -A "$target")" ] || die "备份目录 $target 不是空的"

version="$(running_version)"
version="${version:-$(package_version)}"

info "停止 app（页面显示“系统维护中”）"
compose stop app
restart_app() {
  info "启动 app"
  compose start app >/dev/null
  wait_healthy || echo "警告：app 没有在 5 分钟内恢复，请查看 docker compose logs app" >&2
}
# 中途失败也要把 app 启动回来。
trap restart_app EXIT

wait_db
info "导出数据库"
compose exec -T db pg_dump -U rag -d rag -Fc > "$target/db.dump"
[ -s "$target/db.dump" ] || die "数据库导出为空"

info "打包上传文件"
compose run --rm --no-deps --user 0 -v "$target:/backup" --entrypoint tar app \
  -czf /backup/files.tar.gz -C /data/files .

echo "$version" > "$target/VERSION"
(cd "$target" && checksum db.dump files.tar.gz VERSION > SHA256SUMS)
chmod 600 "$target"/*

trap - EXIT
restart_app
info "备份完成：$target（版本 $version）"
# 最后一行只输出备份目录，供 upgrade.sh 等调用方取用。
echo "$target"
