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

# --- 发布前自查：禁止字面量机密（审计 2.3.5） ---
# 背景：zxyz-static.yml 等文件头部注释声称敏感值走 Jasypt ENC()，实际全是明文 ${ENV} 透传。
# 于是「有人顺手写了个字面量口令」没有任何拦截，会随配置一起进 Nacos 库（结合 P0-1 的库直连脚本即一锅端）。
# 本检查不禁止 ${ENV} 引用（那是当前刻意采用的方案），只拦字面量：
# 值必须以 "${" 开头（env 引用）或 "ENC(" 开头（Jasypt 密文），否则视为明文机密并阻断发布。
echo "检查 *.yml 是否含字面量机密 ..."
PLAINTEXT=1
for file in *.yml; do
  [ -f "$file" ] || continue
  if ! python - "$file" <<'PYEOF'
import re, sys
path = sys.argv[1]
# 键名必须以 password/passwd/secret/token 结尾（避免误伤 password-min-length 这类普通配置）
pat = re.compile(r'^\s*[\w.-]*(?:password|passwd|secret|token)\s*:\s*(\S.*)$', re.I)
with open(path, encoding="utf-8") as f:
    for lineno, line in enumerate(f, 1):
        m = pat.match(line)
        if not m:
            continue
        val = m.group(1).strip().strip('"').strip("'")
        if not val or val.startswith("${") or val.startswith("ENC("):
            continue
        print(f"PLAINTEXT-SECRET {path}:{lineno}: {line.strip()[:100]}")
        sys.exit(1)
PYEOF
  then
    echo "  ✗ ${file} 含字面量机密（应改为 \${ENV} 引用或 ENC(...) 密文），已中止发布"
    PLAINTEXT=0
  fi
done
[ "$PLAINTEXT" -eq 1 ] || exit 1
echo "✓ 无机密字面量"

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
