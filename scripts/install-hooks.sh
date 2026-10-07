#!/usr/bin/env bash
# ============================================================
# install-hooks.sh
# 安装项目 Git 钩子：将根仓库 core.hooksPath 指向版本化的 .husky/ 目录。
# 用法: bash scripts/install-hooks.sh
# 卸载: git config --unset core.hooksPath
# ============================================================
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: 当前目录不在 git 仓库中" >&2
  exit 2
}
cd "$ROOT_DIR"

HOOKS_DIR=".husky"

if [ ! -d "$HOOKS_DIR" ]; then
  echo "ERROR: 未找到 $HOOKS_DIR 目录" >&2
  exit 2
fi

git config core.hooksPath "$HOOKS_DIR"

# 确保钩子可执行（Windows 检出可能丢失执行位）
find "$HOOKS_DIR" -maxdepth 1 -type f ! -name '*.*' -exec chmod +x {} + 2>/dev/null || true

echo "✓ core.hooksPath 已指向 $HOOKS_DIR"
echo "✓ 已安装钩子:"
ls -1 "$HOOKS_DIR" | grep -v '^_' | sed 's/^/  - /'
echo ""
echo "提示: 跳过校验可使用 git push --no-verify"
