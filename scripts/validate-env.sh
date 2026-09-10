#!/bin/bash
# =============================================================================
# ZXYZ .env 配置验证脚本
# 检查所有必需变量是否已配置（非占位符），避免运行时连接失败。
#
# 用法:
#   ./scripts/validate-env.sh              # 验证当前目录 .env
#   ./scripts/validate-env.sh /path/.env   # 验证指定 .env 文件
# =============================================================================

set -euo pipefail

ENV_FILE="${1:-.env}"
ERRORS=0
WARNINGS=0
SYNC_ONLY=false

# 解析 --sync-only 参数
for arg in "$@"; do
  if [ "$arg" = "--sync-only" ]; then
    SYNC_ONLY=true
  fi
done

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE 不存在"
  echo "请从 .env.example 复制: cp .env.example .env"
  exit 1
fi

# 加载 .env
set -a
source "$ENV_FILE"
set +a

echo "===== 验证 $ENV_FILE ====="
echo ""

# --- Step 1: 从 .env.example 补全缺失的 KEY ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_FILE="$SCRIPT_DIR/../.env.example"
if [ -f "$EXAMPLE_FILE" ]; then
  echo "--- Step 1: 补全缺失的配置项 ---"
  sync_count=0
  while IFS= read -r line; do
    # 跳过注释和空行
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line// }" ]] && continue
    # 匹配 KEY= 格式（值可能为空）
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      # 仅当 .env 中不存在该 KEY 时追加
      if ! grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
        echo "  补全: $key"
        echo "$line" >> "$ENV_FILE"
        sync_count=$((sync_count + 1))
      fi
    fi
  done < "$EXAMPLE_FILE"
  if [ "$sync_count" -gt 0 ]; then
    echo "  已补全 $sync_count 个缺失的配置项到 $ENV_FILE"
    # 重新加载更新后的 .env
    set -a
    source "$ENV_FILE"
    set +a
  else
    echo "  所有配置项已存在，无需补全"
  fi
  echo ""

  # --sync-only 模式：仅补全，跳过后续校验
  if [ "$SYNC_ONLY" = true ]; then
    echo "===== sync-only 模式：仅补全完成 ====="
    exit 0
  fi
else
  echo "WARN: .env.example 不存在，跳过 Step 1"
  echo ""
fi

# --- 必须修改的占位符 ---
check_not_placeholder() {
  local var_name="$1"
  local value="${!var_name:-}"
  local pattern="${2:-^CHANGE_ME}"

  if [ -z "$value" ]; then
    echo "  ERROR: $var_name 未设置"
    ERRORS=$((ERRORS + 1))
  elif echo "$value" | grep -qE "$pattern"; then
    echo "  ERROR: $var_name 仍是占位符: $value"
    ERRORS=$((ERRORS + 1))
  else
    echo "  OK: $var_name"
  fi
}

# --- 必须有值（允许任意值） ---
check_required() {
  local var_name="$1"
  local value="${!var_name:-}"

  if [ -z "$value" ]; then
    echo "  ERROR: $var_name 未设置"
    ERRORS=$((ERRORS + 1))
  else
    echo "  OK: $var_name"
  fi
}

echo "--- 数据库 ---"
check_not_placeholder "MYSQL_ROOT_PASSWORD"

echo ""
echo "--- 数据库最小权限专用账户（可选；未启用则回退 root，不校验） ---"
# U1 配套：仅当某库显式配置了专用账户用户名（非 root、非空）时，才要求对应的
# *_DB_PASSWORD 是真实值；否则视为沿用 root，跳过校验，避免「未启用专用账户」被误判为错误。
check_db_account() {
  local prefix="$1"
  local user_var="${prefix}_DB_USERNAME"
  local pass_var="${prefix}_DB_PASSWORD"
  local user_val="${!user_var:-}"
  # 未配置用户名，或显式回退 root → 不校验密码（沿用 root）
  if [ -z "$user_val" ] || [ "$user_val" = "root" ]; then
    echo "  SKIP: ${prefix} 未启用专用账户（回退 root），跳过 ${pass_var} 校验"
    return 0
  fi
  check_not_placeholder "$pass_var"
}
check_db_account PROJECT
check_db_account IM
check_db_account EMAIL
check_db_account SHARE
check_db_account FILE
check_db_account TEAM
check_db_account AUDIT
check_db_account USER
check_db_account CONFIG
check_db_account NACOS

