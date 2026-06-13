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

# Redis 备份
echo "备份 Redis..."
docker exec zxyz-redis redis-cli -a "$REDIS_PASSWORD" BGSAVE
sleep 2
docker cp zxyz-redis:/data/dump.rdb "$BACKUP_DIR/redis_$DATE.rdb"

# 清理过期备份
echo "清理 ${KEEP_DAYS} 天前的备份..."
find "$BACKUP_DIR" -name "mysql_*.sql.gz" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "redis_*.rdb" -mtime +$KEEP_DAYS -delete

echo "=== 备份完成 ==="
echo "备份目录: $BACKUP_DIR"
ls -lh "$BACKUP_DIR"/mysql_"$DATE".sql.gz "$BACKUP_DIR"/redis_"$DATE".rdb 2>/dev/null
