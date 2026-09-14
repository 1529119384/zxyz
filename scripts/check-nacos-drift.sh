#!/bin/bash
set -euo pipefail
# ─────────────────────────────────────────────────────────────────────────────
# Nacos 配置对账（**只读**）：比对「线上 Nacos」与「仓库 nacos-config/**」的逐份 md5
#
# 与 import.sh 的区别：
#   import.sh —— 写入 + 回读校验（有副作用，会改线上）
#   本脚本    —— **只读对账**，不修改任何东西；用于日常巡检与排障
#
# 为什么需要（审计 11 §7.6）：
#   Nacos 已是配置真源，但「线上到底是不是仓库这一版」此前没有独立手段验证。
#   CI 的 nacos-import 只在 `nacos-config/**` 变更时运行；若有人**手工改了控制台**
#   （仓库契约明确禁止，但流程无法真正阻止），要等下一次导入才会被覆盖 ——
#   中间这段时间线上跑的是「没人知道是什么」的配置。本脚本就是用来发现这种漂移。
#
# ⚠ 必须在**服务器上**运行：Nacos 的 8848 / 18081 只绑定 127.0.0.1，外部不可达。
#   要在本机跑需先建隧道：
#     ssh -N -L 8848:127.0.0.1:8848 -L 18081:127.0.0.1:18081 root@<host>
#
# 覆盖范围：仓库 → 远端的**逐份一致性**（OK / DRIFT / MISS）。
#   「远端有、仓库没有」的配置不在本脚本范围内 —— 那由 CI 的
#   `scripts/check-nacos-config-sync.py`（[EXTRA] / [UNCONSUMED]）在仓库侧把关。
#
# 用法:  bash scripts/check-nacos-drift.sh [namespace] [nacos-admin-addr] [nacos-console-addr]
#   例:  bash scripts/check-nacos-drift.sh "" 127.0.0.1:8848 127.0.0.1:18081
#
# 退出码：0 = 全部一致；1 = 存在漂移或远端缺失；2 = 环境/鉴权问题
# ─────────────────────────────────────────────────────────────────────────────
NAMESPACE=${1:-""}
NACOS=${2:-"127.0.0.1:8848"}
NACOS_CONSOLE=${3:-"127.0.0.1:18081"}
GROUP=ZXYZ

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="${SCRIPT_DIR}/../nacos-config"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "✗ 找不到配置目录：${CONFIG_DIR}"
  exit 2
fi

PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
  echo "✗ 需要 python3（用于解析 JSON），未找到"
  exit 2
fi
MD5=$(command -v md5sum || command -v md5 || true)
if [ -z "$MD5" ]; then
  echo "✗ 需要 md5sum 或 md5"
  exit 2
fi
md5_of_file() {
  if [ "$(basename "$MD5")" = "md5" ]; then md5 -q "$1"; else md5sum "$1" | cut -d' ' -f1; fi
}

# 凭据：优先环境变量；否则依次尝试 仓库根的 .env、服务器部署目录的 .env、当前目录的 .env
load_env() {
  local key="$1"
  local f v
  for f in "${SCRIPT_DIR}/../.env" "/www/zxyz/.env" "${PWD}/.env"; do
    [ -f "$f" ] || continue
    v=$(grep -E "^${key}=" "$f" | head -1 | cut -d= -f2- | tr -d '"'"'"'\r' || true)
    if [ -n "${v:-}" ]; then printf '%s' "$v"; return 0; fi
  done
  return 0
}
NACOS_USER=${NACOS_USER:-$(load_env NACOS_USERNAME)}
NACOS_PASS=${NACOS_PASS:-$(load_env NACOS_PASSWORD)}
ID_KEY=${NACOS_AUTH_IDENTITY_KEY:-$(load_env NACOS_AUTH_IDENTITY_KEY)}
ID_VAL=${NACOS_AUTH_IDENTITY_VALUE:-$(load_env NACOS_AUTH_IDENTITY_VALUE)}

