#!/bin/bash
# =============================================================================
# ZXYZ 旧镜像清理脚本
#
# 作用：清理历次部署残留的 per-commit 镜像，防止服务器磁盘被逐步撑满。
#
# 背景（为什么需要它）：
#   build-and-push 每次为 11 个服务各推两个 tag（:dev 可变 + :<40位 sha> 不可变），
#   部署只消费不可变 sha tag，而**旧 tag 从不清理**。实测 11 个 ghcr 仓库各积压
#   11 个 tag（共 131 个镜像），/var/lib/containerd 达 33G，根分区使用率 93%，
#   而 `docker image prune`（不带 -a）因为 dangling=0 一点也回收不到。
#
# 策略（保守：绝不删“正在用”的）：
#   每个 ghcr.io/*/zxyz-* 仓库只保留最近的 KEEP 个 tag，其余删除。以下永不动：
#     1) 任何被容器引用的镜像（docker ps -a，含已停止容器）
#     2) .env / .env.previous 里 APP_IMAGE_TAG 指向的 tag —— 回滚要用
#   最后清理悬空层与超过 CACHE_DAYS 天的构建缓存。
#
# 用法:
#   ./scripts/prune-old-images.sh                # 保留最近 3 个 tag，构建缓存保留 7 天
#   ./scripts/prune-old-images.sh 5              # 保留最近 5 个 tag
#   ./scripts/prune-old-images.sh 5 14           # 保留 5 个 tag，缓存保留 14 天
#   ./scripts/prune-old-images.sh --dry-run      # 只打印将删除的内容，不做任何变更
#
# 环境变量:
#   DEPLOY_DIR    部署目录（默认 /www/zxyz），用于读 .env 与 .env.previous
#   IMAGE_PREFIX  镜像前缀（默认 ghcr.io/1529119384）
#
# 退出码：**恒为 0**。清理是维护动作，失败不应让一次成功的部署变红；
#         所有异常只打 WARN，由调用方决定是否上报。
#
# 为什么是「独立步骤」而不是塞进 deploy 脚本：
#   GitHub 对 workflow 中单个标量的长度上限是 21000 —— 单位是 **UTF-8 字节**。
#   ci-cd.yml 里 `Deploy via SSH` 的 script 实测已达 20860 字节（中文注释每字 3 字节），
#   只剩约 140 字节余量；超限会让整个 workflow 变成 "Invalid workflow file"
#   （run 以文件路径为名、0 个 job 直接失败，报错 "Exceeded max expression length 21000"）。
#   所以清理逻辑做成独立步骤，只在此脚本里演进。
#
# 核对字节预算（改完 ci-cd.yml 后务必跑一次，本地无需联网）:
#   python -c "import yaml;d=yaml.safe_load(open('.github/workflows/ci-cd.yml',encoding='utf-8'));\
#   s=[x for x in d['jobs']['deploy']['steps'] if x.get('name')=='Deploy via SSH'][0]['with']['script'];\
#   print(len(s.encode('utf-8')),'/21000')"
# =============================================================================

# 故意不用 `set -e`：单个镜像删不掉不应中断整轮清理。
set -uo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/www/zxyz}"
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/1529119384}"
KEEP=3
CACHE_DAYS=7
DRY_RUN=false

warn() { echo "WARN: $*" >&2; }
info() { echo "$*"; }

# --- 解析参数：位置参数依次是 KEEP、CACHE_DAYS；--dry-run 可放任意位置 ---
_num_seen=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=true
      ;;
    ''|*[!0-9]*)
      warn "忽略无法识别的参数 '$arg'（只接受数字或 --dry-run）"
      ;;
    *)
      _num_seen=$((_num_seen + 1))
      if [ "$_num_seen" -eq 1 ]; then
        KEEP="$arg"
      elif [ "$_num_seen" -eq 2 ]; then
        CACHE_DAYS="$arg"
      else
        warn "多余的数字参数 '$arg' 被忽略"
      fi
      ;;
  esac
done

if [ "$KEEP" -lt 1 ]; then
  warn "KEEP=$KEEP 不合理（至少要保留 1 个），回退为 3"
  KEEP=3
fi

# --- 前置检查：没有 docker 就干净退出，不制造噪音 ---
if ! command -v docker >/dev/null 2>&1; then
  warn "未找到 docker 命令，跳过清理"
  exit 0
fi
if ! docker info >/dev/null 2>&1; then
  warn "Docker 守护进程不可用，跳过清理"
  exit 0
fi

