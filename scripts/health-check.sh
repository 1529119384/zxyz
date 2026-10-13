#!/bin/bash
# ZXYZ 容器健康检查脚本
# 用法: ./scripts/health-check.sh

set -euo pipefail

# 清单 = docker-compose.yml 里**定义了 healthcheck:** 的 15 个容器：
#   基础设施 4（mysql/redis/nacos/rabbitmq）
#   + 后端 9（project/im/email/user/share/file/team/audit/admin）+ gateway 1 + frontend-nginx 1 = 15
#
# ⚠️ 2026-10-09 修复（审计 P2-2/P2-6）：数组原为 **14 项、漏 `zxyz-admin-service`**。
#   compose 里定义了 healthcheck 的服务共 15 个（脚本解析实测），漏掉 admin 的后果是：
#   生产上跑本脚本显示「14/14 全绿」，而 **admin-service 从未被检查** ——
#   典型的**假绿灯**（本仓反复吃亏的形态）。已补入，插在 team-service 与 gateway 之间保持分组顺序。
#
# ⚠️ 2026-10-03 修复（审计 I-2）：原数组里还有 zxyz-loki / zxyz-promtail 两条**陈旧条目**。
#   这两个服务已于 2026-09-18 迁入 docker-compose.observability.yml（profiles:["observability"]，
#   默认不启动、生产上从未被创建）⇒ `docker inspect` 恒失败 ⇒ `unhealthy` 常驻 ≥2
#   ⇒ 即使下面的计数器修好，脚本也**恒 exit 1**。已删除。
#
# ⚠️ 2026-10-03 修复（审计 I-1）：计数器原写作 `((healthy++))` / `((unhealthy++))`。
#   在 `set -e` 下，算术命令的**退出码 = 表达式的值**；首次 0→1 自增时表达式值为 0
#   ⇒ 返回非零 ⇒ `set -e` 立即终止脚本。实测：脚本在**第一个**服务之后就静默退出，
#   只输出 1 行、恒 exit 1（cron/告警消费方拿到的是「全挂」假信号）。
#   改为 `x=$((x + 1))`：赋值语句的退出码恒为 0，与 `set -e` 兼容。
services=(
  zxyz-mysql
  zxyz-redis
  zxyz-nacos
  zxyz-rabbitmq
  zxyz-project-service
  zxyz-im-service
  zxyz-email-service
  zxyz-user-service
  zxyz-share-service
  zxyz-file-service
  zxyz-team-service
  zxyz-audit-service
  zxyz-admin-service
  zxyz-gateway
  zxyz-frontend-nginx
)

healthy=0
unhealthy=0

for name in "${services[@]}"; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "not found")
  if [ "$status" = "healthy" ]; then
    echo "  $name"
    healthy=$((healthy + 1))
  else
    echo "  $name ($status)"
    unhealthy=$((unhealthy + 1))
  fi
done

echo ""
# 自打印检查条数（AU8 建议）：让文档不必写死「14/15 个容器」这个数字，
# 也让人一眼看出脚本**声称**查了几个 —— 若它与 compose 的 healthcheck 服务数不符，
# 差额就是「未被检查的容器」（本次 P2-2 假绿灯正是这么来的）。
echo "Checked: ${#services[@]} containers (清单为硬编码，须与 docker-compose.yml 的 healthcheck: 服务数一致)"
echo "Healthy: $healthy / $((healthy + unhealthy))"
if [ "$unhealthy" -gt 0 ]; then
  echo "Unhealthy: $unhealthy"
  exit 1
fi

# ---------------------------------------------------------------------------
# 跨跳探测（ISSUE/55 P0 事故收口，2026-10-13）：容器全 healthy ≠ 链路通。
# 55 号事故：nginx→gateway 跨跳断掉（DNS 缓存旧 IP）12h，16/16 healthy 全绿无人知晓。
# 经 frontend-nginx 自身 proxy 链打 /api/：未登录 401 = 网关活证；只拒 5xx/000
# （curl -sf 无法区分 401=通 与 502=断，故取码判断）。
# ---------------------------------------------------------------------------
echo "----- 跨跳探测（nginx → gateway，55 号事故防线）-----"
CROSS_CODE=$(docker exec zxyz-frontend-nginx sh -c "curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://localhost/api/" 2>/dev/null)
if [ -z "$CROSS_CODE" ]; then CROSS_CODE="000"; fi
echo "跨跳探测 HTTP 状态: $CROSS_CODE"
case "$CROSS_CODE" in
  5*|000)
    echo "ERROR: 跨跳探测失败（HTTP $CROSS_CODE）——nginx→gateway 链路断裂（55 号事故形态），即使容器全 healthy 也判失败"
    exit 1
    ;;
  *)
    echo "跨跳探测 OK（HTTP $CROSS_CODE = 网关存活/正常拒绝）"
    ;;
esac