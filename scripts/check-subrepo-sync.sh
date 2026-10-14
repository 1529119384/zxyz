#!/usr/bin/env bash
# ============================================================
# check-subrepo-sync.sh
# 校验 monorepo 根仓库与嵌套子仓库的提交内容是否一致（内容级判据）。
# 用法: bash scripts/check-subrepo-sync.sh [--fix-hint]
#   --fix-hint  历史参数，仅为兼容保留（无效果）
# 退出码: 0=同步（含幻影放行）  1=真实漂移（拦截 push）  2=环境错误
#
# 【判据模型 —— ISSUE/55 §二 P1-3 重写（2026-10-13），Lead 认可】
# 旧判据（重写前）：
#   1) `git status --porcelain` 非空 ⇒ 判漂移；
#   2) root ls-tree == sub ls-tree 不一致 ⇒ 判漂移。
# Windows(core.autocrlf=true) 检出产生数百行「CRLF/stat 幻影」（后端子仓
# 实测 238 行；内容剥 \r 后与 HEAD 逐字节一致，仅行尾/stat 不同），两判据
# 同时误红 ⇒ 33h 冻结只能 `--no-verify` 绕过 = 闸门被废。
#
# 新判据（fail-closed 语义不变）：
#   真实漂移 = 以下任一：
#   A) 子仓工作区/索引 vs 子仓 HEAD 存在内容级差异：
#      - 快速放行门（判据第 4 条）：「双 diff 全空」——
#        `git diff HEAD --numstat` 与 `--ignore-cr-at-eol --numstat` 均空
#        且 porcelain 无 untracked ⇒ porcelain 全为 stat/索引幻影，放行。
#        正确性：git diff 输出只反映内容（stat 脏但内容与 HEAD 一致的文件
#        根本不进 diff），故「双 diff 全空」严格蕴含「无被跟踪文件有内容
#        级差异」；untracked 不进 diff HEAD，必须单独排除，否则新文件漏网。
#      - 双 diff 任一非空（或 porcelain 含 untracked）⇒ 逐候选：先做「逐文件
#        --ignore-cr-at-eol numstat」预筛（git 内容引擎裁决，空=幻影），
#        非空再进「剥 \r 双端规范化哈希」仲裁终裁：工作区文件原始字节 vs
#        HEAD blob（`cat-file blob HEAD:path`），同一 tr -d '\r' +
#        git hash-object --stdin 管道，不一致 ⇒ 真漂移。
#        ⚠️ 工作区侧绝不能读 `git show :path`（索引）——未 stage 的纯工作区
#        改动里 index==HEAD，两侧相等会误判幻影放行（55 号仿真 b 实测漏放，
#        fail-open），必须读工作区文件原始字节。
#      ⚠️ `--name-only`/`--numstat` 的【文件名清单】在内容过滤之前生成：
#        纯幻影文件也会被列名（55 号实测：前端 314 个名字 / 逐文件
#        patch=0 字节），且 rename 形态为 `old => new`、特殊字符路径会被
#        引号改写——名字清单不可靠，因此本版不做名字匹配，porcelain(-z)
#        才是候选真源。已知极限：文件名内含制表符时 porcelain -z 与
#        diff HEAD 的路径同形，仲裁不受影响。
#   B) 根仓库跟踪树 ≠ 子仓 HEAD 树（子仓已提交、根仓未同步，双提交漏第 2 步）：
#      双侧 `git ls-tree -r` 剥前缀后按 mode+blob+path 三元组对比（2026-10-14 P2-5：
#      旧版只比 blob hash 丢 mode 位，chmod +x 这类 mode-only 变更假放行 ——
#      ad21124c6 backup.sh 可执行位丢失致每日备份全失败即该类别生产事故）。
#   untracked(??) 文件属未提交 WIP：不进任何 HEAD，无法「漏同步」，故不计
#   漂移仅提示（刻意不沿用旧版「porcelain 非空即拦」——那是 33h 冻结的成因
#   之一）；staged 纯 rename 由 A 维仲裁（R 记录取新路径）+ B 维兜底。
#
# 【跨平台】不硬编码路径分隔符（$ROOT_DIR/$sub 正斜杠拼接在 Windows git-bash
#   与 Linux CI 均有效）；所有 git 调用显式 `git -C`；哈希仲裁用
#   `git hash-object --stdin`，不依赖 sha256sum/cksum 等外部命令差异。
# 【SIGPIPE 纪律】大清单管道只用 awk（读完整输入），不用 head（ISSUE/51 P2-6）。
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

