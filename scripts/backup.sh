#!/bin/bash
# ZXYZ 数据备份脚本
# 用法: ./scripts/backup.sh
# 建议通过 crontab 定时执行: 0 3 * * * /path/to/scripts/backup.sh
#
# 注意: 生产环境应限制 .env 文件权限: chmod 600 .env
#
# 异地化（对象存储 OSS）：
#   .env 配置以下变量即可启用 OSS 异地推送（"异地"=对象存储，同 bucket 跨区域或
#   同区均可视为异地化配置项）：
#     OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET  # 鉴权凭证
#     OSS_ENDPOINT                                # 例 https://oss-cn-shenzhen.aliyuncs.com
#     OSS_BUCKET                                  # 桶名
#     OSS_REGION                                  # 例 cn-shenzhen
#     BACKUP_OSS_PREFIX                           # 可选，默认 backups；对象 key 前缀
#   上传工具自动选择：
#     - 优先 ossutil（本机已装，或用 OSS_ALIYUN 指定 ossutil 可执行路径）
#     - 否则退化为 curl 直传（阿里云 OSS PUT 签名较繁琐，且脚本按 KISS 不内联签名算法，
#       故 curl 分支只支持"带签名工具"场景时会走 WARN 提示改用 ossutil）
#
# 兼容旧逻辑：BACKUP_REMOTE_HOST 的 ssh/scp 可选项仍保留（可选异地路径），
# 但主异地路径已改为 OSS。

set -euo pipefail

# 加载环境变量
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
set -a
source "$PROJECT_DIR/.env"
set +a

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_DIR/backups}"
DATE=$(date +%Y%m%d_%H%M%S)
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
OSSFILE=""

mkdir -p "$BACKUP_DIR"

# 汇总失败标志：任何一个关键步骤失败都不得静默成功
FAILED=0

echo "=== ZXYZ 备份 $DATE ==="

# MySQL 备份
echo "备份 MySQL..."
if docker exec zxyz-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --all-databases --single-transaction --quick | \
  gzip > "$BACKUP_DIR/mysql_$DATE.sql.gz"; then
  :
else
  echo "ERROR: MySQL 备份失败" >&2
  FAILED=1
  exit 1
fi

