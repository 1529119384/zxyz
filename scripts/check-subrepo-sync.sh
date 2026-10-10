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

  # --- 前置检查（fail-closed，ISSUE/51 P2-4）---
  # 为什么不能 [SKIP]+continue：SUB_REPOS 是硬编码的受管子仓库清单，目录存在而 .git 缺失
  # = 环境残缺/被破坏（2026-10-09 后端子仓 .git 残缺的真实事故），不是「非独立子仓库」。
  # 若在此跳过，下面所有校验对本子仓库失明，脚本会走到末尾照常打印
  # 「✓ 所有子仓库与根仓库同步一致」exit 0 —— 检测器静默失效开放，
  # 双提交纪律的最后闸门空转（昨日 P1-3 的根因正是它静默放行）。
  # 「目录存在但 .git 缺失」推导不出「无需检查」，只能硬失败让维护者修环境
  # （退出码语义见文件头：2=环境错误）。
  if [ ! -d "$sub_path/.git" ]; then
    echo -e "${RED}✗ $sub — 子仓库目录存在但 .git 缺失（环境错误，fail-closed）${NC}" >&2
    echo "  期望: $sub_path/.git" >&2
    echo "  不允许跳过：跳过 = 同步校验静默失效 + 末尾假「✓」放行（ISSUE/49 P1-3 / ISSUE/51 P2-4 的根因）。" >&2
    echo "  处置二选一：" >&2
    echo "    a) 该目录仍是受管子仓库 → 从远程重建 .git（git init && git remote add origin <url> && git fetch && git checkout <分支>）" >&2
    echo "    b) 该目录已废弃 → 同步从本脚本 SUB_REPOS 与根仓库跟踪中移除，再重跑本脚本" >&2
    exit 2
  fi

  # --- 1. 子仓库未提交变更检查 ---
  sub_dirty_all=$(git -C "$sub_path" status --porcelain 2>/dev/null || true)
  if [ -n "$sub_dirty_all" ]; then
    HAS_DRIFT=1
    dirty_count=$(printf '%s\n' "$sub_dirty_all" | wc -l | tr -d ' ')
    # ⚠️ 不用 `head -N`（ISSUE/51 P2-6）：head 取够 N 行就提前退出，上游写端在
    # 大清单（后端脏清单曾实测 1014 行 / 84978 字节 > 64KiB 管道缓冲）上收到
    # SIGPIPE → 管道退出码 141 → set -euo pipefail 直接静默中止整个脚本，
    # 漂移详情一个字都打不出来。改用 awk 'NR<=N'：读完全部输入再结束，
    # 不提前关管道 = 无 SIGPIPE，输出与 head 完全一致。
    dirty_preview=$(printf '%s\n' "$sub_dirty_all" | awk 'NR<=10 {print "  " $0}')
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
    # 以下管道同 P2-6 注释：全部用 awk 读完整输入（grep -c/awk/wc 都不提前退出），
    # 不用 head —— 大差异清单下 head 会触发 SIGPIPE → 141 → pipefail 静默中止。
    only_root=$(printf '%s\n' "$diff_output" | awk '$1=="<" {c++} END {print c+0}')
    only_sub=$(printf '%s\n' "$diff_output" | awk '$1==">" {c++} END {print c+0}')
    DRIFT_DETAILS="${DRIFT_DETAILS}\n[${sub}] 内容不一致 (根仓库独有: ${only_root}, 子仓库独有/不同: ${only_sub}):"

    # 提取具体不同步文件（取前 15 个）
    drift_files=$(printf '%s\n' "$diff_output" | awk '$1=="<" || $1==">" {print $NF}' | sort -u | awk 'NR<=15')

    DRIFT_DETAILS="${DRIFT_DETAILS}\n$(printf '%s\n' "$drift_files" | sed 's/^/  /')"

    total_drift=$(printf '%s\n' "$diff_output" | awk '$1=="<" || $1==">" {print $NF}' | sort -u | wc -l | tr -d ' ')
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
