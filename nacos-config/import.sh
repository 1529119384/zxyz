#!/bin/bash
set -euo pipefail
# ─────────────────────────────────────────────────────────────────────────────
# Nacos 配置导入（Nacos 3.x / group=ZXYZ）
#
# 用法:  ./import.sh [namespace-id] [nacos-admin-addr] [nacos-console-addr]
#   例:  ./import.sh "" 127.0.0.1:8848 127.0.0.1:18081
#
# ⚠ 本脚本必须在**服务器上**运行：Nacos 的 8848/18081 只绑定 127.0.0.1，外部不可达。
#   要在本机跑需先建隧道：
#     ssh -N -L 8848:127.0.0.1:8848 -L 18081:127.0.0.1:18081 root@<host>
#
# 鉴权双通道（按序尝试，任一可用即可；两条都在下面写了原因）：
#   1) accessToken  —— 用 NACOS_USERNAME/NACOS_PASSWORD 走 v3 登录（正统路径）
#       端点：POST http://<console>/v3/auth/user/login        ← 注意在 **console 端口**且**无 /nacos 前缀**
#       （Nacos 3.x 已下线 /nacos/v1/auth/login；8848 上的 /v3/auth/** 被安全过滤器统一 403）
#   2) server-identity —— 用 NACOS_AUTH_IDENTITY_KEY/VALUE 作为请求头（Nacos 白名单机制）
#       Nacos 用它放行"来自可信 server 的请求"，实测对 /v3/admin/cs/config 同样免 token。
#       兜底价值：当 Nacos 用户体系未初始化（users 表为空 ⇒ 永远登不进）时脚本仍可用。
#
# 凭据优先取环境变量，否则读 ../.env（本地开发 / 服务器部署目录均适用）。
# 幂等：Nacos publishConfig 为 upsert，重复执行安全。
# 自带验收：导入后逐份回读并比对 md5，不一致即失败退出 —— 用于消除"改完忘了 import / import 了没生效"。
# ─────────────────────────────────────────────────────────────────────────────
NAMESPACE=${1:-""}
NACOS=${2:-"127.0.0.1:8848"}
NACOS_CONSOLE=${3:-"127.0.0.1:18081"}

# 系统常只有 python3；原脚本硬编码 python 会在部分发行版直接失败
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
  echo "✗ 需要 python3（用于解析 JSON 与校验 YAML），未找到"
  exit 1
fi
# md5 工具：Linux 用 md5sum，macOS 用 md5
MD5=$(command -v md5sum || command -v md5 || true)
if [ -z "$MD5" ]; then
  echo "✗ 需要 md5sum 或 md5（用于导入后回读校验）"
  exit 1
fi
md5_of_file() {
  if [ "$(basename "$MD5")" = "md5" ]; then md5 -q "$1"; else md5sum "$1" | cut -d' ' -f1; fi
}

# 从 ../.env 读取凭据（本地开发；生产建议用环境变量注入）
load_env() {
  local key="$1"
  [ -f "../.env" ] && grep -E "^${key}=" "../.env" | head -1 | cut -d= -f2- | tr -d '"'"'"'\r' || true
}
NACOS_USER=${NACOS_USER:-$(load_env NACOS_USERNAME)}
NACOS_PASS=${NACOS_PASS:-$(load_env NACOS_PASSWORD)}
ID_KEY=${NACOS_AUTH_IDENTITY_KEY:-$(load_env NACOS_AUTH_IDENTITY_KEY)}
ID_VAL=${NACOS_AUTH_IDENTITY_VALUE:-$(load_env NACOS_AUTH_IDENTITY_VALUE)}

