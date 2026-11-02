#!/usr/bin/env bash
# 部署验证（D-048）：安装、升级、恢复之后跑一遍，确认系统真的可用。CI 和客户现场用同一个脚本。
# 检查：健康检查和版本 → 经 web 打开页面、接口可达 → 超级管理员登录 → 知识库“部署验证”里有一份文档且能检索到、
# 问答接口能用 → 没有授权的账号检索不到这个库、问答不引用它。第一次运行时建库、上传文档、建测试账号 deploy-verify，
# 之后复用（升级、恢复后检查数据还在）；这些可以在管理端删除。
# 用法：./verify.sh            超级管理员密码取 .env 的 RAG_ADMIN_PASSWORD；改过密码时设环境变量 VERIFY_ADMIN_PASSWORD。
source "$(dirname "$0")/lib.sh"

WEB_URL="http://127.0.0.1:$(setting WEB_PORT 80)"
ADMIN="$(setting RAG_ADMIN_USERNAME admin)"
PASSWORD="${VERIFY_ADMIN_PASSWORD:-$(setting RAG_ADMIN_PASSWORD)}"
KB_NAME="部署验证"
STAFF="deploy-verify"
failures=0

ok() { echo "  通过：$*"; }
fail() { echo "  失败：$*" >&2; failures=$((failures + 1)); }

# 请求接口：api <方法> <路径> [JSON 请求体] [令牌]。响应体放在 $RESPONSE，状态码放在 $STATUS
# （不在子 shell 里执行，调用方才能读到这两个变量）。
api() {
  local method="$1" path="$2" body="${3:-}" token="${4-${TOKEN:-}}" args=() output
  args=(-sS --max-time 60 -X "$method" -w '\n%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" --data "$body")
  output="$(curl "${args[@]}" "$WEB_URL$path")" || output=$'\n000'
  STATUS="$(tail -n 1 <<<"$output")"
  RESPONSE="$(sed '$d' <<<"$output")"
}

# 从 JSON 里取第一个 "key":值（只用于脚本里的简单字段）。
field() {
  { grep -o "\"$1\":\"\\{0,1\\}[^,\"}]*" || true; } | head -n 1 | sed "s/\"$1\":\"\\{0,1\\}//"
}

# 取第一个匹配的片段里的数字 id；没有匹配时为空。
id_of() {
  { grep -o "$1" || true; } | head -n 1 | { grep -o '[0-9][0-9]*' || true; } | head -n 1
}

# 登录，令牌放在 $LOGIN_TOKEN（失败时为空）。
login() {
  api POST /api/auth/login "{\"username\":\"$1\",\"password\":\"$2\"}" ""
  LOGIN_TOKEN="$(field token <<<"$RESPONSE")"
}

echo "部署验证：$WEB_URL"

# 1. 健康检查和版本（只在本机的 8080 端口）。
wait_healthy 120 && ok "健康检查" || fail "健康检查没有通过（$APP_URL/actuator/health）"
version="$(running_version)"
[ -n "$version" ] && ok "运行版本 $version" || fail "读不到版本号（$APP_URL/actuator/info）"

# 2. 经 web 打开页面和接口。
curl -fsS --max-time 10 "$WEB_URL/" | grep -q '<div id="root">' && ok "页面可以打开" || fail "页面打不开"
api GET /api/auth/branding "" ""
[ "$STATUS" = 200 ] && ok "web 能转发接口" || fail "经 web 访问接口返回 $STATUS"

# 3. 超级管理员登录。
[ -n "$PASSWORD" ] || die "没有超级管理员密码：在 .env 里设置 RAG_ADMIN_PASSWORD，或设环境变量 VERIFY_ADMIN_PASSWORD"
login "$ADMIN" "$PASSWORD"
TOKEN="$LOGIN_TOKEN"
[ -n "$TOKEN" ] && ok "超级管理员 $ADMIN 登录" || { fail "超级管理员登录失败"; exit 1; }

# 4. 知识库、文档、检索、问答。
# 关键词“部署验证”的 URL 编码。
api GET "/api/knowledge-bases/page?keyword=%E9%83%A8%E7%BD%B2%E9%AA%8C%E8%AF%81"
kb="$(id_of "\"id\":[0-9]*,\"name\":\"$KB_NAME\"" <<<"$RESPONSE")"
if [ -z "$kb" ]; then
  api POST /api/knowledge-bases "{\"name\":\"$KB_NAME\"}"
  [ "$STATUS" = 201 ] || { fail "建知识库返回 $STATUS：$RESPONSE"; exit 1; }
  kb="$(field id <<<"$RESPONSE")"
  file="$(mktemp)"
  printf '部署验证资料：冷柜每周一闭店后除霜，除霜时把物品移到备用冷柜。\n' > "$file"
  curl -sS --max-time 60 -H "Authorization: Bearer $TOKEN" -F "file=@$file;filename=deploy-verify.txt" \
    "$WEB_URL/api/knowledge-bases/$kb/documents" >/dev/null
  rm -f "$file"
  ok "新建知识库“$KB_NAME”并上传文档"
