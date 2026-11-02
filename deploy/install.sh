#!/usr/bin/env bash
# 全新安装（D-048）：加载镜像（离线包）→ 启动 → 等健康检查通过 → 跑一遍 verify.sh。
# 先复制 .env.example 为 .env 并填写（至少 DB_PASSWORD、MODEL_API_KEY、RAG_ADMIN_PASSWORD、RAG_MASTER_KEY）。
# 用法：./install.sh
source "$(dirname "$0")/lib.sh"

[ -n "$(setting DB_PASSWORD)" ] && [ "$(setting DB_PASSWORD)" != "change-me" ] || die "请在 .env 里设置 DB_PASSWORD"
[ -n "$(setting RAG_ADMIN_PASSWORD)" ] || die "请在 .env 里设置 RAG_ADMIN_PASSWORD（第一次启动时创建超级管理员）"
[ -z "$(running_version)" ] || die "已经有版本 $(running_version) 在运行；升级请用 ./upgrade.sh"

version="$(package_version)"
if [ -f release.env ] && ! docker image inspect "rag-pilot-app:$version" >/dev/null 2>&1; then
  "$DEPLOY_DIR/load.sh"
fi

info "启动版本 $version"
compose up -d
wait_healthy 300 || die "app 没有在 5 分钟内启动，请查看 docker compose logs app"
info "安装完成"
"$DEPLOY_DIR/verify.sh"
