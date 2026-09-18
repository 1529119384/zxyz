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

# --- 叠加 TLS 覆盖层（U2）：与 CI 部署保持一致 ---
# 若外部已显式指定 COMPOSE_FILE 则沿用；否则依据 .env 的 TLS_ENABLED 推导。
# 缺了这段，TLS 部署走本脚本回滚会把 443 入口静默降级为纯 HTTP。
if [ -z "${COMPOSE_FILE:-}" ]; then
  if grep -qE '^[[:space:]]*TLS_ENABLED[[:space:]]*=[[:space:]]*true' .env 2>/dev/null \
     && [ -f docker-compose.tls.yml ]; then
    export COMPOSE_FILE="docker-compose.yml:docker-compose.tls.yml"
    echo "INFO: 检测到 TLS_ENABLED=true，回滚将叠加 docker-compose.tls.yml"
  else
    export COMPOSE_FILE="docker-compose.yml"
  fi
fi

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
# 🔴 必须带 --no-deps（2026-09-19 修复）：默认回滚的服务集含 frontend-nginx，
#   而它声明了 `depends_on: gateway`（docker-compose.yml:1017）。不带 --no-deps 时
#   compose 会把 gateway（连带其 nacos/redis/rabbitmq 依赖）一并纳入「必要时重建」的计划。
#   回滚场景下这尤其危险：本脚本刚把 .env 的 APP_IMAGE_TAG 改成**上一版 sha**，
#   连带重建 gateway 时若该 sha 的镜像本地已不存在，compose 会退化走 compose 里的 build: 段，
#   而服务器上没有 ZXYZdatabaseBack 源码 ⇒ 报 lstat 之类与真实原因无关的错误。
#   且本脚本**没有 rollback-of-rollback** 兜底，连带重建失败会让站点停在「新旧混合」状态。
#   点名服务 + --no-deps 也正是 CI 主部署路径的纪律（deploy-on-server.sh:442）。
docker compose up -d --no-deps "${SERVICES[@]}"

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