else
  ok "知识库“$KB_NAME”已存在（id $kb）"
fi
for _ in $(seq 1 60); do
  api GET "/api/knowledge-bases/$kb/documents"
  documents="$RESPONSE"
  grep -q '"status":"PARSING"' <<<"$documents" || break
  sleep 2
done
grep -q '"filename":"deploy-verify.txt"' <<<"$documents" && grep -q '"status":"READY"' <<<"$documents" \
  && ok "文档已入库" || fail "文档没有入库：$documents"
api GET "/api/knowledge-bases/$kb/search?q=%E5%86%B7%E6%9F%9C%E9%99%A4%E9%9C%9C"
grep -q 'deploy-verify.txt' <<<"$RESPONSE" && ok "检索能找到文档" || fail "检索没有找到文档：$RESPONSE"
api POST /api/ask "{\"question\":\"冷柜什么时候除霜\",\"knowledgeBaseIds\":[$kb]}"
[ "$STATUS" = 200 ] && ok "问答接口可用" || fail "问答返回 $STATUS"

# 5. 没有授权的账号（员工角色）选不到这个库，点名它提问也不引用它。每次把测试账号的密码重置后改成新的随机密码。
api GET /api/roles
staff_role="$(id_of '"id":[0-9]*,"name":"[^"]*","builtIn":"STAFF"' <<<"$RESPONSE")"
temp="verify-$(date +%s)-tmp"
api GET "/api/users?keyword=$STAFF"
staff_id="$(id_of "\"id\":[0-9]*,\"username\":\"$STAFF\"" <<<"$RESPONSE")"
if [ -z "$staff_id" ]; then
  api POST /api/users "{\"username\":\"$STAFF\",\"displayName\":\"部署验证\",\"password\":\"$temp\",\"roleIds\":[$staff_role]}"
  staff_id="$(field id <<<"$RESPONSE")"
fi
api POST "/api/users/$staff_id/reset-password" "{\"password\":\"$temp\"}"
staff_password="verify-$(date +%s)-$RANDOM"
login "$STAFF" "$temp"
api POST /api/auth/password "{\"oldPassword\":\"$temp\",\"newPassword\":\"$staff_password\"}" "$LOGIN_TOKEN"
login "$STAFF" "$staff_password"
staff_token="$LOGIN_TOKEN"
[ -n "$staff_token" ] || { fail "测试账号 $STAFF 登录失败"; exit 1; }
api GET /api/knowledge-bases "" "$staff_token"
grep -q "\"id\":$kb," <<<"$RESPONSE" && fail "未授权的账号能选到“$KB_NAME”" || ok "未授权的账号选不到“$KB_NAME”"
api POST /api/ask "{\"question\":\"冷柜什么时候除霜\",\"knowledgeBaseIds\":[$kb]}" "$staff_token"
grep -q 'deploy-verify.txt' <<<"$RESPONSE" && fail "未授权的账号的回答引用了“$KB_NAME”的文档" \
  || ok "未授权的账号的回答没有引用“$KB_NAME”"

if [ "$failures" -gt 0 ]; then
  echo "部署验证没有通过：$failures 项失败" >&2
  exit 1
fi
echo "部署验证通过（版本 $version）"
