#!/usr/bin/env bash
# 升级到本部署目录的版本（D-048、#138）。在新版本发布包解压后的目录里运行，先把旧目录的 .env 复制过来。
# 步骤：在宿主机上构建新版本的镜像（旧版本照常服务）→ 检查磁盘 → 停服务备份 → 换新镜像启动（Flyway 自动迁移数据库）
# → 等健康检查通过 → 跑一遍 verify.sh。
# 迁移不可逆：失败时停掉新版本，按提示在旧版本目录里用这次的备份恢复（会丢升级后写入的数据，这里刚升级，通常没有）。
# 用法：./upgrade.sh [--yes]
source "$(dirname "$0")/lib.sh"

version="$(package_version)"
current="$(running_version)"
[ -n "$current" ] || die "没有检测到正在运行的版本（$APP_URL/actuator/info），全新安装请按 README.md 的“安装”"
[ "$current" != "$version" ] || die "正在运行的已经是版本 $version"
# 从带 Redis 的版本起（D-053）.env 要多一项：从旧版本复制来的 .env 里没有时先补上。
[ -n "$(setting REDIS_PASSWORD)" ] && [ "$(setting REDIS_PASSWORD)" != "change-me" ] \
  || die "请在 .env 里加上 REDIS_PASSWORD（新版本用 Redis 存缓存和登录会话，见 .env.example），再运行升级"
info "构建版本 $version 的镜像，构建期间旧版本照常服务"
"$DEPLOY_DIR/build.sh"

root="$(backup_root)"
free_kb="$(df -Pk "$root" | awk 'NR == 2 { print $4 }')"
[ "$free_kb" -ge $((2 * 1024 * 1024)) ] || die "备份目录 $root 剩余空间不足 2 GB"

if [ "${1:-}" != "--yes" ]; then
  read -r -p "将从版本 $current 升级到 ${version}，期间停服务几分钟，确定吗？输入 yes 继续：" answer
  [ "$answer" = "yes" ] || die "已取消"
fi

backup="$root/$(date +%Y%m%d-%H%M%S)-before-$version"
info "升级前备份到 $backup"
"$DEPLOY_DIR/backup.sh" "$backup" >/dev/null

info "启动版本 ${version}（数据库迁移在启动时自动执行）"
compose up -d
if ! wait_healthy 300; then
  compose stop app
  cat >&2 <<EOF

升级失败：版本 $version 没有在 5 分钟内启动，已停止。原因见 docker compose logs app（常见是数据库迁移失败）。
数据库可能已经部分迁移，请回退到版本 ${current}：
  cd <版本 $current 的部署目录>
  ./restore.sh $backup
EOF
  exit 1
fi

running="$(running_version)"
[ "$running" = "$version" ] || die "启动后运行的版本是 ${running}，不是 ${version}：镜像可能不对，请在本目录重新运行 ./build.sh 后再升级"
info "升级完成：$current → ${version}。升级前的备份在 $backup"
"$DEPLOY_DIR/verify.sh"
