#!/bin/bash
set -euo pipefail
# 用法: ./import.sh <namespace-id> [nacos-addr] [nacos-user] [nacos-pass]
# 环境变量也可: NACOS_USER, NACOS_PASS
NAMESPACE=${1:-""}
NACOS=${2:-"localhost:8848"}
NACOS_USER=${3:-${NACOS_USER:-"nacos"}}
NACOS_PASS=${4:-${NACOS_PASS:-"nacos"}}

# 检查 Nacos 是否可达
echo "检查 Nacos 连接: http://${NACOS}/nacos/v1/cs/configs?dataId=healthcheck&group=HEALTH"
if ! curl -sf --retry 3 --retry-delay 2 "http://${NACOS}/nacos/v1/cs/configs?dataId=healthcheck&group=HEALTH&tenant=${NAMESPACE}&username=${NACOS_USER}&password=${NACOS_PASS}" > /dev/null 2>&1; then
  echo "✗ Nacos 不可达，请检查地址和鉴权配置"
  exit 1
fi
echo "✓ Nacos 连接正常"

success=0; fail=0
for file in *.yml; do
  [ -f "$file" ] || continue
  dataId=$(basename "$file")
  echo "导入 ${dataId} → namespace=${NAMESPACE} ..."
  # --data-urlencode 自动处理 YAML 中的 &、# 等特殊字符
  # Nacos 2.x 默认开启鉴权，需传 username/password
  # Nacos 配置 API 幂等：重复导入会覆盖已有配置
  resp=$(curl -s --retry 3 --retry-delay 1 -w "\n%{http_code}" -X POST "http://${NACOS}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "group=ZXYZ" \
    --data-urlencode "content@${file}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "tenant=${NAMESPACE}" \
    --data-urlencode "username=${NACOS_USER}" \
    --data-urlencode "password=${NACOS_PASS}")
  http_code=$(echo "$resp" | tail -1)
  if [ "$http_code" = "200" ]; then
    echo "  ✓ 成功"; ((success++))
  else
    echo "  ✗ 失败 (HTTP ${http_code})"; ((fail++))
  fi
done
echo "导入完成: ${success} 成功, ${fail} 失败"
[ "$fail" -eq 0 ] || exit 1
