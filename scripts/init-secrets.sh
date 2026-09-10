#!/bin/bash
# =============================================================================
# ZXYZ 首次部署机密自动生成脚本
#
# 解决痛点：新环境部署需手动 cp .env.example .env 并手填一堆 CHANGE_ME_* 强密码。
# 本脚本在首次部署时自动生成「可随机化的系统内部机密」写入 .env 持久化，
# 并在服务器本地日志（init-secrets.log，chmod 600）记录完整值，终端仅打码显示。
#
# 用法:
#   ./scripts/init-secrets.sh [--dry-run] [--force] [ENV_FILE]
#     ENV_FILE   默认 .env
#     --dry-run  只打印将生成的值，不写文件
#     --force    覆盖已有的占位符值（CHANGE_ME_*）；默认幂等，已有非占位符值跳过
#
# 范围：仅生成「系统内部机密」（数据库/Redis/RabbitMQ/Nacos/Jasypt/内部 token/
#       Grafana 等纯本地机密）。OSS 访问密钥、邮箱密码、前端地址等「外部凭证」
#       必须人工填真实值，本脚本不生成（否则文件上传/邮件功能静默失效）。
# =============================================================================

set -euo pipefail

# U15: 在写任何机密之前收紧新建文件权限，消除 init-secrets.log 明文窗口。
# 日志在 :112 才首次写入明文，而原结尾 chmod 600 之前依赖 umask（常 022→644）。
# 这里显式 umask 077，使之后 touch/>> 创建的文件默认为 600（仅属主可读）。
umask 077

DRY_RUN=false
FORCE=false
ENV_FILE=".env"

for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=true ;;
    --force)   FORCE=true ;;
    -*) echo "Unknown arg: $a" >&2; exit 1 ;;
    *)  ENV_FILE="$a" ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# --- 确保 .env 存在（新环境若只 cp 了 .env.example，自动复制）---
if [ ! -f "$ENV_FILE" ]; then
  if [ -f "$PROJECT_DIR/.env.example" ]; then
    echo "INFO: $ENV_FILE 不存在，从 .env.example 复制"
    cp "$PROJECT_DIR/.env.example" "$ENV_FILE"
  else
    echo "ERROR: $ENV_FILE 与 .env.example 均不存在，无法初始化" >&2
    exit 1
  fi
fi

LOG_FILE="$(dirname "$ENV_FILE")/init-secrets.log"

# --- 补全缺失 KEY（复用 validate-env 的 sync-only 能力，不生成值）---
# 注意：validate-env.sh 把第一个位置参数当作 .env 路径，--sync-only 作为标志，
# 故必须写成 "<env_file> --sync-only"（顺序不能反，否则它会把 --sync-only 当路径报错）。
if [ -f "$SCRIPT_DIR/validate-env.sh" ]; then
  bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE" --sync-only || true
fi

# --- 加载 .env ---
set -a
source "$ENV_FILE"
set +a

# --- openssl 可用性检查 ---
if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: 未找到 openssl，无法生成随机机密，请手动填写 .env 中的 CHANGE_ME_* 值" >&2
  exit 1
fi

# 生成随机值（无管道，避免 pipefail + SIGPIPE 误杀脚本）
# $1=kind: base64_32 | token_32 | default
gen_val() {
  local raw; raw="$(openssl rand -base64 32)"
  case "$1" in
    # NACOS_AUTH_TOKEN：Nacos 要求 base64 且解码后 ≥32 字节，必须原样 base64
    # （不去除 /+=、不截断，否则破坏 base64 对齐导致解码字节数不足）
    base64_32) printf '%s' "$raw" ;;
    # 内部 token：32 位随机串（去 /+= 避免 shell 注入，截 32）
    token_32)  raw="${raw//[+/=]/}"; printf '%s' "${raw:0:32}" ;;
    # 通用强密码：24 位（去 /+= 避免 REDIS requirepass 经 sh -c 插值时的特殊字符问题）
    *)         raw="${raw//[+/=]/}"; printf '%s' "${raw:0:24}" ;;
  esac
}

# 生成单个机密并写回 .env（幂等）
# $1=key  $2=kind
gen_secret() {
  local key="$1" kind="${2:-default}"
  local cur="${!key:-}"

  # 真实（非 CHANGE_ME）值始终跳过，--force 也不覆盖已部署的真实机密（防重部署误覆盖）
  if [ -n "$cur" ] && ! echo "$cur" | grep -qE '^CHANGE_ME'; then
    echo "  $key: 已存在真实值，跳过（--force 也不覆盖）"
    return 0
  fi

  local val
  val="$(gen_val "$kind")"

  if [ "$DRY_RUN" = true ]; then
    echo "  $key: [dry-run] 将生成（不写文件）"
    return 0
  fi

  # 写回 .env：已存在该行则 sed 替换（用 | 作分隔符，base64 值不含 | 安全），否则追加
  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
  else
    echo "${key}=${val}" >> "$ENV_FILE"
  fi

  # 完整值落服务器本地日志（chmod 600），不进 GitHub Actions 公开日志
  echo "$(date '+%Y-%m-%d %H:%M:%S') GENERATED ${key}=${val}" >> "$LOG_FILE"
  # 终端仅打码（前 4 位 + ****），进 SSH stdout 但不泄露明文
  echo "  $key: ${val:0:4}**** (完整值见 $LOG_FILE)"
}