# --- 收集“受保护 tag”集合 ---
# 用关联数组：tag 是 40 位 commit sha，在所有 zxyz 仓库间同名，按 tag 保护即可。
declare -A PROTECTED_TAG=()

# 1) 容器引用的镜像（含已停止的：停止不等于可以删镜像）
while IFS= read -r img; do
  [ -n "$img" ] || continue
  PROTECTED_TAG["${img##*:}"]=1
done < <(docker ps -a --format '{{.Image}}' 2>/dev/null)

# 2) 回滚锚点：.env（当前）与 .env.previous（上一次），rollback.sh 靠它精确回滚
for f in "$DEPLOY_DIR/.env" "$DEPLOY_DIR/.env.previous"; do
  [ -f "$f" ] || continue
  t="$(sed -n 's/^APP_IMAGE_TAG=//p' "$f" 2>/dev/null | head -1 | tr -d '[:space:]')"
  if [ -n "$t" ]; then
    PROTECTED_TAG["$t"]=1
    info "保护回滚锚点 tag: $t（来自 ${f}）"
  fi
done

BEFORE_AVAIL="$(df -Pk / 2>/dev/null | awk 'NR==2{print $4}')"

# --- 逐仓库保留最近 KEEP 个 tag ---
mapfile -t REPOS < <(
  docker images --format '{{.Repository}}' 2>/dev/null \
    | awk -v p="${IMAGE_PREFIX}/" 'index($0, p) == 1 && index($0, "zxyz-") > 0' \
    | sort -u
)

if [ "${#REPOS[@]}" -eq 0 ]; then
  info "未发现 ${IMAGE_PREFIX}/x... 形式的 zxyz-* 镜像仓库，跳过 tag 清理"
fi

REMOVED=0
FAILED=0
for repo in "${REPOS[@]:-}"; do
  [ -n "$repo" ] || continue

  # 按 CreatedAt 倒序：CreatedAt 形如 "2026-09-11 08:12:34 +0000 UTC"，定宽，字符串排序即可
  mapfile -t ROWS < <(
    docker images --format '{{.Tag}}|{{.CreatedAt}}' "$repo" 2>/dev/null | sort -t'|' -k2,2r
  )

  idx=0
  for row in "${ROWS[@]:-}"; do
    [ -n "$row" ] || continue
    tag="${row%%|*}"
    idx=$((idx + 1))

    # 最近 KEEP 个：保留
    [ "$idx" -le "$KEEP" ] && continue

    # 受保护（容器在用 / 回滚锚点）：保留
    if [ -n "${PROTECTED_TAG[$tag]:-}" ]; then
      info "  keep(protected)  ${repo}:${tag}"
      continue
    fi

    if [ "$DRY_RUN" = true ]; then
      info "  would remove     ${repo}:${tag}"
      REMOVED=$((REMOVED + 1))
      continue
    fi

    if docker rmi "$repo:$tag" >/dev/null 2>&1; then
      info "  removed          ${repo}:${tag}"
      REMOVED=$((REMOVED + 1))
    else
      warn "  删除失败（可能仍被引用）: ${repo}:${tag}"
      FAILED=$((FAILED + 1))
    fi
  done
done

# --- 悬空层 + 构建缓存 ---
if [ "$DRY_RUN" = false ]; then
  if docker image prune -f >/dev/null 2>&1; then
    info "悬空镜像层已清理"
  else
    warn "悬空镜像层清理失败（不影响部署）"
  fi

  CACHE_UNTIL="$((CACHE_DAYS * 24))h"
  if docker builder prune -f --filter "until=${CACHE_UNTIL}" >/dev/null 2>&1; then
    info "构建缓存已清理（保留最近 ${CACHE_DAYS} 天的记录）"
  else
    warn "构建缓存清理失败（不影响部署）"
  fi
fi

# --- 结果汇报 ---
AFTER_AVAIL="$(df -Pk / 2>/dev/null | awk 'NR==2{print $4}')"
if [ -n "${BEFORE_AVAIL:-}" ] && [ -n "${AFTER_AVAIL:-}" ]; then
  FREED_MB=$(((AFTER_AVAIL - BEFORE_AVAIL) / 1024))
  info "清理完成：删除 ${REMOVED} 个 tag（失败 ${FAILED} 个）；根分区可用 ${BEFORE_AVAIL}KB -> ${AFTER_AVAIL}KB（+${FREED_MB}MB）"
else
  info "清理完成：删除 ${REMOVED} 个 tag（失败 ${FAILED} 个）"
fi

exit 0