echo ""
echo "--- Redis ---"
check_not_placeholder "REDIS_PASSWORD"

echo ""
echo "--- RabbitMQ ---"
check_not_placeholder "RABBITMQ_USER"
check_not_placeholder "RABBITMQ_PASSWORD"

echo ""
echo "--- Nacos ---"
# Nacos 用户名不能未设置（compose 已移除 :-nacos 默认值，未配置将启动失败）
check_required "NACOS_USERNAME"
# Nacos 密码不能是默认值 nacos（生产环境）
if [ "${NACOS_PASSWORD:-}" = "nacos" ]; then
  echo "  WARN: NACOS_PASSWORD 是默认值 'nacos'，生产环境建议修改"
  WARNINGS=$((WARNINGS + 1))
else
  check_not_placeholder "NACOS_PASSWORD"
fi
# Nacos 鉴权凭证不能是占位符或公开周知弱值（生产环境）
check_not_placeholder "NACOS_AUTH_TOKEN"
check_not_placeholder "NACOS_AUTH_IDENTITY_VALUE" "^security$|^CHANGE_ME"
check_required "NACOS_AUTH_IDENTITY_KEY"

echo ""
echo "--- 认证 ---"
check_not_placeholder "INTERNAL_SERVICE_TOKEN"
check_not_placeholder "SHARE_COOKIE_SECRET"

# U3：Cookie Secure 与 TLS 的一致性校验。
# 当前部署为 IP 直连 HTTP，故默认 false（置 true 会因浏览器不回传 Secure Cookie 直接导致登录失效）。
# 一旦启用容器内 nginx TLS（TLS_ENABLED=true + 证书挂载），必须同步置 true，否则安全收益归零。
if [ "${TLS_ENABLED:-false}" = "true" ]; then
  if [ "${AUTH_COOKIE_SECURE:-false}" != "true" ]; then
    echo "  ERROR: TLS_ENABLED=true 但 AUTH_COOKIE_SECURE != true：HTTPS 已启用却仍签发非 Secure Cookie，必须改为 true"
    ERRORS=$((ERRORS + 1))
  else
    echo "  OK: TLS 已启用且 Cookie Secure 已开启"
  fi
elif [ "${AUTH_COOKIE_SECURE:-false}" != "true" ]; then
  echo "  WARN: AUTH_COOKIE_SECURE=false（站点走 HTTP，Cookie 无 Secure 属性，可被嗅探）。启用 TLS 后请置 true（不阻断部署）"
  WARNINGS=$((WARNINGS + 1))
else
  echo "  OK: AUTH_COOKIE_SECURE=true"
fi

echo ""
echo "--- OSS ---"
check_not_placeholder "OSS_ACCESS_KEY_ID"
check_not_placeholder "OSS_ACCESS_KEY_SECRET"

echo ""
echo "--- 邮件 ---"
if [ "${EMAIL_ENABLED:-true}" = "true" ]; then
  check_not_placeholder "EMAIL_USERNAME"
  check_not_placeholder "EMAIL_PASSWORD"
  check_not_placeholder "EMAIL_FROM"
else
  echo "  SKIP: 邮件服务已禁用 (EMAIL_ENABLED=false)"
fi

echo ""
echo "--- 本地存储（生产禁用） ---"
if [ "${STORAGE_LOCAL_ENABLED:-false}" = "true" ]; then
  echo "  ERROR: STORAGE_LOCAL_ENABLED=true 不允许用于生产：本地磁盘存储在容器/卷卸载时会丢失数据，且不可跨节点共享"
  ERRORS=$((ERRORS + 1))
else
  echo "  OK: STORAGE_LOCAL_ENABLED 为 false（使用 OSS 对象存储）"
fi

echo ""
echo "--- 监控栈（仅告警，不阻断部署） ---"
# Grafana 默认 admin/admin 极危险：.env 未设时 compose 回退为 admin（弱密码），故空值也要告警。
if [ -z "${GRAFANA_ADMIN_PASSWORD:-}" ] || echo "${GRAFANA_ADMIN_PASSWORD}" | grep -qE "CHANGE_ME|admin"; then
  echo "  WARN: GRAFANA_ADMIN_PASSWORD 未设置或仍是占位符/弱密码，生产环境请改为高强度密码（不阻断部署）"
  WARNINGS=$((WARNINGS + 1))
