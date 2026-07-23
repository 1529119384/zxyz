#!/bin/bash
# =============================================================================
# ZXYZ 镜像源切换脚本
# 在阿里云 ACR 和 GitHub Container Registry (GHCR) 之间切换
# =============================================================================

set -euo pipefail

MODE="${1:-}"
if [ -z "$MODE" ]; then
    echo "用法: $0 <enable|disable>"
    echo "  enable  切换到阿里云 ACR"
    echo "  disable 切换回 GitHub GHCR"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CI_FILE="$ROOT_DIR/.github/workflows/ci-cd.yml"
DOCKER_COMPOSE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="$ROOT_DIR/.env"

if [ ! -f "$CI_FILE" ]; then
    echo "ERROR: 未找到 $CI_FILE"
    exit 1
fi

if [ "$MODE" = "enable" ]; then
    echo "===== 切换到阿里云 ACR ====="

    # 1. 更新 CI/CD workflow
    echo "[1/3] 更新 .github/workflows/ci-cd.yml ..."
    sed -i 's|registry: ghcr.io|registry: registry.cn-shenzhen.aliyuncs.com|g' "$CI_FILE"
    sed -i 's|ghcr.io/${{ github.repository_owner }}|registry.cn-shenzhen.aliyuncs.com/zxyz|g' "$CI_FILE"
    sed -i 's|IMAGE_PREFIX: ghcr.io/${{ github.repository_owner }}|IMAGE_PREFIX: registry.cn-shenzhen.aliyuncs.com/zxyz/|g' "$CI_FILE" || true

    # 2. 更新 docker-compose.yml
    echo "[2/3] 更新 docker-compose.yml ..."
    if [ -f "$DOCKER_COMPOSE" ]; then
        sed -i 's|\${IMAGE_PREFIX:-}ghcr.io/${{ GITHUB_REPOSITORY_OWNER }}|${IMAGE_PREFIX:-}registry.cn-shenzhen.aliyuncs.com/zxyz/|g' "$DOCKER_COMPOSE" || true
    fi

    # 3. 更新 .env
    echo "[3/3] 更新 .env ..."
    if [ -f "$ENV_FILE" ]; then
        if grep -q "^IMAGE_PREFIX=" "$ENV_FILE"; then
            sed -i 's|^IMAGE_PREFIX=.*|IMAGE_PREFIX=registry.cn-shenzhen.aliyuncs.com/zxyz/|' "$ENV_FILE"
        else
            echo "IMAGE_PREFIX=registry.cn-shenzhen.aliyuncs.com/zxyz/" >> "$ENV_FILE"
        fi
    fi

    echo "===== 切换完成 ====="
    echo ""
    echo "请确认："
    echo "1. GitHub Secrets 已配置 ACR_USERNAME / ACR_PASSWORD"
    echo "2. 服务器已执行: docker login registry.cn-shenzhen.aliyuncs.com"

elif [ "$MODE" = "disable" ]; then
    echo "===== 切换回 GitHub GHCR ====="

    # 1. 更新 CI/CD workflow
    echo "[1/3] 更新 .github/workflows/ci-cd.yml ..."
    sed -i 's|registry: registry.cn-shenzhen.aliyuncs.com|registry: ghcr.io|g' "$CI_FILE"
    sed -i 's|registry.cn-shenzhen.aliyuncs.com/zxyz|ghcr.io/${{ github.repository_owner }}|g' "$CI_FILE"
    sed -i 's|IMAGE_PREFIX: registry.cn-shenzhen.aliyuncs.com/zxyz/|IMAGE_PREFIX: ghcr.io/${{ github.repository_owner }}/|g' "$CI_FILE" || true

    # 2. 更新 docker-compose.yml
    echo "[2/3] 更新 docker-compose.yml ..."
    if [ -f "$DOCKER_COMPOSE" ]; then
        sed -i 's|\${IMAGE_PREFIX:-}registry.cn-shenzhen.aliyuncs.com/zxyz/|\${IMAGE_PREFIX:-}ghcr.io/${{ GITHUB_REPOSITORY_OWNER }}|g' "$DOCKER_COMPOSE" || true
    fi

    # 3. 更新 .env
    echo "[3/3] 更新 .env ..."
    if [ -f "$ENV_FILE" ]; then
        if grep -q "^IMAGE_PREFIX=" "$ENV_FILE"; then
            sed -i 's|^IMAGE_PREFIX=.*|IMAGE_PREFIX=ghcr.io/${{ GITHUB_REPOSITORY_OWNER }}/|' "$ENV_FILE"
        else
            echo "IMAGE_PREFIX=ghcr.io/${{ GITHUB_REPOSITORY_OWNER }}/" >> "$ENV_FILE"
        fi
    fi

    echo "===== 切换完成 ====="
else
    echo "ERROR: 未知模式 '$MODE'，请使用 enable 或 disable"
    exit 1
fi
