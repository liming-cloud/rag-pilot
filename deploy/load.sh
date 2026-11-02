#!/usr/bin/env bash
# 加载离线包里的镜像（D-048）：app、web 和数据库三个镜像放在 images/ 下，不需要访问外网。
# 用法：./load.sh
set -euo pipefail
cd "$(dirname "$0")"

shopt -s nullglob
files=(images/*.image.tar.gz)
[ "${#files[@]}" -gt 0 ] || { echo "错误：images/ 下没有镜像文件，把 Release 上的三个 *.image.tar.gz 放进 images/ 再运行" >&2; exit 1; }
for file in "${files[@]}"; do
  echo "==> 加载 $file"
  gunzip -c "$file" | docker load
done
echo "==> 镜像已加载，版本 $(grep '^RAG_VERSION=' release.env | cut -d= -f2)"
