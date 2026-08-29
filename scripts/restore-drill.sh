#!/bin/bash
# ZXYZ 恢复演练脚本
# 用法: ./scripts/restore-drill.sh
#
# 目的：验证最近一次 MySQL 备份能真正恢复。方法：
#   1. 取 $BACKUP_DIR 下最近一个 mysql_*.sql.gz
#   2. 用 docker run 起一个临时 MySQL 容器
#   3. 将备份解压灌入该容器
#   4. 校验核心表（zxyz_user.user）行数非空
#   5. 输出 PASS/FAIL；FAIL 则 exit 1；成功后清理临时容器
#
# 注意：
#   - 需要本机 Docker 可用，且能拉取 mysql:8.4 镜像（或本地已缓存）
#   - 需要 .env 中配置 MYSQL_ROOT_PASSWORD
#   - 临时容器会占用端口（映射到随机可用端口），演练结束后自动删除
#   - 生产环境建议结合 crontab 周期执行，确保持续可恢复

set -euo pipefail

# 加载环境变量
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
set -a
source "$PROJECT_DIR/.env" 2>/dev/null || { echo "ERROR: 缺少 .env" >&2; exit 1; }
set +a

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_DIR/backups}"

# 找最近一个 MySQL 备份
LATEST=$(ls -1t "$BACKUP_DIR"/mysql_*.sql.gz 2>/dev/null | head -n1)
if [ -z "$LATEST" ] || [ ! -f "$LATEST" ]; then
  echo "FAIL: 在 $BACKUP_DIR 未找到 mysql_*.sql.gz 备份文件" >&2
  exit 1
fi

echo "=== ZXYZ 恢复演练 ==="
echo "使用备份: $LATEST"

# Docker 可用性检查
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: 未找到 docker 命令，无法执行恢复演练" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker 守护进程未运行，无法执行恢复演练" >&2
  exit 1
fi

# 一个不冲突的宿主机端口，用于连接临时 MySQL
DRILL_PORT="${RESTORE_DRILL_PORT:-13306}"
CONTAINER_NAME="zxyz-restore-drill-$$"

# 陷阱：退出时无论成功失败都清理临时容器（演练不留残留）
cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "启动临时 MySQL 容器: $CONTAINER_NAME (端口 $DRILL_PORT)"
docker run -d \
  --name "$CONTAINER_NAME" \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -p "127.0.0.1:${DRILL_PORT}:3306" \
  mysql:8.4 >/dev/null

# 等待 MySQL 就绪（最多 120s）
echo "等待 MySQL 就绪..."
READY=0
for i in $(seq 1 120); do
  if docker exec "$CONTAINER_NAME" mysqladmin ping \
      -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; then
    READY=1
    break
  fi
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "FAIL: MySQL 启动超时" >&2
  exit 1
fi
echo "MySQL 就绪 (${i}s)"

# 解压并灌入备份（含建库建表语句，来自 --all-databases mysqldump）
echo "恢复备份数据..."
if gunzip -c "$LATEST" | docker exec -i "$CONTAINER_NAME" \
   mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --max-allowed-packet=512M; then
  echo "数据加载完成"
else
  echo "FAIL: 数据恢复执行失败" >&2
  exit 1
fi

# 校验核心表是否有数据（zxyz_user 库的 user 表）
echo "校验核心表行数..."
ROW_COUNT=$(docker exec "$CONTAINER_NAME" \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT COUNT(*) FROM \`zxyz_user\`.\`user\`;" 2>/dev/null || true)

if [ -n "$ROW_COUNT" ] && [ "$ROW_COUNT" -ge 1 ] 2>/dev/null; then
  echo "PASS: 恢复成功，zxyz_user.user 行数 = $ROW_COUNT"
  PASSED=1
else
  echo "FAIL: zxyz_user.user 行数校验未通过 (ROW_COUNT='$ROW_COUNT')" >&2
  PASSED=0
fi

# 演练结束后清理临时容器
cleanup
trap - EXIT

[ "$PASSED" -eq 1 ] || exit 1
echo "=== 恢复演练 PASS，临时容器已清理 ==="