# --- 鉴权：先 accessToken，失败再 server-identity（与 import.sh 完全同口径）---
TOKEN=""
if [ -n "$NACOS_USER" ] && [ -n "$NACOS_PASS" ]; then
  echo "尝试 accessToken 登录: http://${NACOS_CONSOLE}/v3/auth/user/login"
  login_resp=$(curl -s --retry 2 --retry-delay 1 -X POST "http://${NACOS_CONSOLE}/v3/auth/user/login" \
    --data-urlencode "username=${NACOS_USER}" \
    --data-urlencode "password=${NACOS_PASS}" || true)
  TOKEN=$(printf '%s' "$login_resp" | "$PY" -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
fi

if [ -n "$TOKEN" ]; then
  echo "✓ 鉴权通道：accessToken（用户 ${NACOS_USER}）"
elif [ -n "$ID_KEY" ] && [ -n "$ID_VAL" ]; then
  echo "⚠ 登录不可用，回退 server-identity 通道（请求头 ${ID_KEY}）"
else
  echo "✗ 鉴权失败：既无法用 NACOS_USERNAME/NACOS_PASSWORD 登录，"
  echo "  也缺少 NACOS_AUTH_IDENTITY_KEY/NACOS_AUTH_IDENTITY_VALUE。"
  echo "  初始化管理员用户：bash scripts/init-nacos-auth.sh"
  exit 2
fi

echo "对账：namespace='${NAMESPACE}' group=${GROUP} 远端=${NACOS}"
echo "      本地目录=${CONFIG_DIR}"
echo "──────────────────────────────────────────────────────────"

cd "$CONFIG_DIR"
ok=0
drift=0
miss=0
drift_names=""

for file in *.yml; do
  [ -f "$file" ] || continue
  dataId="$file"
  local_md5=$(md5_of_file "$file")

  # 读远端：注意 v3 的「客户端读」端点无需鉴权；参数名是 groupName（传 group 会 400）
  remote_md5=""
  for _try in 1 2 3; do
    readback=$(curl -s --retry 2 \
      "http://${NACOS}/nacos/v3/client/cs/config?dataId=${dataId}&groupName=${GROUP}&namespaceId=${NAMESPACE}" || true)
    remote_md5=$(printf '%s' "$readback" \
      | "$PY" -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('md5',''))" 2>/dev/null || true)
    if [ -n "$remote_md5" ]; then break; fi
    sleep 1
  done

  if [ -z "$remote_md5" ]; then
    echo "[MISS ] ${dataId}  ← 远端读不到（从未导入？或 namespace/group 口径不一致）"
    miss=$((miss + 1))
    drift_names="${drift_names}${dataId}(MISS) "
  elif [ "$remote_md5" = "$local_md5" ]; then
    echo "[ OK  ] ${dataId}  md5=${local_md5:0:12}…"
    ok=$((ok + 1))
  else
    echo "[DRIFT] ${dataId}  本地 ${local_md5:0:12}… ≠ 远端 ${remote_md5:0:12}…"
    drift=$((drift + 1))
    drift_names="${drift_names}${dataId}(DRIFT) "
  fi
done

echo "──────────────────────────────────────────────────────────"
total=$((ok + drift + miss))
echo "对账结果：一致 ${ok} ｜ 漂移 ${drift} ｜ 远端缺失 ${miss}（共 ${total} 份）"

if [ $((drift + miss)) -ne 0 ]; then
  echo ""
  echo "⚠ 漂移清单：${drift_names}"
  echo ""
  echo "修复方式（把仓库版本重新推给 Nacos，脚本幂等）："
  echo "    cd ${CONFIG_DIR} && bash ./import.sh \"${NAMESPACE}\" ${NACOS} ${NACOS_CONSOLE}"
  echo "  若怀疑是有人手工改了控制台，请先用控制台/接口 diff 出具体差异，再决定以哪边为准。"
  exit 1
fi

echo "✓ 线上 Nacos 与仓库逐字节一致。"
