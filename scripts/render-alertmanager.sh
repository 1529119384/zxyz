#!/usr/bin/env bash
#
# scripts/render-alertmanager.sh
# ---------------------------------------------------------------------------
# 渲染 ZXYZ Alertmanager 配置（缺陷 U8）：
#   - 读取 .env 中的 ALERT_WEBHOOK_URL / ALERT_EMAIL_TO(+EMAIL_*) 投递渠道
#   - 将 deploy/alertmanager/alertmanager.yml.tmpl 渲染为最终的 alertmanager.yml
#   - 两者皆未配置时渲染为「静默丢弃」的合法 no-op receiver，确保 Alertmanager 仍可启动
#
# 设计要点：
#   - Alertmanager 自身不展开环境变量，故必须在部署时渲染。
#   - 输出文件写到 $DEPLOY_DIR/deploy/alertmanager/alertmanager.yml（容器挂载点），
#     不写入 $REPO_DIR（git clone 工作区），因此不会触发 CI 的仓库漂移检测。
#   - 仅依赖 POSIX shell + 标准命令，无额外依赖。
#
# 用法：
#   bash scripts/render-alertmanager.sh [ENV_FILE] [OUT_FILE] [TMPL_FILE]
#   参数缺省时分别从 $DEPLOY_DIR/.env、同目录推导的输出/模板路径取。
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE="${1:-${DEPLOY_DIR:-}/.env}"
OUT_FILE="${2:-${DEPLOY_DIR:-}/deploy/alertmanager/alertmanager.yml}"
TMPL_FILE="${3:-$SCRIPT_DIR/../deploy/alertmanager/alertmanager.yml.tmpl}"

if [ ! -f "$TMPL_FILE" ]; then
  echo "::error::RENDER_ALERTMANAGER: 模板不存在: $TMPL_FILE" >&2
  exit 1
fi
if [ ! -f "$ENV_FILE" ]; then
  echo "::warning::RENDER_ALERTMANAGER: .env 不存在 ($ENV_FILE)，按未配置渠道渲染（no-op）" >&2
  ENV_FILE="/dev/null"
fi

# 从 .env 读取单个变量（避免 source 可能带入的特殊字符/副作用）
get_env() {
  local key="$1" def="${2:-}"
  local v=""
  v="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | head -1 | sed "s/^${key}=//")"
  [ -z "$v" ] && v="$def"
  # 去掉首尾可能的单/双引号
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

WEBHOOK="$(get_env ALERT_WEBHOOK_URL)"
EMAIL_TO="$(get_env ALERT_EMAIL_TO)"
EMAIL_ENABLED="$(get_env EMAIL_ENABLED false)"
EMAIL_HOST="$(get_env EMAIL_HOST)"
EMAIL_PORT="$(get_env EMAIL_PORT)"
EMAIL_USER="$(get_env EMAIL_USERNAME)"
EMAIL_PASS="$(get_env EMAIL_PASSWORD)"
EMAIL_FROM="$(get_env EMAIL_FROM)"

# 拼接 receiver 下的配置块（4 空格缩进，与模板 `- name: 'default'` 对齐）
CFG=""
if [ -n "$WEBHOOK" ]; then
  CFG="${CFG}    webhook_configs:
      - url: '${WEBHOOK}'
        send_resolved: true
"
fi
if [ "$EMAIL_ENABLED" = "true" ] && [ -n "$EMAIL_TO" ] && [ -n "$EMAIL_HOST" ] && [ -n "$EMAIL_PORT" ] && [ -n "$EMAIL_USER" ]; then
  CFG="${CFG}    email_configs:
      - to: '${EMAIL_TO}'
        from: '${EMAIL_FROM}'
        smarthost: '${EMAIL_HOST}:${EMAIL_PORT}'
        auth_username: '${EMAIL_USER}'
        auth_password: '${EMAIL_PASS}'
        require_tls: true
        send_resolved: true
"
fi

if [ -z "$CFG" ]; then
  # 优雅降级：无投递渠道时告警合法路由但静默丢弃，监控不中断
  CFG="    # 未配置任何告警投递渠道：告警路由合法但静默丢弃（监控不中断）。
    # 配置 ALERT_WEBHOOK_URL 或 ALERT_EMAIL_TO(且 EMAIL_ENABLED=true) 后重新渲染生效。"
fi

mkdir -p "$(dirname "$OUT_FILE")"
# 先写静态骨架，再追加动态配置块（避免多行 sed 替换的脆弱性）
cat "$TMPL_FILE" > "$OUT_FILE"
printf '%s\n' "$CFG" >> "$OUT_FILE"

echo "RENDER_ALERTMANAGER_OK: 已渲染 -> $OUT_FILE" >&2
exit 0
