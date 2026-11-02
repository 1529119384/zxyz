#!/usr/bin/env bash
# 66 号二.8：数据库口令轮换留痕执行器（一次维护窗口闭环历史口令暴露）。
#
# 背景：gitleaks 豁免注释确认提交 a43f5c6 曾把真实 DB 口令写入仓库（脚本已删除），
# 该口令必须视为已泄露并轮换。本脚本在服务器上以幂等步骤完成轮换 + 全程留痕。
#
# ⚠️ 使用前提与顺序（务必通读后再执行）：
#   1. 本脚本在**服务器**上运行（需 docker + /www/zxyz/.env）；
#   2. 执行前停止所有依赖 DB 口令的服务（compose stop 或接受自动重连抖动）；
#   3. 执行后**必须**同步更新 /www/zxyz/.env 的对应 MYSQL_*_PASSWORD 并 `docker compose up -d`
#      重建（restart 不重载 env），否则服务用旧口令全部 401；
#   4. 新口令只在运行时写入临时文件（600 权限），务必当场落进 .env 后删除。
#
# 用法：
#   DRY_RUN=1 bash rotate-db-passwords.sh          # 只打印将轮换的账户，不连库
#   bash rotate-db-passwords.sh                     # 实际执行（新口令写入 $NEW_PW_FILE）
#   NEW_PW_FILE=/root/newpw.txt bash rotate-db-passwords.sh
#
# 退出码：0 全部成功；1 有账户轮换失败（已成功的不回滚，日志有明细）；2 前置条件不满足。
set -uo pipefail

DEPLOY_DIR="${DEPLOY_PATH:-/www/zxyz}"
ENV_FILE="$DEPLOY_DIR/.env"
LOG="$DEPLOY_DIR/deploy-last.log"
DRY_RUN="${DRY_RUN:-0}"
NEW_PW_FILE="${NEW_PW_FILE:-$DEPLOY_DIR/.rotation-$(date -u +%Y%m%dT%H%M%SZ).txt}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-zxyz-mysql}"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"; }
die() { log "FATAL: $*" >&2; exit 2; }

# 生成新口令：32 位十六进制（无特殊字符，避免 shell/SQL 转义问题）
gen_pw() { openssl rand -hex 16; }

# 从 .env 读取一个值（取第一处 key=）
read_env() {
  local key="$1"
  [ -r "$ENV_FILE" ] || return 1
  sed -nE "s/^${key}=//p" "$ENV_FILE" | head -1
}

# 执行 SQL；root 口令经 MYSQL_PWD 传入（不出现在命令行/ps）
mysql_exec() {
  local pw="$1"; shift
  docker exec -e MYSQL_PWD="$pw" "$MYSQL_CONTAINER" mysql -uroot -e "$@"
}

if [ "$DRY_RUN" != "1" ]; then
  [ -r "$ENV_FILE" ] || die "读不到 $ENV_FILE（需要 MYSQL_ROOT_PASSWORD 作为当前口令）"
fi
CURRENT_ROOT_PW="$(read_env MYSQL_ROOT_PASSWORD || true)"
if [ "$DRY_RUN" != "1" ] && [ -z "${CURRENT_ROOT_PW:-}" ]; then
  die "$ENV_FILE 中 MYSQL_ROOT_PASSWORD 为空 —— 无法以 root 身份执行轮换"
fi

FAILED=0
# 轮换一个账户；成功后把新口令写入 $NEW_PW_FILE（600），deploy 日志只留事实不留明文
rotate_user() {
  local user="$1" host="$2" oldpw="$3" newpw="$4"
  if [ "$DRY_RUN" = "1" ]; then
    echo "  [dry-run] ALTER USER '${user}'@'${host}' IDENTIFIED BY '***'"
    return 0
  fi
  if mysql_exec "$oldpw" "ALTER USER '${user}'@'${host}' IDENTIFIED BY '${newpw}'; FLUSH PRIVILEGES;" >/dev/null 2>&1; then
    printf 'rotated: %s@%s ok date=%s\n' "$user" "$host" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$LOG"
    printf '%s@%s %s\n' "$user" "$host" "$newpw" >> "$NEW_PW_FILE"
    log "已轮换 ${user}@${host}（新口令已写入 $NEW_PW_FILE）"
  else
    printf 'rotated: %s@%s FAILED date=%s\n' "$user" "$host" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$LOG"
    log "ERROR: 轮换 ${user}@${host} 失败" >&2
    FAILED=1
  fi
  return 0
}

# --- 1) 业务账户（用当前 root 口令）---
# 名单与 grant-least-privilege.sql 的授权账户对齐；host 通配 '%' 与授权脚本一致。
SVC_USERS=(zxyz_project zxyz_im zxyz_email zxyz_user zxyz_share zxyz_file zxyz_team zxyz_audit zxyz_admin)
if [ "$DRY_RUN" != "1" ]; then
  umask 177
  : > "$NEW_PW_FILE" || die "无法创建 $NEW_PW_FILE"
  chmod 600 "$NEW_PW_FILE" 2>/dev/null || true
fi
for svc in "${SVC_USERS[@]}"; do
  rotate_user "$svc" '%' "$CURRENT_ROOT_PW" "$(gen_pw)"
done

# --- 2) root 最后轮换（轮换后当前口令失效，故放最后）---
NEW_ROOT_PW="$(gen_pw)"
rotate_user 'root' '%' "$CURRENT_ROOT_PW" "$NEW_ROOT_PW"
if [ "$DRY_RUN" != "1" ] && [ -s "$NEW_PW_FILE" ]; then
  if mysql_exec "$NEW_ROOT_PW" "SELECT 1;" >/dev/null 2>&1; then
    log "root 新口令自检通过（SELECT 1 可用）"
  else
    log "ERROR: root 新口令自检失败 —— 请立即回滚：ALTER USER 'root'@'%' IDENTIFIED BY '<旧口令>';" >&2
    FAILED=1
  fi
fi

if [ "$DRY_RUN" != "1" ]; then
  log "轮换结束 FAILED=$FAILED。⚠️ 必须：1) 把 $NEW_PW_FILE 里的新口令写入 .env 的 MYSQL_*_PASSWORD；2) docker compose up -d 重建全部后端服务；3) 验证 healthy；4) 确认后删除 $NEW_PW_FILE。"
fi
[ "$FAILED" -eq 0 ] || exit 1
exit 0
