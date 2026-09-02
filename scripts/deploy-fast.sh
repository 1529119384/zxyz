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
#   ./scripts/deploy-fast.sh --validate                    # 仅验证 .env 配置
#   ./scripts/deploy-fast.sh --clean-nacos                 # 清理 Nacos 日志后部署
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
VALIDATE_ONLY=false
CLEAN_NACOS=false
BUILD_FIRST=false
REPAIR_FLYWAY=false
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-pull)      NO_PULL=true; shift ;;
    --no-health)    NO_HEALTH=true; shift ;;
    --all)          SERVICES=("${ALL_APP_SERVICES[@]}"); shift ;;
    --validate)     VALIDATE_ONLY=true; shift ;;
    --clean-nacos)  CLEAN_NACOS=true; shift ;;
    --build)        BUILD_FIRST=true; shift ;;
    --repair-flyway) REPAIR_FLYWAY=true; shift ;;
    -*)             echo "Unknown option: $1"; exit 1 ;;
    *)              SERVICES+=("$1"); shift ;;
  esac
done

# --- 环境验证 ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- 若部署目录 .env 缺失，自动从 .env.example 生成（仅内部机密；外部凭证需手动填）---
if [ ! -f "$DEPLOY_DIR/.env" ]; then
  echo "INFO: $DEPLOY_DIR/.env 不存在，自动从 .env.example 复制并生成内部机密（init-secrets.sh）..."
  if ! bash "$SCRIPT_DIR/init-secrets.sh" "$DEPLOY_DIR/.env"; then
    echo "ERROR: 自动生成 .env 失败，请在 $DEPLOY_DIR 手动执行: cp .env.example .env && ./scripts/init-secrets.sh"
    exit 1
  fi
  echo "INFO: .env 已生成，外部凭证（OSS/邮箱/前端地址等 CHANGE_ME_*）仍需手动填写。"
fi

if [ "$VALIDATE_ONLY" = true ]; then
  bash "$SCRIPT_DIR/validate-env.sh" "$DEPLOY_DIR/.env"
  exit $?
fi

# --- Nacos 日志清理 ---
if [ "$CLEAN_NACOS" = true ]; then
  echo "===== Cleaning Nacos logs ====="
  docker exec zxyz-nacos sh -c "find /home/nacos/logs -name '*.log.*' -mtime +7 -delete 2>/dev/null"
  docker exec zxyz-nacos sh -c "find /home/nacos/logs -name 'nacos.log' -size +100M -exec truncate -s 20M {} \; 2>/dev/null"
  echo "Nacos logs cleaned"
  echo ""
fi

# --- 自动验证 .env ---
if [ -f "$SCRIPT_DIR/validate-env.sh" ] && [ -f "$DEPLOY_DIR/.env" ]; then
  echo "===== Validating .env ====="
  if ! bash "$SCRIPT_DIR/validate-env.sh" "$DEPLOY_DIR/.env"; then
    echo ""
    echo "ERROR: .env validation failed. Fix issues before deploying."
    echo "Run: ./scripts/validate-env.sh $DEPLOY_DIR/.env"
    exit 1
  fi
  echo ""
fi

if [ ${#SERVICES[@]} -eq 0 ]; then
  echo "Usage: deploy-fast.sh [--no-pull] [--no-health] [--build] [--all] [--validate] [--clean-nacos] [--repair-flyway] <service> [service...]"
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
echo "Build:    $([ "$BUILD_FIRST" = true ] && echo "YES" || echo "SKIP")"
echo "Health:   $([ "$NO_HEALTH" = true ] && echo "SKIP" || echo "WAIT")"
echo "Nacos:    $([ "$CLEAN_NACOS" = true ] && echo "CLEAN" || echo "SKIP")"
echo "Flyway:   $([ "$REPAIR_FLYWAY" = true ] && echo "REPAIR" || echo "SKIP")"
echo ""

# --- 构建镜像 ---
if [ "$BUILD_FIRST" = true ]; then
  echo "===== Building images ====="
  for svc in "${SERVICES[@]}"; do
    module="zxyz-${svc}"
    echo "Building $svc (module: $module)..."
    cd "$DEPLOY_DIR/../ZXYZdatabaseBack"
    mvn -B -T 1C -pl "$module" -am package -DskipTests || {
      echo "ERROR: Maven build failed for $module"
      exit 1
    }
    echo "Building Docker image for $svc..."
    cd "$DEPLOY_DIR"
    docker compose build "$svc" || {
      echo "ERROR: Docker build failed for $svc"
      exit 1
    }
  done
  echo "Build complete"
  echo ""
fi

# --- 拉取镜像 ---
if [ "$NO_PULL" = false ] && [ "$BUILD_FIRST" = false ]; then
  echo "===== Pulling images ====="
  for svc in "${SERVICES[@]}"; do
    docker compose pull "$svc" &
  done
  wait
  echo "Pull complete"
fi

# --- Flyway repair（修复 V2 checksum mismatch） ---
if [ "$REPAIR_FLYWAY" = true ]; then
  echo "===== Flyway Repair ====="
  echo "修复 admin-service V2 迁移校验不匹配问题..."
  # 读取 .env 中的 MySQL 密码
  MYSQL_PASSWORD=""
  if [ -f "$DEPLOY_DIR/.env" ]; then
    MYSQL_PASSWORD=$(grep -E '^MYSQL_ROOT_PASSWORD=' "$DEPLOY_DIR/.env" | head -1 | cut -d'=' -f2-)
  fi

  if [ -z "$MYSQL_PASSWORD" ]; then
    echo "ERROR: 无法读取 MYSQL_ROOT_PASSWORD（请确认 $DEPLOY_DIR/.env 存在且包含该变量）"
    exit 1
  fi

  echo "执行 flyway repair（更新 zxyz_config 库的 schema history 校验）..."
  docker run --rm \
    --network zxyz-net \
    -e "FLYWAY_URL=jdbc:mysql://mysql:3306/zxyz_config?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
    -e "FLYWAY_USER=root" \
    -e "FLYWAY_PASSWORD=$MYSQL_PASSWORD" \
    flyway/flyway:10.12 repair || {
      echo "ERROR: flyway repair 执行失败"
      exit 1
    }
  echo "Flyway repair 完成"
  echo ""
fi

# --- 重启容器 ---
echo "===== Restarting ====="
if [ "$BUILD_FIRST" = true ]; then
  # 本地构建后仅重启自身，不重启依赖服务
  docker compose up -d --no-deps "${SERVICES[@]}"
else
  docker compose up -d "${SERVICES[@]}"
fi

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
