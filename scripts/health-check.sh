#!/bin/bash
# ZXYZ 容器健康检查脚本
# 用法: ./scripts/health-check.sh

set -euo pipefail

# 清单 = docker-compose.yml 里**真实在跑**的 14 个容器：
#   基础设施 4（mysql/redis/nacos/rabbitmq）
#   + 后端 8 + gateway 1 + frontend-nginx 1 = 14
#
# ⚠️ 2026-10-03 修复（审计 I-2）：原数组里还有 zxyz-loki / zxyz-promtail 两条**陈旧条目**。
#   这两个服务已于 2026-09-18 迁入 docker-compose.observability.yml（profiles:["observability"]，
#   默认不启动、生产上从未被创建）⇒ `docker inspect` 恒失败 ⇒ `unhealthy` 常驻 ≥2
#   ⇒ 即使下面的计数器修好，脚本也**恒 exit 1**。已删除，与 docs/claude-infra.md 的
#   「14 容器」口径对齐。
#
# ⚠️ 2026-10-03 修复（审计 I-1）：计数器原写作 `((healthy++))` / `((unhealthy++))`。
#   在 `set -e` 下，算术命令的**退出码 = 表达式的值**；首次 0→1 自增时表达式值为 0
#   ⇒ 返回非零 ⇒ `set -e` 立即终止脚本。实测：脚本在**第一个**服务之后就静默退出，
#   只输出 1 行、恒 exit 1（cron/告警消费方拿到的是「全挂」假信号）。
#   改为 `x=$((x + 1))`：赋值语句的退出码恒为 0，与 `set -e` 兼容。
services=(
  zxyz-mysql
  zxyz-redis
  zxyz-nacos
  zxyz-rabbitmq
  zxyz-project-service
  zxyz-im-service
  zxyz-email-service
  zxyz-user-service
  zxyz-share-service
  zxyz-file-service
  zxyz-team-service
  zxyz-audit-service
  zxyz-gateway
  zxyz-frontend-nginx
)

healthy=0
unhealthy=0

for name in "${services[@]}"; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "not found")
  if [ "$status" = "healthy" ]; then
    echo "  $name"
    healthy=$((healthy + 1))
  else
    echo "  $name ($status)"
    unhealthy=$((unhealthy + 1))
  fi
done

echo ""
echo "Healthy: $healthy / $((healthy + unhealthy))"
if [ "$unhealthy" -gt 0 ]; then
  echo "Unhealthy: $unhealthy"
  exit 1
fi
