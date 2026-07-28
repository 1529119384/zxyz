#!/bin/bash
# ZXYZ 数据备份脚本
# 用法: ./scripts/backup.sh
# 建议通过 crontab 定时执行: 0 3 * * * /path/to/scripts/backup.sh
#
# 注意: 生产环境应限制 .env 文件权限: chmod 600 .env

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

mkdir -p "$BACKUP_DIR"

echo "=== ZXYZ 备份 $DATE ==="

# MySQL 备份
echo "备份 MySQL..."
docker exec zxyz-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --all-databases --single-transaction --quick | \
  gzip > "$BACKUP_DIR/mysql_$DATE.sql.gz"

# Redis 备份 — 轮询 LASTSAVE 确认 BGSAVE 完成
echo "备份 Redis..."
PREV_SAVE=$(docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" LASTSAVE)
docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" BGSAVE >/dev/null

echo "等待 Redis BGSAVE 完成..."
for i in $(seq 1 60); do
  sleep 1
  CURR_SAVE=$(docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" LASTSAVE)
  if [ "$CURR_SAVE" != "$PREV_SAVE" ]; then
    echo "Redis BGSAVE 完成 (${i}s)"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "ERROR: Redis BGSAVE 超时 (60s)" >&2
    exit 1
  fi
done

docker cp zxyz-redis:/data/dump.rdb "$BACKUP_DIR/redis_$DATE.rdb"

# 备份完整性校验
echo "校验备份文件..."
MYSQL_SIZE=$(wc -c < "$BACKUP_DIR/mysql_$DATE.sql.gz")
REDIS_SIZE=$(wc -c < "$BACKUP_DIR/redis_$DATE.rdb")

if [ "$MYSQL_SIZE" -lt 1024 ]; then
  echo "ERROR: MySQL 备份文件过小 ($MYSQL_SIZE bytes)，可能备份失败" >&2
  exit 1
fi

if [ "$REDIS_SIZE" -lt 1024 ]; then
  echo "ERROR: Redis 备份文件过小 ($REDIS_SIZE bytes)，可能备份失败" >&2
  exit 1
fi

echo "MySQL 备份: $BACKUP_DIR/mysql_$DATE.sql.gz ($MYSQL_SIZE bytes)"
echo "Redis 备份: $BACKUP_DIR/redis_$DATE.rdb ($REDIS_SIZE bytes)"

# 异地化备份（可选）
BACKUP_REMOTE_HOST="${BACKUP_REMOTE_HOST:-}"
BACKUP_REMOTE_DIR="${BACKUP_REMOTE_DIR:-/data/backups/zxyz}"

if [ -z "$BACKUP_REMOTE_HOST" ]; then
  echo "============================================================================" >&2
  echo "WARNING: 未配置远程备份主机 (BACKUP_REMOTE_HOST)" >&2
  echo "  当前备份仅保存在本地 ($BACKUP_DIR)，未做异地化容灾" >&2
  echo "  生产环境务必配置远程备份，防止本地磁盘故障导致数据丢失" >&2
  echo "  请在 .env 中设置 BACKUP_REMOTE_HOST 和 BACKUP_REMOTE_DIR" >&2
  echo "============================================================================" >&2
else
  echo "同步备份到远程主机 $BACKUP_REMOTE_HOST..."
  REMOTE_DIR="$BACKUP_REMOTE_DIR/$DATE"
  ssh -o StrictHostKeyChecking=no "$BACKUP_REMOTE_HOST" "mkdir -p $REMOTE_DIR" || {
    echo "WARN: 无法创建远程目录，跳过异地化" >&2
  }
  scp -o StrictHostKeyChecking=no \
    "$BACKUP_DIR/mysql_$DATE.sql.gz" \
    "$BACKUP_DIR/redis_$DATE.rdb" \
    "$BACKUP_REMOTE_HOST:$REMOTE_DIR/" || {
    echo "WARN: 异地化备份失败，本地备份仍有效" >&2
  }
  echo "异地化备份完成: $BACKUP_REMOTE_HOST:$REMOTE_DIR/"
fi

# 清理过期备份
echo "清理 ${KEEP_DAYS} 天前的备份..."
find "$BACKUP_DIR" -name "mysql_*.sql.gz" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "redis_*.rdb" -mtime +$KEEP_DAYS -delete

echo "=== 备份完成 ==="
echo "备份目录: $BACKUP_DIR"
ls -lh "$BACKUP_DIR"/mysql_"$DATE".sql.gz "$BACKUP_DIR"/redis_"$DATE".rdb 2>/dev/null