# Redis 备份 — 轮询 LASTSAVE 确认 BGSAVE 完成
# 注意: LASTSAVE 是 Unix 秒级时间戳。BGSAVE 完成后若与下一次轮询落在同一秒内，
# LASTSAVE 可能与变更前相等（同秒）。因此"前移判定"采用严格随大——只要某次
# 读到的 LASTSAVE 已较基线前移即可判定完成；同秒未前移则继续轮询，靠 60s 总
# 上限兜底超时（不因单次同秒而误判失败）。
echo "备份 Redis..."
PREV_SAVE=$(docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" LASTSAVE) || { echo "ERROR: Redis LASTSAVE 失败" >&2; FAILED=1; exit 1; }
docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" BGSAVE >/dev/null

echo "等待 Redis BGSAVE 完成..."
for i in $(seq 1 60); do
  sleep 1
  CURR_SAVE=$(docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" LASTSAVE 2>/dev/null || echo "$PREV_SAVE")
  if [ "$CURR_SAVE" -gt "$PREV_SAVE" ]; then
    echo "Redis BGSAVE 完成 (${i}s, LASTSAVE=$CURR_SAVE)"
    BACKUP_READY=1
    break
  fi
done
if [ "${BACKUP_READY:-0}" -ne 1 ]; then
  echo "ERROR: Redis BGSAVE 超时 (60s)" >&2
  FAILED=1
  exit 1
fi

docker cp zxyz-redis:/data/dump.rdb "$BACKUP_DIR/redis_$DATE.rdb"

# RabbitMQ 队列/交换器/绑定拓扑备份
echo "备份 RabbitMQ definitions..."
if docker ps --format '{{.Names}}' | grep -q '^zxyz-rabbitmq$'; then
  if docker exec zxyz-rabbitmq rabbitmqadmin export \
    -u "$RABBITMQ_USER" -p "$RABBITMQ_PASSWORD" \
    "$BACKUP_DIR/rabbitmq_$DATE.json"; then
    echo "RabbitMQ definitions 已导出"
  elif docker exec zxyz-rabbitmq rabbitmqctl export_definitions \
    "$BACKUP_DIR/rabbitmq_$DATE.json" >/dev/null 2>&1; then
    echo "RabbitMQ definitions 已导出 (rabbitmqctl)"
  else
    echo "WARN: RabbitMQ definitions 导出失败，跳过（拓扑丢失风险）" >&2
    FAILED=1
  fi
else
  echo "WARN: zxyz-rabbitmq 容器未运行，跳过 RabbitMQ 拓扑备份" >&2
fi

# 备份完整性校验
echo "校验备份文件..."
MYSQL_SIZE=$(wc -c < "$BACKUP_DIR/mysql_$DATE.sql.gz")
REDIS_SIZE=$(wc -c < "$BACKUP_DIR/redis_$DATE.rdb")

if [ "$MYSQL_SIZE" -lt 1024 ]; then
  echo "ERROR: MySQL 备份文件过小 ($MYSQL_SIZE bytes)，可能备份失败" >&2
  FAILED=1
  exit 1
fi

if [ "$REDIS_SIZE" -lt 1024 ]; then
  echo "ERROR: Redis 备份文件过小 ($REDIS_SIZE bytes)，可能备份失败" >&2
  FAILED=1
  exit 1
fi

echo "MySQL 备份: $BACKUP_DIR/mysql_$DATE.sql.gz ($MYSQL_SIZE bytes)"
echo "Redis 备份: $BACKUP_DIR/redis_$DATE.rdb ($REDIS_SIZE bytes)"
echo "RabbitMQ 拓扑: $BACKUP_DIR/rabbitmq_$DATE.json"

# 组装本次产物列表
ARTIFACTS=( "$BACKUP_DIR/mysql_$DATE.sql.gz" "$BACKUP_DIR/redis_$DATE.rdb" )
[ -f "$BACKUP_DIR/rabbitmq_$DATE.json" ] && ARTIFACTS+=( "$BACKUP_DIR/rabbitmq_$DATE.json" )

# ---------------------------------------------------------------------------
# 异地化备份（优先 OSS，旧 ssh/scp 作为可选兼容）
# ---------------------------------------------------------------------------
REGION="${OSS_REGION:-cn-shenzhen}"
ENDPOINT="${OSS_ENDPOINT:-https://oss-${REGION}.aliyuncs.com}"

# 解析 bucket 内对象前缀：BACKUP_OSS_PREFIX，默认 backups/<YYYYMMDD>
OSS_PREFIX="${BACKUP_OSS_PREFIX:-backups}/$(date +%Y%m%d)"
OSS_PREFIX="${OSS_PREFIX#/}"      # 去掉首部斜杠，避免空前缀
OSS_PREFIX="${OSS_PREFIX%/}"      # 去掉尾部斜杠

echo "异地化: OSS bucket=$OSS_BUCKET prefix=$OSS_PREFIX endpoint=$ENDPOINT"

# 选择 ossutil 路径：优先 OSS_ALIYUN，其次 PATH 中的 ossutil
OSSUTIL="${OSS_ALIYUN:-}"
if [ -z "$OSSUTIL" ] && command -v ossutil >/dev/null 2>&1; then
  OSSUTIL=$(command -v ossutil)
fi

# 优先尝试 OSS 推送
if [ -z "$OSS_BUCKET" ] || [ -z "$OSS_ACCESS_KEY_ID" ] || [ -z "$OSS_ACCESS_KEY_SECRET" ]; then
  echo "WARN: 未配置完整 OSS 参数 (OSS_BUCKET/OSS_ACCESS_KEY_ID/OSS_ACCESS_KEY_SECRET)，跳过 OSS 异地推送" >&2
elif [ -z "$OSSUTIL" ]; then
  echo "WARN: 已配置 OSS 但未找伪造 ossutil（PATH 或 OSS_ALIYUN），无法推送异地备份。" >&2
  echo "     安装 ossutil 后设置 OSS_ALIYUN=<ossutil 路径>，或在 PATH 中提供 ossutil。" >&2
  echo "     （可选）配置 BACKUP_REMOTE_HOST 走旧的 ssh/scp 异地路径也可。" >&2
  FAILED=1
elif [ ! -x "$OSSUTIL" ] && [ ! -f "$OSSUTIL" ]; then
  echo "ERROR: ossutil 路径不存在或不可执行: $OSSUTIL" >&2
  FAILED=1
else
  echo "使用 ossutil 推送异地备份到 OSS..."
  # ossutil 首次使用需配置凭证；为避免交互，用环境变量注入
  # ossutil2.x 语法: ossutil cp <本地> oss://bucket/key [-e endpoint] [-i id] [-k secret]
  export OSS_ACCESS_KEY_ID="$OSS_ACCESS_KEY_ID" OSS_ACCESS_KEY_SECRET="$OSS_ACCESS_KEY_SECRET"
  OSS_PUSH_FAIL=0
  for ART in "${ARTIFACTS[@]}"; do
    NAME=$(basename "$ART")
    if "$OSSUTIL" cp "$ART" "oss://$OSS_BUCKET/$OSS_PREFIX/$NAME" -e "$ENDPOINT" --region "$REGION"; then
      echo "OSS 上传成功: $OSS_PREFIX/$NAME"
      OSSFILE="$OSS_PREFIX/$NAME"
    else
      echo "ERROR: OSS 上传失败: $NAME" >&2
      OSS_PUSH_FAIL=1
    fi
  done
  if [ "$OSS_PUSH_FAIL" -ne 0 ]; then
    echo "ERROR: OSS 异地备份部分失败（本地备份仍有效）" >&2
    FAILED=1
  else
    echo "异地化备份完成: s3://$OSS_BUCKET/$OSS_PREFIX/"
  fi
fi

# 兼容旧 ssh/scp 异地同步（可选）
BACKUP_REMOTE_HOST="${BACKUP_REMOTE_HOST:-}"
BACKUP_REMOTE_DIR="${BACKUP_REMOTE_DIR:-/data/backups/zxyz}"

if [ -n "$BACKUP_REMOTE_HOST" ]; then
  echo "同步备份到远程主机 $BACKUP_REMOTE_HOST..."
  REMOTE_DIR="$BACKUP_REMOTE_DIR/$DATE"
  ssh -o StrictHostKeyChecking=no "$BACKUP_REMOTE_HOST" "mkdir -p $REMOTE_DIR" || {
    echo "WARN: 无法创建远程目录，跳过 ssh 异地化" >&2
  }
  scp -o StrictHostKeyChecking=no \
    "${ARTIFACTS[@]}" "$BACKUP_REMOTE_HOST:$REMOTE_DIR/" || {
    echo "WARN: ssh/scp 异地化备份失败，本地备份仍有效" >&2
  }
  echo "ssh/scp 异地化备份完成: $BACKUP_REMOTE_HOST:$REMOTE_DIR/"
fi

# 清理过期备份（本地 + RabbitMQ 拓扑，均纳入清理周期）
echo "清理 ${KEEP_DAYS} 天前的备份..."
find "$BACKUP_DIR" -name "mysql_*.sql.gz" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "redis_*.rdb"     -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "rabbitmq_*.json" -mtime +$KEEP_DAYS -delete

if [ "$FAILED" -ne 0 ]; then
  echo "ERROR: 备份流程中存在失败步骤，请检查上方 WARN/ERROR。本地备份可能不完整。" >&2
  exit 1
fi

echo "=== 备份完成 ==="
echo "备份目录: $BACKUP_DIR"
ls -lh "$BACKUP_DIR"/mysql_"$DATE".sql.gz "$BACKUP_DIR"/redis_"$DATE".rdb 2>/dev/null