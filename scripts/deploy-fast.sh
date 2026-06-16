#!/bin/bash
# =============================================================================
# ZXYZ 快速部署脚本
# 用于开发环境快速拉取+重启单个/多个服务，绕过完整 CI/CD 健康检查等待。
#
# 用法:
#   ./scripts/deploy-fast.sh project-service              # 拉取+重启
#   ./scripts/deploy-fast.sh project-service gateway       # 多个服务
#   ./scripts/deploy-fast.sh --no-health project-service   # 跳过健康检查（最快）
#   ./scripts/deploy-fast.sh --all                         # 重启所有应用服务
#
# 前置条件:
#   - 服务器上已部署 docker-compose.yml（/www/zxyz/）
#   - 镜像已推送到 GHCR（CI/CD 已构建完成）
# =============================================================================

set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/www/zxyz}"

ALL_APP_SERVICES=(
  project-service im-service email-service user-service
  share-service file-service team-service audit-service
  admin-service gateway frontend-nginx
)

# --- 参数解析 ---
NO_PULL=false
NO_HEALTH=false
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-pull)   NO_PULL=true; shift ;;
    --no-health) NO_HEALTH=true; shift ;;
    --all)       SERVICES=("${ALL_APP_SERVICES[@]}"); shift ;;
    -*)          echo "Unknown option: $1"; exit 1 ;;
    *)           SERVICES+=("$1"); shift ;;
  esac
done

if [ ${#SERVICES[@]} -eq 0 ]; then
  echo "Usage: deploy-fast.sh [--no-pull] [--no-health] [--all] <service> [service...]"
  echo ""
  echo "Available services:"
  for s in "${ALL_APP_SERVICES[@]}"; do
    echo "  $s"
  done
  exit 1
fi

cd "$DEPLOY_DIR"

echo "===== Fast Deploy ====="
echo "Services: ${SERVICES[*]}"
echo "Pull:     $([ "$NO_PULL" = true ] && echo "SKIP" || echo "YES")"
echo "Health:   $([ "$NO_HEALTH" = true ] && echo "SKIP" || echo "WAIT")"
echo ""

# --- 拉取镜像 ---
if [ "$NO_PULL" = false ]; then
  echo "===== Pulling images ====="
  for svc in "${SERVICES[@]}"; do
    docker compose pull "$svc" &
  done
  wait
  echo "Pull complete"
fi

# --- 重启容器 ---
echo "===== Restarting ====="
docker compose up -d "${SERVICES[@]}"

# --- 等待容器 running 状态（非健康） ---
echo "===== Waiting for containers to start ====="
MAX_WAIT=60
for svc in "${SERVICES[@]}"; do
  container="zxyz-${svc}"
  elapsed=0
  while [ $elapsed -lt $MAX_WAIT ]; do
    state=$(docker inspect --format='{{.State.Status}}' "$container" 2>/dev/null || echo "missing")
    if [ "$state" = "running" ]; then
      echo "  $svc: running (${elapsed}s)"
      break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  if [ $elapsed -ge $MAX_WAIT ]; then
    echo "  $svc: TIMEOUT after ${MAX_WAIT}s (state: $state)"
  fi
done

# --- 健康检查（可选） ---
if [ "$NO_HEALTH" = false ]; then
  echo ""
  echo "===== Health check ====="
  HEALTH_TIMEOUT=180
  elapsed=0
  while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
    all_healthy=true
    for svc in "${SERVICES[@]}"; do
      container="zxyz-${svc}"
      health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$container" 2>/dev/null || echo "missing")
      if [ "$health" != "healthy" ] && [ "$health" != "no-healthcheck" ]; then
        all_healthy=false
      fi
    done

    if [ "$all_healthy" = true ]; then
      echo "All services healthy (${elapsed}s)"
      break
    fi

    if [ $((elapsed % 10)) -eq 0 ] && [ $elapsed -gt 0 ]; then
      for svc in "${SERVICES[@]}"; do
        container="zxyz-${svc}"
        health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$container" 2>/dev/null || echo "?")
        echo "  [$elapsed s] $svc: $health"
      done
    fi

    sleep 5
    elapsed=$((elapsed + 5))
  done

  if [ $elapsed -ge $HEALTH_TIMEOUT ]; then
    echo "WARN: Health check timeout after ${HEALTH_TIMEOUT}s"
    for svc in "${SERVICES[@]}"; do
      container="zxyz-${svc}"
      health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$container" 2>/dev/null || echo "?")
      echo "  $svc: $health"
    done
  fi
fi

# --- 最终状态 ---
echo ""
echo "===== Status ====="
docker compose ps "${SERVICES[@]}"
echo ""
echo "===== Deploy complete ====="