# 剥 \r 双端规范化哈希：两侧（工作区文件原始字节 与 HEAD blob 字节流）
# 走同一条 tr + hash-object 管道，哈希域一致才可比。
stripped_hash() {
  tr -d '\r' | git hash-object --stdin
}

# 哈希仲裁：$1=sub_path $2=相对路径。输出 0=一致（幻影） 1=不一致/任一侧
# 取不到值（fail-closed：删了被跟踪文件、路径消失等都按真漂移处理）。
# 工作区侧必须读文件原始字节（索引 git show :path 在「未 stage 的纯工作区
# 改动」下 == HEAD，两侧相等 = 幻影误判 = 真漂移漏放，勿改回）。
hash_arbitrate() {
  local h_work h_head
  if [ -f "$1/$2" ]; then
    h_work=$(stripped_hash < "$1/$2")
  else
    h_work=""
  fi
  h_head=$(git -C "$1" cat-file blob "HEAD:$2" 2>/dev/null | stripped_hash || true)
  if [ -z "$h_work" ] || [ -z "$h_head" ] || [ "$h_work" != "$h_head" ]; then
    return 1
  fi
  return 0
}

# 逐文件内容级预筛：该文件在 --ignore-cr-at-eol numstat 下非空 ⇒ 有真实
# 内容差异（git 内容引擎裁决，对纯 EOL 幻影文件为空）。空输出 ⇒ 幻影。
# 用 --no-optional-locks 避免触碰子仓 index 的 stat 缓存。
file_has_content_drift() {
  [ -n "$(git --no-optional-locks -C "$1" diff HEAD --ignore-cr-at-eol --numstat -- "$2" 2>/dev/null || true)" ]
}

