#!/usr/bin/env bash
# 从备份恢复（D-048）：会覆盖当前数据库和上传文件。也用于升级后回退：在旧版本的部署目录里，用升级前的备份恢复。
# 用法：./restore.sh <备份目录> [--yes]
# 备份的版本必须与本部署目录的版本一致，避免新版本的数据库结构配上旧程序。
source "$(dirname "$0")/lib.sh"

source_dir="${1:-}"
[ -n "$source_dir" ] && [ -d "$source_dir" ] || die "用法：./restore.sh <备份目录> [--yes]"
source_dir="$(cd "$source_dir" && pwd)"
for file in db.dump files.tar.gz VERSION SHA256SUMS; do
  [ -f "$source_dir/$file" ] || die "备份不完整，缺少 $file"
done
(cd "$source_dir" && checksum -c SHA256SUMS >/dev/null) || die "备份文件校验失败，可能已损坏"

backup_version="$(cat "$source_dir/VERSION")"
version="$(package_version)"
if [ "$backup_version" != "$version" ]; then
  die "这份备份是版本 $backup_version 的，本目录是版本 $version。请在版本 $backup_version 的部署目录里恢复"
fi

if [ "${2:-}" != "--yes" ]; then
  read -r -p "将用 $source_dir 覆盖当前的数据库和上传文件，确定吗？输入 yes 继续：" answer
  [ "$answer" = "yes" ] || die "已取消"
fi

# 回退时旧版本的镜像可能已被清理，先确认本版本的镜像都在（已有时很快，不会重新下载）。
"$DEPLOY_DIR/build.sh"

info "停止 app（页面显示“系统维护中”）"
compose stop app 2>/dev/null || true
compose up -d db
wait_db

info "恢复数据库"
# 用空白的 template0 建库：ParadeDB 在 template1 里预先建了 paradedb 等对象，会与备份里的重复。
compose exec -T db psql -U rag -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS rag WITH (FORCE)" -c "CREATE DATABASE rag OWNER rag TEMPLATE template0" >/dev/null
compose exec -T db pg_restore -U rag -d rag --no-owner --exit-on-error < "$source_dir/db.dump"

info "恢复上传文件"
ensure_redis
compose run --rm --no-deps --user 0 -v "$source_dir:/backup:ro" --entrypoint sh app -c \
  'find /data/files -mindepth 1 -delete && tar -xzf /backup/files.tar.gz -C /data/files && chown -R 10001:10001 /data/files'

info "启动"
compose up -d
wait_healthy || die "app 没有在 5 分钟内启动，请查看 docker compose logs app"
info "恢复完成：版本 $version，数据来自 $source_dir"
