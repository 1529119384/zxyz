#!/usr/bin/env bash
# ============================================================
# check-subrepo-sync.sh
# 校验 monorepo 根仓库与嵌套子仓库的提交内容是否一致。
# 用法: bash scripts/check-subrepo-sync.sh [--fix-hint]
#   --fix-hint  输出修复命令（默认开启）
# 退出码: 0=同步  1=不同步  2=环境错误
# ============================================================
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: 当前目录不在 git 仓库中" >&2
  exit 2
}
cd "$ROOT_DIR"

SUB_REPOS=("ZXYZdatabaseBack" "ZXYZdatabaseFront")
HAS_DRIFT=0
DRIFT_DETAILS=""

# 颜色（CI 中自动禁用）
if [ -t 1 ] && [ "${CI:-}" != "true" ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; NC=''
fi

echo "===== 子仓库同步一致性检查 ====="
echo "根仓库: $ROOT_DIR"
echo ""

for sub in "${SUB_REPOS[@]}"; do
  sub_path="$ROOT_DIR/$sub"

  # --- 前置检查 ---
  if [ ! -d "$sub_path/.git" ]; then
    echo -e "${YELLOW}[SKIP]${NC} $sub — 未找到 .git 目录，非独立子仓库，跳过"
    continue
  fi

  # --- 1. 子仓库未提交变更检查 ---
  sub_dirty_all=$(git -C "$sub_path" status --porcelain 2>/dev/null || true)
  if [ -n "$sub_dirty_all" ]; then
    HAS_DRIFT=1
    dirty_count=$(echo "$sub_dirty_all" | wc -l | tr -d ' ')
    dirty_preview=$(echo "$sub_dirty_all" | head -10 | sed 's/^/  /')
    DRIFT_DETAILS="${DRIFT_DETAILS}\n[${sub}] 子仓库有 ${dirty_count} 个未提交变更:"
    DRIFT_DETAILS="${DRIFT_DETAILS}\n${dirty_preview}"
    if [ "$dirty_count" -gt 10 ]; then
      DRIFT_DETAILS="${DRIFT_DETAILS}\n  ... 还有 $((dirty_count - 10)) 个文件"
    fi
  fi

  # --- 2. 内容一致性比较（blob hash 对比） ---
  # 根仓库中该目录的文件 hash
  root_tree=$(git ls-tree -r HEAD -- "$sub/" 2>/dev/null \
    | awk '{print $3, $4}' \
    | sed "s| $sub/| |" \
    | sort -k2 || true)

  # 子仓库自身 HEAD 的文件 hash
  sub_tree=$(git -C "$sub_path" ls-tree -r HEAD 2>/dev/null \
    | awk '{print $3, $4}' \
    | sort -k2 || true)

  if [ -z "$root_tree" ] && [ -z "$sub_tree" ]; then
    echo -e "${GREEN}[OK]${NC}   $sub — 双方均无跟踪文件"
    continue
  fi

  # 比较差异
  diff_output=$(diff <(echo "$root_tree") <(echo "$sub_tree") 2>/dev/null || true)

  if [ -z "$diff_output" ]; then
    echo -e "${GREEN}[OK]${NC}   $sub — 根仓库与子仓库内容一致"
  else
    HAS_DRIFT=1
    # 解析差异文件
    only_root=$(echo "$diff_output" | grep -c '^<' || true)
    only_sub=$(echo "$diff_output" | grep -c '^>' || true)
    DRIFT_DETAILS="${DRIFT_DETAILS}\n[${sub}] 内容不一致 (根仓库独有: ${only_root}, 子仓库独有/不同: ${only_sub}):"

    # 提取具体不同步文件（取前 15 个）
    drift_files=$(echo "$diff_output" | grep '^[<>]' | awk '{print $NF}' | sort -u | head -15 || true)
    DRIFT_DETAILS="${DRIFT_DETAILS}\n$(echo "$drift_files" | sed 's/^/  /')"

    total_drift=$(echo "$diff_output" | grep '^[<>]' | awk '{print $NF}' | sort -u | wc -l | tr -d ' ' || true)
    if [ "${total_drift:-0}" -gt 15 ]; then
      DRIFT_DETAILS="${DRIFT_DETAILS}\n  ... 还有 $((total_drift - 15)) 个文件"
    fi
  fi
done

echo ""

# --- 输出结果 ---
if [ "$HAS_DRIFT" -eq 0 ]; then
  echo -e "${GREEN}✓ 所有子仓库与根仓库同步一致${NC}"
  exit 0
else
  echo -e "${RED}✗ 检测到子仓库不同步！${NC}"
  echo ""
  echo "===== 不同步详情 ====="
  echo -e "$DRIFT_DETAILS"
  echo ""
  echo "===== 修复步骤 ====="
  echo "1. 进入子仓库提交变更:"
  echo "   cd ZXYZdatabaseBack && git add -A && git commit -m 'sync: <描述>'"
  echo "   cd ZXYZdatabaseFront && git add -A && git commit -m 'sync: <描述>'"
  echo ""
  echo "2. 回到根仓库同步提交:"
  echo "   cd $ROOT_DIR"
  echo "   git add ZXYZdatabaseBack/ ZXYZdatabaseFront/"
  echo "   git commit -m 'sync: 同步子仓库变更'"
  echo ""
  echo "3. 同时推送两个仓库:"
  echo "   git push origin HEAD"
  echo "   git -C ZXYZdatabaseBack push origin HEAD"
  echo "   git -C ZXYZdatabaseFront push origin HEAD"
  echo ""
  echo "::error::SUBREPO_SYNC_DRIFT: 子仓库与根仓库内容不一致，请先同步再推送"
  exit 1
fi