echo "===== 子仓库同步一致性检查（内容级判据，CRLF/stat 幻影自动放行） ====="
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

  # --- 0. porcelain → 候选清单（v1 -z 字节安全：文件名不被引号/转义破坏；
  #        stderr 丢弃 —— 大量「CRLF will be replaced」warning 是纯噪音）---
  porcelain_total=0
  untracked_count=0
  prev_rename=0
  CANDIDATES=()
  while IFS= read -r -d '' entry; do
    if [ "$prev_rename" -eq 1 ]; then prev_rename=0; continue; fi
    # rename/copy 记录为「R  <new>\0<old>\0」：只取新路径作候选；旧路径已不在
    # 索引，若参与仲裁（git show :old 失败）会 fail-closed 误报真漂移。
    porcelain_total=$((porcelain_total + 1))
    xy="${entry:0:2}"
    path="${entry:3}"
    case "$xy" in
      *R*|*C*) prev_rename=1 ;;
      '??') untracked_count=$((untracked_count + 1)); continue ;;
    esac
    CANDIDATES+=("$path")
  done < <(git -C "$sub_path" status --porcelain=v1 -z 2>/dev/null || true)

  # --- 1. A 维：子仓工作区/索引 vs 子仓 HEAD 的内容级双 diff ---
  content_numstat=$(git -C "$sub_path" diff HEAD --ignore-cr-at-eol --numstat 2>/dev/null || true)
  raw_numstat=$(git -C "$sub_path" diff HEAD --numstat 2>/dev/null || true)

  GENUINE_A=()
  phantom_count=0

  if [ -z "$content_numstat" ] && [ -z "$raw_numstat" ] && [ "$untracked_count" -eq 0 ]; then
    # 快速放行门（判据第 4 条）：双 diff 全空 ⇒ porcelain 全为幻影，放行
    phantom_count=$porcelain_total
  else
    # 双 diff 任一非空（或有 untracked）⇒ 逐候选：先逐文件 numstat 预筛，
    # 非空再进哈希仲裁（绝大多数幻影文件在预筛即空，避免 234 次全量哈希）
    for f in ${CANDIDATES[@]+"${CANDIDATES[@]}"}; do
      if file_has_content_drift "$sub_path" "$f" || ! hash_arbitrate "$sub_path" "$f"; then
        GENUINE_A+=("$f")
      else
        phantom_count=$((phantom_count + 1))
      fi
    done
  fi
  genuine_count="${#GENUINE_A[@]}"

  # --- 2. B 维：根仓库跟踪树 vs 子仓 HEAD 树（子仓已提交、根仓未同步）---
  # awk -F'\t' 保留含空格完整路径；前缀剥离必须在 awk 内做（ls-tree 的路径在
  # TAB 后，sed 的 ^ 锚点匹配不到——55 号实测踩坑）。
  # key = mode\tblob\tpath（2026-10-14 P2-5）：旧版只取 m[3]（blob hash）丢 mode 位，
  # chmod +x 这类 mode-only 变更（两侧 blob 相同、mode 100644↔100755）假放行；mode 入 key
  # 后 B 维对 mode-only 变更同样非空，与内容变更一视同仁。
  root_tree=$(git ls-tree -r HEAD -- "$sub/" 2>/dev/null \
    | awk -F'\t' -v p="$sub/" '{ split($1, m, " "); pth=$2; sub("^" p, "", pth); print m[1] "\t" m[3] "\t" pth }' \
    | sort || true)
  sub_tree=$(git -C "$sub_path" ls-tree -r HEAD 2>/dev/null \
    | awk -F'\t' '{ split($1, m, " "); print m[1] "\t" m[3] "\t" $2 }' | sort || true)

  B_DIFF_FILES=""
  if [ -n "$root_tree" ] || [ -n "$sub_tree" ]; then
    b_diff=$(diff <(printf '%s' "$root_tree") <(printf '%s' "$sub_tree") 2>/dev/null || true)
    if [ -n "$b_diff" ]; then
      B_DIFF_FILES=$(printf '%s\n' "$b_diff" \
        | awk '/^[<>] / { sub(/^[<>] [0-9a-f]+\t[0-9a-f]+\t/, ""); if ($0 != "") print }' | sort -u)
    fi
  fi

  # --- 3. A 维小结输出 ---
  if [ "$genuine_count" -gt 0 ]; then
    HAS_DRIFT=1
    echo -e "${RED}[漂移]${NC}   $sub — ${genuine_count} 个文件与子仓 HEAD 存在真实内容差异（未提交）"
    if [ "$phantom_count" -gt 0 ]; then
      echo "           （另有 ${phantom_count} 行 porcelain 为 CRLF/stat 幻影，已自动放行）"
    fi
    DRIFT_DETAILS="${DRIFT_DETAILS}\n[${sub}] 真实漂移 ${genuine_count} 个文件（工作区 vs 子仓 HEAD，内容级）:"
    DRIFT_DETAILS="${DRIFT_DETAILS}\n$(printf '%s\n' ${GENUINE_A[@]+"${GENUINE_A[@]}"} \
      | awk '{ if (NR<=30) printf "  M %s\n", $0; c++ } END { if (c>30) printf "  ... 还有 %d 个文件\n", c-30 }')"
  elif [ "$phantom_count" -gt 0 ]; then
    echo -e "${YELLOW}[幻影放行]${NC} $sub — ${porcelain_total} 行 porcelain 为 CRLF/stat 幻影，内容级 diff 为空，放行"
  elif [ "$porcelain_total" -gt 0 ]; then
    echo -e "${YELLOW}[提示]${NC}   $sub — ${porcelain_total} 行 porcelain 全部为 untracked（未提交 WIP，不计漂移）"
  else
    echo -e "${GREEN}[OK]${NC}   $sub — 工作区与子仓 HEAD 内容一致"
  fi
  if [ "$untracked_count" -gt 0 ]; then
    echo "           （另有 ${untracked_count} 个 untracked 文件属未提交 WIP，不计漂移；提交时请 git add 一并处理）"
  fi

  # --- 4. B 维结果 ---
  if [ -n "$B_DIFF_FILES" ]; then
    HAS_DRIFT=1
    echo -e "${RED}[树漂移]${NC}  $sub — 根仓库跟踪内容与子仓 HEAD 不一致"
    DRIFT_DETAILS="${DRIFT_DETAILS}\n[${sub}] 根仓库跟踪树 ≠ 子仓 HEAD 树（子仓已提交但根仓未同步）:"
    DRIFT_DETAILS="${DRIFT_DETAILS}\n$(printf '%s\n' "$B_DIFF_FILES" \
      | awk '{ if (NR<=30) printf "  T %s\n", $0; c++ } END { if (c>30) printf "  ... 还有 %d 个文件\n", c-30 }')"
  fi
done

echo ""

# --- 输出结果 ---
if [ "$HAS_DRIFT" -eq 0 ]; then
  echo -e "${GREEN}✓ 所有子仓库与根仓库内容级同步一致（CRLF/stat 幻影不判漂移）${NC}"
  exit 0
else
  echo -e "${RED}✗ 检测到子仓库真实漂移（内容级判据）！${NC}"
  echo ""
  echo "===== 漂移详情 ====="
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
  echo "::error::SUBREPO_SYNC_DRIFT: 子仓库与根仓库存在真实内容级漂移，请先同步再推送"
  exit 1
fi
