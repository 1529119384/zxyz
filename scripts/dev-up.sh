#!/bin/bash
# =============================================================================
# ZXYZ 本地开发环境启动脚本
# 仅启动基础设施（MySQL / Nacos / Redis / RabbitMQ），不启动 Java 服务。
# Java 服务通过 IDE 本地运行，连接容器网络。
#
# 用法:
#   ./scripts/dev-up.sh              # 启动基础设施
#   ./scripts/dev-up.sh down         # 停止基础设施
#   ./scripts/dev-up.sh reset        # 重置数据卷（清空所有数据）
#   ./scripts/dev-up.sh logs         # 查看所有服务日志
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_DEV="$PROJECT_ROOT/docker-compose.dev.yml"
COMPOSE_BASE="$PROJECT_ROOT/docker-compose.yml"

ACTION="${1:-up}"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
    echo "ERROR: .env 不存在，请先复制 .env.example: cp .env.example .env"
    exit 1
fi

# 检查 Docker 是否运行
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker 未运行，请先启动 Docker Desktop"
    exit 1
fi

case "$ACTION" in
    up|start)
        echo "启动本地开发基础设施（MySQL / Nacos / Redis / RabbitMQ）..."
        docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" up -d
        echo ""
        echo "等待服务就绪..."
        docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" ps
        echo ""
        echo "服务地址："
        echo "  MySQL:    localhost:${MYSQL_PORT:-3306}"
        echo "  Nacos:    http://localhost:${NACOS_PORT:-8848}/nacos (${NACOS_USERNAME:-nacos}/${NACOS_PASSWORD})"
        echo "  Nacos UI: http://localhost:${NACOS_CONSOLE_PORT:-8080}"
        echo "  Redis:    localhost:${REDIS_PORT:-6379}"
        echo "  RabbitMQ: http://localhost:${RABBITMQ_MGMT_PORT:-15672} (${RABBITMQ_USER:-guest}/${RABBITMQ_PASSWORD})"
        echo ""
        echo "IDE 中运行服务时，配置 Nacos 地址为: localhost:${NACOS_PORT:-8848}"
        ;;
    down|stop)
        echo "停止本地开发基础设施..."
        docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" down
        ;;
    reset)
        echo "WARNING: 这将删除所有数据卷（数据库、Nacos、Redis、RabbitMQ 数据）！"
        read -p "确认？(yes/no): " CONFIRM
        if [ "$CONFIRM" != "yes" ]; then
            echo "已取消"
            exit 0
        fi
        docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" down -v
        echo "数据卷已清空"
        ;;
    logs)
        docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEV" logs -f "${2:-}"
        ;;
    *)
        echo "用法: $0 {up|down|reset|logs [service]}"
        exit 1
        ;;
esac
