#!/usr/bin/env bash
# 本地静态验证：Alertmanager 渲染产物（三态）+ amtool 语法校验
#
# 2026-11-02（70 号事件后）：把「告警到底有没有出口」变成**本机可复现的检查**。
#
# 本脚本覆盖（确定性、不依赖端口/网络）：
#   1) 配 ALERT_WEBHOOK_URL        -> 渲染出 webhook_configs，amtool check-config 通过
#   2) 配 EMAIL_*（EMAIL_ENABLED） -> 渲染出 email_configs，amtool check-config 通过
#   3) 两者都不配                  -> 合法 no-op，amtool 通过，但**必须打印 ALERTMANAGER_NO_CHANNEL 警告**
#
# ⚠️ 「真投递」的端到端验证（真 alertmanager -> 真 webhook 收到 payload）已在本机手工执行并留证
#    （见 ISSUE/70 §四）：它需要端口映射与容器网络，不适合放进这个纯静态脚本。
#
# 依赖：bash + docker（prom/alertmanager 镜像，含 amtool）。不需要外部网络。
set -uo pipefail
# MSYS/Git-bash 会把 -v host:/container/path 的容器侧路径误转成 Windows 路径：
# 关闭该转换（Linux 上无此变量、无副作用）。
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
AM_IMAGE="${AM_IMAGE:-prom/alertmanager:v0.28.1}"
FAILED=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# docker -v 的宿主路径在 MSYS/Git-bash 下需转成 Windows 形式（-m 混合斜杠）
topath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
say() { printf '\n=== %s ===\n' "$1"; }
ok()  { printf '  ✅ %s\n' "$1"; }
bad() { printf '  ❌ %s\n' "$1"; FAILED=1; }

check_config() { # $1=yml
  local err
  if ! err="$(docker run --rm -v "$(topath "$1"):/etc/alertmanager/alertmanager.yml:ro" \
        --entrypoint amtool "$AM_IMAGE" check-config /etc/alertmanager/alertmanager.yml 2>&1)"; then
    printf '     [amtool] %s\n' "$(printf '%s' "$err" | tail -3 | tr '\n' ' ')"
    return 1
  fi
  return 0
}

say "1) webhook 渠道"
printf 'ALERT_WEBHOOK_URL=http://127.0.0.1:19999/alert\nEMAIL_ENABLED=false\n' > "$WORK/env_webhook"
bash "$REPO_ROOT/scripts/render-alertmanager.sh" "$WORK/env_webhook" "$WORK/am_webhook.yml" >/dev/null 2>&1
grep -q 'webhook_configs' "$WORK/am_webhook.yml" && ok "渲染出 webhook_configs" || bad "缺少 webhook_configs"
check_config "$WORK/am_webhook.yml" && ok "amtool check-config 通过" || bad "amtool check-config 失败"

say "2) 邮箱渠道"
printf 'EMAIL_ENABLED=true\nALERT_EMAIL_TO=ops@example.test\nEMAIL_HOST=smtp.example.test\nEMAIL_PORT=465\nEMAIL_USERNAME=u\nEMAIL_PASSWORD=p\nEMAIL_FROM=alert@example.test\n' > "$WORK/env_email"
bash "$REPO_ROOT/scripts/render-alertmanager.sh" "$WORK/env_email" "$WORK/am_email.yml" >/dev/null 2>&1
grep -q 'email_configs' "$WORK/am_email.yml" && ok "渲染出 email_configs" || bad "缺少 email_configs"
check_config "$WORK/am_email.yml" && ok "amtool check-config 通过" || bad "amtool check-config 失败"

say "3) 无渠道：合法 no-op + 必须响亮告警"
printf '# empty\nEMAIL_ENABLED=false\n' > "$WORK/env_empty"
RENDER_OUT="$(bash "$REPO_ROOT/scripts/render-alertmanager.sh" "$WORK/env_empty" "$WORK/am_empty.yml" 2>&1)"
check_config "$WORK/am_empty.yml" && ok "amtool check-config 通过（仍是合法配置）" || bad "空渠道配置非法"
printf '%s' "$RENDER_OUT" | grep -q 'ALERTMANAGER_NO_CHANNEL' \
  && ok "渲染时打印 ALERTMANAGER_NO_CHANNEL 警告（不再伪装成 OK）" \
  || bad "缺少 ALERTMANAGER_NO_CHANNEL 警告"
grep -Eq '^[[:space:]]+(webhook_configs|email_configs):' "$WORK/am_empty.yml" \
  && bad "空渠道竟渲染出投递配置" || ok "空渠道确无任何投递配置（告警会被静默丢弃，符合预期语义）"

say "结果"
if [ "$FAILED" -eq 0 ]; then echo "ALL PASS"; exit 0; else echo "FAILED（见上方 ❌）"; exit 1; fi
