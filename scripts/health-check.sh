#!/bin/bash
# ZXYZ 容器健康检查脚本
# 用法: ./scripts/health-check.sh

set -euo pipefail

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
  zxyz-loki
  zxyz-promtail
)

healthy=0
unhealthy=0

for name in "${services[@]}"; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "not found")
  if [ "$status" = "healthy" ]; then
    echo "  $name"
    ((healthy++))
  else
    echo "  $name ($status)"
    ((unhealthy++))
  fi
done

echo ""
echo "Healthy: $healthy / $((healthy + unhealthy))"
if [ "$unhealthy" -gt 0 ]; then
  echo "Unhealthy: $unhealthy"
  exit 1
fi
