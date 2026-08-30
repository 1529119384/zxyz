#!/bin/bash
set -euo pipefail
# 用法: ./import.sh <namespace-id> [nacos-addr]
# 凭据优先取环境变量 NACOS_USER/NACOS_PASS，否则从 ../.env 读取（本地开发）。
# Nacos 3.x：使用 /v3/admin/cs/config 发布配置，需先登录获取 accessToken。
NAMESPACE=${1:-""}
NACOS=${2:-"localhost:18048"}

# 从 ../.env 读取 Nacos 凭据（本地开发；生产请用环境变量注入）
load_env() {
  local key="$1"
  [ -f "../.env" ] && grep -E "^${key}=" "../.env" | head -1 | cut -d= -f2- | tr -d '\r' || true
}
NACOS_USER=${NACOS_USER:-$(load_env NACOS_USERNAME)}
NACOS_PASS=${NACOS_PASS:-$(load_env NACOS_PASSWORD)}

if [ -z "$NACOS_USER" ] || [ -z "$NACOS_PASS" ]; then
  echo "✗ 未找到 Nacos 凭据（环境变量 NACOS_USER/NACOS_PASS 或 ../.env 的 NACOS_USERNAME/NACOS_PASSWORD）"
  exit 1
fi

echo "检查 Nacos 连接: http://${NACOS}/nacos/v1/auth/login"
login_resp=$(curl -s --retry 3 --retry-delay 2 -X POST "http://${NACOS}/nacos/v1/auth/login" \
  --data-urlencode "username=${NACOS_USER}" \
  --data-urlencode "password=${NACOS_PASS}")
TOKEN=$(echo "$login_resp" | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
if [ -z "$TOKEN" ]; then
  echo "✗ 登录失败，请检查地址和鉴权配置"
  echo "$login_resp" | head -c 300
  exit 1
fi
echo "✓ 登录成功"

# --- 发布前自查：禁止 YAML 顶层重复 key（SnakeYAML 会静默丢弃第一个，属配置地雷） ---
echo "检查 *.yml 顶层重复 key ..."
DUPLICATE=1
for file in *.yml; do
  [ -f "$file" ] || continue
  # 用 python 的 yaml 解析顶层映射，检测重复 key 并打印行号
  if ! python - "$file" <<'PYEOF'
import sys, yaml
path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    data = yaml.safe_load(raw)
except Exception as e:
    # 解析失败也算作阻断（结构不合法不应发布）
    print(f"PARSE-ERROR {path}: {e}")
    sys.exit(1)
seen = {}
for line in raw.splitlines():
    # 仅匹配顶层 "key: value"（无缩进）
    import re
    m = re.match(r"^([A-Za-z0-9_.-]+):", line)
    if m:
        k = m.group(1)
        if k in seen:
            print(f"DUPLICATE {path}: 顶层重复 key '{k}'（行 {seen[k]} 和本行）")
            sys.exit(1)
        seen[k] = line
PYEOF
  then
    echo "  ✗ ${file} 含重复顶层 key，已中止发布"
    DUPLICATE=0
  fi
done
[ "$DUPLICATE" -eq 1 ] || exit 1
echo "✓ 无重复顶层 key"

success=0; fail=0
for file in *.yml; do
  [ -f "$file" ] || continue
  dataId=$(basename "$file")
  echo "导入 ${dataId} → namespace=${NAMESPACE} ..."
  # Nacos 3.x Admin API：POST /nacos/v3/admin/cs/config（form-urlencoded + accessToken）
  resp=$(curl -s --retry 3 --retry-delay 1 -w "\n%{http_code}" -X POST "http://${NACOS}/nacos/v3/admin/cs/config" \
    --data-urlencode "accessToken=${TOKEN}" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "groupName=ZXYZ" \
    --data-urlencode "content@${file}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "namespaceId=${NAMESPACE}")
  http_code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  if [ "$http_code" = "200" ]; then
    echo "  ✓ 成功"; success=$((success + 1))
  else
    echo "  ✗ 失败 (HTTP ${http_code}) ${body}" | head -c 200; echo ""; fail=$((fail + 1))
  fi
done
echo "导入完成: ${success} 成功, ${fail} 失败"
[ "$fail" -eq 0 ] || exit 1