# --- 鉴权：先 token，失败再 identity ---
TOKEN=""
if [ -n "$NACOS_USER" ] && [ -n "$NACOS_PASS" ]; then
  echo "尝试 accessToken 登录: http://${NACOS_CONSOLE}/v3/auth/user/login"
  login_resp=$(curl -s --retry 2 --retry-delay 1 -X POST "http://${NACOS_CONSOLE}/v3/auth/user/login" \
    --data-urlencode "username=${NACOS_USER}" \
    --data-urlencode "password=${NACOS_PASS}" || true)
  TOKEN=$(printf '%s' "$login_resp" | "$PY" -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
fi

AUTH_ARGS=()
if [ -n "$TOKEN" ]; then
  echo "✓ 鉴权通道：accessToken（用户 ${NACOS_USER}）"
  AUTH_ARGS+=(--data-urlencode "accessToken=${TOKEN}")
elif [ -n "$ID_KEY" ] && [ -n "$ID_VAL" ]; then
  echo "⚠ 登录不可用，回退 server-identity 通道（请求头 ${ID_KEY}）"
  AUTH_ARGS+=(-H "${ID_KEY}: ${ID_VAL}")
else
  echo "✗ 鉴权失败：既无法用 NACOS_USERNAME/NACOS_PASSWORD 登录（用户体系可能未初始化），"
  echo "  也缺少 NACOS_AUTH_IDENTITY_KEY/NACOS_AUTH_IDENTITY_VALUE。"
  echo "  初始化管理员用户：./scripts/init-nacos-auth.sh"
  exit 1
fi

# --- 发布前自查：禁止 YAML 顶层重复 key（SnakeYAML 视重复键为致命错误，服务会起不来） ---
echo "检查 *.yml 顶层重复 key ..."
DUPLICATE=1
for file in *.yml; do
  [ -f "$file" ] || continue
  # 用 python 的 yaml 解析顶层映射，检测重复 key 并打印行号
  if ! "$PY" - "$file" <<'PYEOF'
import re, sys
path = sys.argv[1]
raw = open(path, encoding="utf-8").read()
strict = True
try:
    import yaml
    yaml.safe_load(raw)
except ImportError:
    strict = False          # 无 pyyaml 时退化为纯文本扫描，不阻断发布
except Exception as e:
    # 解析失败也算作阻断（结构不合法不应发布）
    print(f"PARSE-ERROR {path}: {e}")
    sys.exit(1)
seen = {}
for lineno, line in enumerate(raw.splitlines(), 1):
    # 仅匹配顶层 "key: value"（无缩进）
    m = re.match(r"^([A-Za-z0-9_.-]+):", line)
    if m:
        k = m.group(1)
        if k in seen:
            print(f"DUPLICATE {path}: 顶层重复 key '{k}'（行 {seen[k]} 和本行）")
            sys.exit(1)
        seen[k] = lineno
if not strict:
    print(f"NOTE {path}: 未安装 pyyaml，仅做文本层面重复 key 扫描")
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
# 于是「有人顺手写了个字面量口令」没有任何拦截，会随配置一起进 Nacos 库。
# 本检查不禁止 ${ENV} 引用（那是当前刻意采用的方案），只拦字面量：
# 值必须以 "${" 开头（env 引用）或 "ENC(" 开头（Jasypt 密文），否则视为明文机密并阻断发布。
echo "检查 *.yml 是否含字面量机密 ..."
PLAINTEXT=1
for file in *.yml; do
  [ -f "$file" ] || continue
  if ! "$PY" - "$file" <<'PYEOF'
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

# --- 导入 + 回读验收 ---
success=0; fail=0; mismatch=0
for file in *.yml; do
  [ -f "$file" ] || continue
  dataId=$(basename "$file")
  echo "导入 ${dataId} → namespace='${NAMESPACE}' group=ZXYZ ..."
  # Nacos 3.x Admin API：POST /nacos/v3/admin/cs/config（form-urlencoded）
  #   注意：v3 参数名是 groupName（传 group 会 400）；namespaceId 空 = public namespace
  resp=$(curl -s --retry 3 --retry-delay 1 -w "\n%{http_code}" -X POST "http://${NACOS}/nacos/v3/admin/cs/config" \
    "${AUTH_ARGS[@]}" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "groupName=ZXYZ" \
    --data-urlencode "content@${file}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "namespaceId=${NAMESPACE}")
  http_code=$(printf '%s' "$resp" | tail -1)
  body=$(printf '%s' "$resp" | sed '$d')
  if [ "$http_code" != "200" ]; then
    echo "  ✗ 写入失败 (HTTP ${http_code}) $(printf '%s' "$body" | head -c 200)"
    fail=$((fail + 1)); continue
  fi

  # 回读校验：Nacos 存的 md5 应等于本地文件字节的 md5（导入内容必须与仓库逐字节等价）
  # ⚠ 实测：发布成功后 Nacos 的「客户端可读视图」有短暂滞后——紧邻写入即读会拿到 20004，
  #   稍后读必到。故此处退避重试，避免把"最终一致"误报成"导入失败"。
  remote_md5=""
  for _try in 1 2 3 4 5 6 7 8; do
    readback=$(curl -s --retry 2 "http://${NACOS}/nacos/v3/client/cs/config?dataId=${dataId}&groupName=ZXYZ&namespaceId=${NAMESPACE}")
    remote_md5=$(printf '%s' "$readback" | "$PY" -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('md5',''))" 2>/dev/null || true)
    [ -n "$remote_md5" ] && break
    sleep 1
  done
  local_md5=$(md5_of_file "$file")
  if [ -n "$remote_md5" ] && [ "$remote_md5" = "$local_md5" ]; then
    echo "  ✓ 成功（md5 ${local_md5:0:12}… 回读一致）"; success=$((success + 1))
  elif [ -z "$remote_md5" ]; then
    echo "  ✗ 写入返回 200 但回读不到（namespace/group 口径不一致？）"; fail=$((fail + 1))
  else
    echo "  ✗ 回读 md5 不一致：本地 ${local_md5:0:12}… vs 远端 ${remote_md5:0:12}…"; mismatch=$((mismatch + 1))
  fi
done
echo "导入完成: ${success} 成功, ${fail} 失败, ${mismatch} 内容不一致"
[ $((fail + mismatch)) -eq 0 ] || exit 1
echo "✓ 全部配置已入库且回读校验一致"
