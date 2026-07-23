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
check_not_placeholder "CONFIG_DB_PASSWORD"

echo ""
echo "--- Redis ---"
check_not_placeholder "REDIS_PASSWORD"

echo ""
echo "--- RabbitMQ ---"
check_not_placeholder "RABBITMQ_USER"
check_not_placeholder "RABBITMQ_PASSWORD"

echo ""
echo "--- Nacos ---"
# Nacos 密码不能是默认值 nacos（生产环境）
if [ "${NACOS_PASSWORD:-}" = "nacos" ]; then
  echo "  WARN: NACOS_PASSWORD 是默认值 'nacos'，生产环境建议修改"
  WARNINGS=$((WARNINGS + 1))
else
  check_not_placeholder "NACOS_PASSWORD"
fi

echo ""
echo "--- 认证 ---"
check_not_placeholder "INTERNAL_SERVICE_TOKEN"
check_not_placeholder "SHARE_COOKIE_SECRET"

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