else
  echo "  OK: GRAFANA_ADMIN_PASSWORD 已设置"
fi

echo ""
echo "--- CORS ---"
check_not_placeholder "CORS_ALLOWED_ORIGINS"

echo ""
echo "--- Jasypt ---"
check_not_placeholder "JASYPT_PASSWORD"

echo ""
echo "--- 前端地址 ---"
if echo "${FRONTEND_BASE_URL:-}" | grep -qE "YOUR_SERVER_IP|CHANGE_ME|localhost"; then
  echo "  WARN: FRONTEND_BASE_URL 似乎是占位符: ${FRONTEND_BASE_URL}"
  WARNINGS=$((WARNINGS + 1))
else
  check_required "FRONTEND_BASE_URL"
fi

echo ""
echo "--- CORS × 前端地址 一致性（防登录 403 回归） ---"
# 背景（实测事故）：nginx 用 `proxy_set_header Host $host`（丢失端口），后端据此把请求视为
# 80 端口，于是浏览器访问 http://<ip>:HTTP_PORT 时 Origin 被判为跨域；若该 Origin 不在
# CORS_ALLOWED_ORIGINS 中，Spring 会直接 403 掉登录等所有 /api 请求 → 全站无法登录。
# 这里在部署前做静态一致性校验，把该故障拦在部署之前（而非上线后才发现）。
_cors_frontend_check() {
  local fe="${FRONTEND_BASE_URL:-}"
  local port="${HTTP_PORT:-80}"
  # 空值/占位符上文已单独处理，这里跳过以免重复报错
  if [ -z "$fe" ] || echo "$fe" | grep -qE "YOUR_SERVER_IP|CHANGE_ME"; then
    echo "  SKIP: FRONTEND_BASE_URL 未配置或为占位符，跳过 CORS 一致性校验"
    return 0
  fi
  fe="${fe%/}"
  local scheme="http"
  local rest="$fe"
  if echo "$fe" | grep -q '://'; then
    scheme="${fe%%://*}"
    rest="${fe#*://}"
  fi
  local host="${rest%%/*}"
  local origin
  if echo "$host" | grep -qE ':[0-9]+$'; then
    # 地址已显式带端口，直接采用
    origin="${scheme}://${host}"
  elif { [ "$port" = "80" ] && [ "$scheme" = "http" ]; } \
    || { [ "$port" = "443" ] && [ "$scheme" = "https" ]; }; then
    # 默认端口可省略
    origin="${scheme}://${host}"
  else
    # 前端地址未带端口，按入口发布端口 HTTP_PORT 补齐
    origin="${scheme}://${host}:${port}"
  fi
  local cors_norm="${CORS_ALLOWED_ORIGINS:-}"
  # 归一化：去空白、去全部斜杠，保证 `http://h:1` 与 `http://h:1/` 视为相同
  cors_norm="$(echo "$cors_norm" | tr -d '[:space:]')"
  cors_norm="${cors_norm//\//}"
  local origin_norm="${origin//\//}"
  if [ "$cors_norm" = "*" ] || echo ",$cors_norm," | grep -qF ",$origin_norm,"; then
    echo "  OK: 前端 Origin ($origin) 已在 CORS_ALLOWED_ORIGINS 中"
  elif echo "$cors_norm" | grep -q '\*'; then
    echo "  WARN: CORS_ALLOWED_ORIGINS 含通配符，无法静态确认是否覆盖前端 Origin ($origin)，请人工核对"
    WARNINGS=$((WARNINGS + 1))
  else
    echo "  ERROR: 前端 Origin ($origin) 不在 CORS_ALLOWED_ORIGINS 中"
    echo "         浏览器访问该地址时，登录等 /api 请求会被 Spring CORS 直接 403。"
    echo "         请把 $origin 加入 CORS_ALLOWED_ORIGINS（当前值: ${CORS_ALLOWED_ORIGINS:-<空>}）"
    ERRORS=$((ERRORS + 1))
  fi
}
_cors_frontend_check

echo ""
echo "===== 结果 ====="
if [ $ERRORS -gt 0 ]; then
  echo "ERROR: $ERRORS 个错误，$WARNINGS 个警告"
  echo "请修改 .env 后重新运行"
  exit 1
elif [ $WARNINGS -gt 0 ]; then
  echo "PASS: 0 个错误，$WARNINGS 个警告"
  exit 0
else
  echo "PASS: 所有检查通过"
  exit 0
fi
