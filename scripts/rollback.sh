#!/bin/bash
# =============================================================================
# ZXYZ 回滚脚本
# 一键回滚到上一个部署版本。
#
# 用法:
#   ./scripts/rollback.sh                    # 回滚所有服务
#   ./scripts/rollback.sh --no-pull gateway  # 跳过拉取，回滚指定服务
#   ./scripts/rollback.sh --validate         # 仅验证 .env.previous 存在
#
# 前置条件:
#   - 服务器上已部署 docker-compose.yml（/www/zxyz/）
#   - CI/CD 部署时会自动生成 .env.previous
#
# 关于镜像 tag（重要，2026 修订）：
#   - 现已改用「不可变」commit sha 作为 APP_IMAGE_TAG（部署 job 写 github.sha，
#     与 build-and-push 推送的 sha tag 精确匹配），不再使用 dev/latest 等可变 tag。
#   - 因此 .env.previous 记录的就是「上一个部署版本的旧 sha」，回滚可精确回到上一版本，
#     不会被新构建覆盖（旧可变 tag 同名覆盖导致回滚失效的问题已修复）。
#   - IMAGE_PREFIX 来源于同目录 .env（compose v2 自动加载），本脚本只改写 APP_IMAGE_TAG，
#     回滚时 IMAGE_PREFIX 仍从 .env 读取，无需单独传入。
#   - 风险提醒：若 IMAGE_PREFIX 指向私有 registry，须确保旧 sha 镜像未被 GC 清理，
#     否则 docker compose pull ...:<旧sha> 会失败（依赖 build-and-push 推送并保留的 sha tag）。
# =============================================================================

set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/www/zxyz}"

# --- 参数解析 ---
NO_PULL=false
VALIDATE_ONLY=false
SERVICES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-pull)    NO_PULL=true; shift ;;
    --validate)   VALIDATE_ONLY=true; shift ;;
    -*)           echo "Unknown option: $1"; exit 1 ;;
    *)            SERVICES+=("$1"); shift ;;
  esac
done

cd "$DEPLOY_DIR"

# --- 验证 .env.previous ---
if [ ! -f ".env.previous" ]; then
  echo "ERROR: .env.previous 不存在，无法回滚"
  echo "提示: CI/CD 部署时会自动记录上一个镜像 tag 到 .env.previous"
  exit 1
fi

if [ "$VALIDATE_ONLY" = true ]; then
  echo "OK: .env.previous 存在"
  source .env.previous 2>/dev/null || true
  echo "上一个镜像 tag: ${APP_IMAGE_TAG:-unknown}"
  exit 0
fi

# --- 读取上一个 tag ---
set -a
source ".env.previous"
set +a

PREV_TAG="${APP_IMAGE_TAG:-}"
if [ -z "$PREV_TAG" ]; then
  echo "ERROR: .env.previous 中 APP_IMAGE_TAG 为空"
  exit 1
fi

echo "===== Rolling back to $PREV_TAG ====="

# --- 更新 .env ---
sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=$PREV_TAG|" .env 2>/dev/null || echo "APP_IMAGE_TAG=$PREV_TAG" >> .env

# --- 默认回滚所有应用服务 ---
if [ ${#SERVICES[@]} -eq 0 ]; then
  SERVICES=(
    project-service im-service email-service user-service
    share-service file-service team-service audit-service
    admin-service gateway frontend-nginx
  )
fi

echo "Services: ${SERVICES[*]}"
echo "Image tag: $PREV_TAG"
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

echo ""
echo "===== Status ====="
docker compose ps "${SERVICES[@]}"
echo ""
echo "===== Rollback complete ====="
