#!/bin/bash
# =============================================================================
# 本地一键启动后端服务（自动注入 .env 环境变量）
#
# 用法: bash scripts/run-local.sh <service>
#   例: bash scripts/run-local.sh team-service
#       bash scripts/run-local.sh gateway
#       DRY_RUN=1 bash scripts/run-local.sh team-service   # 只打印变量不启动
#
# 解决的问题:
#   - 不用在 IDEA 里每个服务手动配置环境变量（换机器/换目录零配置）
#   - 自动注入 Nacos(18048)/Redis/RabbitMQ/Jasypt/内部 Token 等
#   - 按服务把 datasource 密码键统一指向 .env 的 MYSQL_ROOT_PASSWORD
#     （dev yml 默认密码是 123456，与 Docker MySQL 实际密码不一致）
#   - mvn spring-boot:run 启动，DevTools 改代码自动重启
#   - 需要断点调试时改用 IDEA 运行配置（本脚本适合快速起服务）
# =============================================================================

set -euo pipefail

SERVICE="${1:?用法: bash scripts/run-local.sh <service>，如 team-service / gateway}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
BACKEND_DIR="$PROJECT_ROOT/ZXYZdatabaseBack"

# ---- 0. 前置检查 ----
[ -f "$ENV_FILE" ] || { echo "✗ .env 不存在: $ENV_FILE（先 cp .env.example .env）"; exit 1; }
[ -d "$BACKEND_DIR" ] || { echo "✗ 后端目录不存在: $BACKEND_DIR"; exit 1; }

# ---- 1. 注入 .env 中的变量（KEY=VALUE 逐行导出，处理 CRLF）----
while IFS= read -r line; do
    line="${line%$'\r'}"
    case "$line" in
        ''|\#*) continue ;;            # 跳过空行和注释
        *=*) export "$line" ;;
    esac
done < "$ENV_FILE"

# ---- 2. 本地开发固定覆盖（docker-compose 内部主机名不适用于本地 JVM）----
NACOS_PORT="${NACOS_PORT:-18048}"
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-http://localhost:${NACOS_PORT}}"
# 以下三项在 .env 中是 Docker 内部主机名（mysql / admin-service），本地必须强制覆盖为 localhost
export CONFIG_DB_HOST="localhost"
export ADMIN_SERVICE_BASE_URL="http://localhost:18088"
export REDIS_HOST="localhost"
export RABBITMQ_HOST="localhost"
# .env 键名是 RABBITMQ_USER，而 application-common.yml 读取 RABBITMQ_USERNAME，做映射
export RABBITMQ_USERNAME="${RABBITMQ_USER}"

# ---- 3. 按服务映射 datasource 密码键（统一指向 MYSQL_ROOT_PASSWORD）----
DB_PASS="${MYSQL_ROOT_PASSWORD:?✗ .env 缺少 MYSQL_ROOT_PASSWORD}"
case "$SERVICE" in
    team-service)    export TEAM_DATASOURCE_PASSWORD="$DB_PASS" ;;
    user-service)    export USER_DATASOURCE_PASSWORD="$DB_PASS" ;;
    file-service)    export FILE_DATASOURCE_PASSWORD="$DB_PASS" ;;
    project-service) export PROJECT_DATASOURCE_PASSWORD="$DB_PASS" ;;
    share-service)   export SHARE_DATASOURCE_PASSWORD="$DB_PASS" ;;
    im-service)      export IM_DATASOURCE_PASSWORD="$DB_PASS" ;;
    email-service)   export EMAIL_DATASOURCE_PASSWORD="$DB_PASS" ;;
    audit-service)   export AUDIT_DATASOURCE_PASSWORD="$DB_PASS" ;;
    admin-service)   export ADMIN_DATASOURCE_PASSWORD="$DB_PASS" CONFIG_DB_PASSWORD="$DB_PASS" ;;
    gateway)         : ;;   # 网关不连库
    *)
        echo "✗ 未知服务: $SERVICE（可用: team/user/file/project/share/im/email/audit/admin-service/gateway）"
        exit 1
        ;;
esac

# ---- 4. dry-run：只打印关键变量，不启动 ----
if [ "${DRY_RUN:-}" = "1" ]; then
    echo "=== 注入的关键环境变量（值已打码）==="
    for k in NACOS_SERVER_ADDR NACOS_USERNAME JASYPT_PASSWORD INTERNAL_SERVICE_TOKEN \
             REDIS_PASSWORD REDIS_HOST RABBITMQ_USERNAME RABBITMQ_HOST \
             TEAM_DATASOURCE_PASSWORD USER_DATASOURCE_PASSWORD FILE_DATASOURCE_PASSWORD \
             PROJECT_DATASOURCE_PASSWORD SHARE_DATASOURCE_PASSWORD IM_DATASOURCE_PASSWORD \
             EMAIL_DATASOURCE_PASSWORD AUDIT_DATASOURCE_PASSWORD ADMIN_DATASOURCE_PASSWORD \
             CONFIG_DB_PASSWORD CONFIG_DB_HOST ADMIN_SERVICE_BASE_URL; do
        v="${!k:-<未设置>}"
        case "$k" in
            *PASSWORD*|*TOKEN*|*SECRET*) echo "  $k=${v:0:4}****" ;;
            *) echo "  $k=$v" ;;
        esac
    done
    exit 0
fi

# ---- 5. 启动 ----
MODULE="zxyz-${SERVICE}"
echo "🚀 启动 ${MODULE}（dev profile）..."
echo "   Nacos:  $NACOS_SERVER_ADDR"
echo "   数据库: localhost:3306/${SERVICE#zxyz-}"
echo "   改代码后 DevTools 自动重启；断点调试请用 IDEA 运行配置"
cd "$BACKEND_DIR"
exec mvn -pl "$MODULE" spring-boot:run
