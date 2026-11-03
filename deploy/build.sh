#!/usr/bin/env bash
# 在宿主机上准备本版本的镜像（#138）：数据库镜像按摘要从镜像仓库拉（本机已有时不再下载）；app、web 镜像用发布包里
# 编译好的 app/rag-app.jar 和 web/dist 构建，JRE、Nginx 基础镜像同样按摘要锁定。install.sh、upgrade.sh、restore.sh 会调用它，
# 也可以单独运行。镜像仓库地址可在 .env 里用 RAG_DB_IMAGE、RAG_JRE_IMAGE、RAG_NGINX_IMAGE 改成镜像源或内网仓库。
# 用法：./build.sh
source "$(dirname "$0")/lib.sh"

version="$(package_version)"
[ -f app/rag-app.jar ] && [ -f web/dist/index.html ] \
  || die "没有找到 app/rag-app.jar 或 web/dist/index.html：请在完整解压的发布包目录里运行"

db_image="$(compose config --images | grep -vE '^rag-pilot-(app|web):' | head -n 1)"
if docker image inspect "$db_image" >/dev/null 2>&1; then
  info "数据库镜像已在本机：$db_image"
else
  info "拉取数据库镜像 $db_image"
  compose pull db
fi

info "构建版本 $version 的 app、web 镜像（第一次会拉 JRE、Nginx 基础镜像）"
compose build app web
info "镜像已就绪：rag-pilot-app:$version、rag-pilot-web:$version"
