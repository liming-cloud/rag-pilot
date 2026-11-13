#!/usr/bin/env bash
# 解析核对（#134）与资料体检（#218）：资料导入后，按文档列出分块数、图片数、表格行数、标题层级、乱码和空白比例与体检结论，人工抽查解析结果。
# 只读（数据库连接设为只读），不改任何数据，不需要停服务。输出含文档名，只留在客户环境里。
# 用法：./parse-check.sh          打印表格
#       ./parse-check.sh --csv    输出 CSV，方便用 Excel 打开记录结论
source "$(dirname "$0")/lib.sh"

format=""
case "${1:-}" in
  "") ;;
  --csv) format="--csv" ;;
  *) die "用法：./parse-check.sh [--csv]" ;;
esac

wait_db
compose exec -T -e PGOPTIONS="-c default_transaction_read_only=on" db \
  psql -U rag -d rag -X -v ON_ERROR_STOP=1 $format < parse-check.sql