# 写入明文默认值（幂等，已有真实值不覆盖）——用于非机密的白名单/地址类配置。
# 与 gen_secret 的区别：写入的是固定默认常量而非随机机密，因此不落 init-secrets.log
# （该日志按机密处理、chmod 600，不适合放明文白名单），只在终端回显。
# $1=key  $2=默认值
set_default() {
  local key="$1" val="$2"
  local cur="${!key:-}"

  # 与 gen_secret 同一套幂等判据：真实（非 CHANGE_ME）值始终跳过
  if [ -n "$cur" ] && ! echo "$cur" | grep -qE '^CHANGE_ME'; then
    echo "  $key: 已存在真实值，跳过"
    return 0
  fi

  if [ "$DRY_RUN" = true ]; then
    echo "  $key: [dry-run] 将写入默认值（不写文件）"
    return 0
  fi

  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
  else
    echo "${key}=${val}" >> "$ENV_FILE"
  fi
  echo "  $key: 已写入默认值 ${val}（生产部署请按实际域名修改）"
}

echo "===== 生成/校验部署机密（幂等）====="
gen_secret MYSQL_ROOT_PASSWORD
# 说明：CONFIG_DB_PASSWORD 不再与 MYSQL_ROOT_PASSWORD 同值（原 B1 逻辑已废弃）。
# U1 之后 config 库由专用账户 zxyz_config 连接，密码在下方「数据库最小权限专用
# 账户凭据」段由 gen_secret CONFIG_DB_PASSWORD 独立生成（幂等，已有真实值跳过）。
# 若运维仍沿用 root 连 config 库（.env 中 CONFIG_DB_USERNAME=root），则该密码保持
# 原值不变（gen_secret 见真实值即跳过），行为与旧版一致。
gen_secret REDIS_PASSWORD
gen_secret RABBITMQ_USER
gen_secret RABBITMQ_PASSWORD
gen_secret INTERNAL_SERVICE_TOKEN token_32
gen_secret SHARE_COOKIE_SECRET
gen_secret NACOS_AUTH_TOKEN base64_32
gen_secret NACOS_AUTH_IDENTITY_VALUE
gen_secret NACOS_PASSWORD
gen_secret JASYPT_PASSWORD
gen_secret GRAFANA_ADMIN_PASSWORD
gen_secret EMAIL_CONFIG_SECRET
# P2-A2: 服务级白名单矩阵每服务独立密钥（互不相同，且与 INTERNAL_SERVICE_TOKEN 不同）
gen_secret SVC_ADMIN_KEY token_32
gen_secret SVC_AUDIT_KEY token_32
gen_secret SVC_EMAIL_KEY token_32
gen_secret SVC_FILE_KEY token_32
gen_secret SVC_IM_KEY token_32
gen_secret SVC_PROJECT_KEY token_32
gen_secret SVC_SHARE_KEY token_32
gen_secret SVC_TEAM_KEY token_32
gen_secret SVC_USER_KEY token_32
gen_secret SVC_GATEWAY_KEY token_32

# --- U1: 数据库最小权限专用账户凭据（幂等）---
# 为 10 个业务库各写入用户名默认值（明文，非机密）与随机密码（机密）。
# 若运维选择继续用 root（不启用专用账户），可保留这些密码为 CHANGE_ME_* 占位符，
# docker-compose 回退 root 即可，不影响现网；grant-least-privilege.sh 会在变量缺失时报错。
echo "===== 生成数据库最小权限账户凭据（幂等）====="
set_default PROJECT_DB_USERNAME "zxyz_project"
set_default IM_DB_USERNAME "zxyz_im"
set_default EMAIL_DB_USERNAME "zxyz_email"
set_default SHARE_DB_USERNAME "zxyz_share"
set_default FILE_DB_USERNAME "zxyz_file"
set_default TEAM_DB_USERNAME "zxyz_team"
set_default AUDIT_DB_USERNAME "zxyz_audit"
set_default USER_DB_USERNAME "zxyz_user"
set_default CONFIG_DB_USERNAME "zxyz_config"
set_default NACOS_DB_USERNAME "zxyz_nacos"

gen_secret PROJECT_DB_PASSWORD
gen_secret IM_DB_PASSWORD
gen_secret EMAIL_DB_PASSWORD
gen_secret SHARE_DB_PASSWORD
gen_secret FILE_DB_PASSWORD
gen_secret TEAM_DB_PASSWORD
gen_secret AUDIT_DB_PASSWORD
gen_secret USER_DB_PASSWORD
gen_secret CONFIG_DB_PASSWORD
gen_secret NACOS_DB_PASSWORD

# --- 非机密的明文默认值（N6）---
# CORS_ALLOWED_ORIGINS 是明文白名单、不是机密，但 .env.example 里给的是
# CHANGE_ME_CORS_ORIGIN，而 validate-env.sh 对它做的是 ERROR 级 check_not_placeholder。
# 若此处不生成默认值，全新环境跑完 init-secrets 仍会被 validate-env 拦死。
# 默认值与 nacos-config/zxyz-dynamic.yml 的回退值保持一致。
echo "===== 写入明文默认值（非机密，幂等）====="
set_default CORS_ALLOWED_ORIGINS "http://localhost:5173,http://localhost:4173"

# --- 收尾：收紧权限 ---
chmod 600 "$ENV_FILE" 2>/dev/null || true
chmod 600 "$LOG_FILE" 2>/dev/null || true

if [ "$DRY_RUN" = false ]; then
  echo "===== 机密初始化完成：请妥善保存 $LOG_FILE（含完整值）====="
  echo "      外部凭证（OSS 访问密钥/邮箱密码/前端地址等）仍需手动填写，否则相关功能不可用。"
fi
