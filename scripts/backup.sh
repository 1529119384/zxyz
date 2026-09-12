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
# 审计 2.3.3：备份产物含 mysql.user 口令哈希与业务数据，禁止组/其他用户读取
umask 077

# --- 参数解析 ---
# 为什么：CI 预部署只需快速备 MySQL（状态核心），无需备 redis/rabbitmq 及异地推送；
# 无参默认仍是全量，保持 crontab 现有行为不变（向后兼容）。
MYSQL_ONLY=false
for a in "$@"; do
  case "$a" in
    --mysql-only) MYSQL_ONLY=true ;;
    *) echo "Unknown arg: $a" >&2; exit 1 ;;
  esac
done

# 加载环境变量
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
set -a
source "$PROJECT_DIR/.env"
set +a
# 审计 2.3.3：DB/Redis 口令改走环境变量（MYSQL_PWD / REDISCLI_AUTH，配合 docker exec -e），
# 不再展开进宿主机 docker 客户端进程的 argv（原来同机任意进程都能从 /proc/<pid>/cmdline 读到）。
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?缺少 MYSQL_ROOT_PASSWORD，请检查 .env}"
export REDISCLI_AUTH="${REDIS_PASSWORD:-}"

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_DIR/backups}"
DATE=$(date +%Y%m%d_%H%M%S)
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
OSSFILE=""

mkdir -p "$BACKUP_DIR"

# 汇总失败标志：任何一个关键步骤失败都不得静默成功
FAILED=0

echo "=== ZXYZ 备份 $DATE ==="

# MySQL 备份
MYSQL_SKIPPED=false
echo "备份 MySQL..."
# 容器未运行时跳过：首次部署/灾后重建场景没有旧数据可备。
# 不跳过会让 docker exec 直接失败 → backup.sh exit 1 → CI 部署中止，
# 但全新服务器首次部署永远过不了这一步（先有部署才有容器）。
if ! docker ps --format '{{.Names}}' | grep -qx 'zxyz-mysql'; then
  echo "WARN: zxyz-mysql 容器未运行，跳过 MySQL 备份（首次部署场景，无旧数据可备）"
  MYSQL_SKIPPED=true
else
if docker exec -e MYSQL_PWD zxyz-mysql mysqldump -uroot \
  --all-databases --single-transaction --quick --source-data=2 | \
  gzip > "$BACKUP_DIR/mysql_$DATE.sql.gz"; then
  :
else
  echo "ERROR: MySQL 备份失败" >&2
  FAILED=1
  exit 1
fi
fi

# ---------------------------------------------------------------------------
# MySQL binlog 增量备份（PITR 时间点恢复的前置条件）
# ---------------------------------------------------------------------------
# 为什么：仅有每日全量 dump，RPO 最长 24h。全量 dump 用 --source-data=2 在文件头
# 写入了 CHANGE REPLICATION SOURCE TO 位点注释作为恢复锚点；配合这里按位点续传的
# binlog 文件，即可把库恢复到两次全量之间的任意时间点。
# 前提：mysql 已开启 log-bin（compose 已固化 --log-bin/--binlog-format=ROW；
# MySQL 8 默认亦开启），否则本步 WARN 跳过。
# 一致性：先 FLUSH BINARY LOGS 轮转，使「当前写入文件」封口；只拷贝其之前的文件
# （状态文件记录下次应从哪个文件起拷），避免拷到半截文件。
# --mysql-only（预部署快速备份）时跳过，保持部署前备份轻快。
BINLOG_ARTIFACT=""
if [ "$MYSQL_ONLY" = false ]; then
echo "备份 MySQL binlog（增量，PITR 用）..."
MYSQL_BINLOG_OK=false
if [ "$MYSQL_SKIPPED" = true ]; then
  echo "WARN: MySQL 未运行，跳过 binlog 备份"
elif ! docker exec -e MYSQL_PWD zxyz-mysql mysql -uroot -N -e \
       "SHOW VARIABLES LIKE 'log_bin'" 2>/dev/null | grep -qi "ON"; then
  echo "WARN: MySQL 未开启 log_bin，无 binlog 可备，跳过（PITR 不可用）" >&2
else
  if docker exec -e MYSQL_PWD zxyz-mysql mysql -uroot -e "FLUSH BINARY LOGS" >/dev/null 2>&1; then
    MYSQL_BINLOG_OK=true
  else
    echo "WARN: FLUSH BINARY LOGS 失败，仍尝试拷贝现有 binlog" >&2
  fi
  # 记录当前位点（人工 PITR 时的参考锚点；权威锚点在 dump 头注释里）
  docker exec -e MYSQL_PWD zxyz-mysql mysql -uroot -N -e "SHOW MASTER STATUS" \
    > "$BACKUP_DIR/binlog_pos_$DATE.txt" 2>/dev/null || true
  CURRENT_BINLOG=$(docker exec -e MYSQL_PWD zxyz-mysql mysql -uroot -N -e \
      "SHOW MASTER STATUS" 2>/dev/null | awk 'NR==1{print $1}')
  BINLOG_STATE_FILE="$BACKUP_DIR/.last_binlog"

  if [ -z "$CURRENT_BINLOG" ]; then
    echo "WARN: 无法获取当前 binlog 文件名，跳过 binlog 备份" >&2
    [ "$MYSQL_BINLOG_OK" = true ] && FAILED=1
  else
    LAST_COPIED=""
    [ -f "$BINLOG_STATE_FILE" ] && LAST_COPIED=$(cat "$BINLOG_STATE_FILE" 2>/dev/null || true)
    BINLOG_TMP=$(mktemp -d)
    BINLOG_COPIED=0
    # 定义域：所有 < 当前文件 的日志（当前文件可能仍在写入，留到下次轮转后再拷）
    # 下界：状态文件记录的「上次已拷到的当前文件」，本次从它开始（含）续传
    while IFS= read -r BLOG; do
      [ -z "$BLOG" ] && continue
      [[ "$BLOG" < "$CURRENT_BINLOG" ]] || continue
      if [ -n "$LAST_COPIED" ] && [[ "$BLOG" < "$LAST_COPIED" ]]; then
        continue
      fi
      if docker cp "zxyz-mysql:/var/lib/mysql/$BLOG" "$BINLOG_TMP/" >/dev/null 2>&1; then
        BINLOG_COPIED=$((BINLOG_COPIED + 1))
      else
        echo "WARN: 拷贝 binlog $BLOG 失败" >&2
      fi
    done < <(docker exec -e MYSQL_PWD zxyz-mysql mysql -uroot -N -e \
               "SHOW BINARY LOGS" 2>/dev/null | awk '{print $1}')

    if [ "$BINLOG_COPIED" -gt 0 ]; then
      if tar -czf "$BACKUP_DIR/binlog_$DATE.tar.gz" -C "$BINLOG_TMP" . ; then
        BINLOG_ARTIFACT="$BACKUP_DIR/binlog_$DATE.tar.gz"
        echo "binlog 增量备份: $BINLOG_ARTIFACT ($BINLOG_COPIED 个文件)"
      else
        echo "ERROR: binlog 打包失败" >&2
        FAILED=1
      fi
    else
      echo "本次无新增 binlog 文件可备份"
    fi
    rm -rf "$BINLOG_TMP"
    # 状态推进到当前文件：下次先轮转，当前文件即封口，再从其起拷贝
    if [ "$MYSQL_BINLOG_OK" = true ]; then
      echo "$CURRENT_BINLOG" > "$BINLOG_STATE_FILE"
    fi
  fi
fi
fi

# Redis 备份 — 轮询 LASTSAVE 确认 BGSAVE 完成
# 注意: LASTSAVE 是 Unix 秒级时间戳。BGSAVE 完成后若与下一次轮询落在同一秒内，
# LASTSAVE 可能与变更前相等（同秒）。因此"前移判定"采用严格随大——只要某次
# 读到的 LASTSAVE 已较基线前移即可判定完成；同秒未前移则继续轮询，靠 60s 总
# 上限兜底超时（不因单次同秒而误判失败）。
# --mysql-only 时跳过：预部署只需 MySQL 状态核心，redis 非必须且耗时。
if [ "$MYSQL_ONLY" = false ]; then
echo "备份 Redis..."
PREV_SAVE=$(REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH zxyz-redis redis-cli LASTSAVE) || { echo "ERROR: Redis LASTSAVE 失败" >&2; FAILED=1; exit 1; }
REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH zxyz-redis redis-cli BGSAVE >/dev/null

echo "等待 Redis BGSAVE 完成..."
for i in $(seq 1 60); do
  sleep 1
  CURR_SAVE=$(REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH zxyz-redis redis-cli LASTSAVE 2>/dev/null || echo "$PREV_SAVE")
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
fi

# RabbitMQ 队列/交换器/绑定拓扑备份
# --mysql-only 时跳过：预部署只需 MySQL 状态核心，拓扑非必须。
if [ "$MYSQL_ONLY" = false ]; then
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
fi

# 备份完整性校验（被跳过的组件不校验、不入产物清单）
echo "校验备份文件..."
if [ "$MYSQL_SKIPPED" = true ]; then
  MYSQL_SIZE=0
else
  MYSQL_SIZE=$(wc -c < "$BACKUP_DIR/mysql_$DATE.sql.gz")
fi

# --mysql-only 时只校验 MySQL；redis/rabbitmq 产物不存在，跳过其校验与汇总
if [ "$MYSQL_ONLY" = false ]; then
REDIS_SIZE=$(wc -c < "$BACKUP_DIR/redis_$DATE.rdb")

if [ "$REDIS_SIZE" -lt 1024 ]; then
  echo "ERROR: Redis 备份文件过小 ($REDIS_SIZE bytes)，可能备份失败" >&2
  FAILED=1
  exit 1
fi
fi

if [ "$MYSQL_SKIPPED" = true ]; then
  echo "MySQL 备份: 已跳过（容器未运行，首次部署场景）"
elif [ "$MYSQL_SIZE" -lt 1024 ]; then
  echo "ERROR: MySQL 备份文件过小 ($MYSQL_SIZE bytes)，可能备份失败" >&2
  FAILED=1
  exit 1
else
  echo "MySQL 备份: $BACKUP_DIR/mysql_$DATE.sql.gz ($MYSQL_SIZE bytes)"
fi
if [ "$MYSQL_ONLY" = false ]; then
echo "Redis 备份: $BACKUP_DIR/redis_$DATE.rdb ($REDIS_SIZE bytes)"
echo "RabbitMQ 拓扑: $BACKUP_DIR/rabbitmq_$DATE.json"
fi

# 组装本次产物列表（--mysql-only 时仅 MySQL，避免异地推送扫到不存在的文件）
if [ "$MYSQL_SKIPPED" = true ]; then
ARTIFACTS=()
if [ "$MYSQL_ONLY" = false ]; then
ARTIFACTS=( "$BACKUP_DIR/redis_$DATE.rdb" )
[ -f "$BACKUP_DIR/rabbitmq_$DATE.json" ] && ARTIFACTS+=( "$BACKUP_DIR/rabbitmq_$DATE.json" )
fi
elif [ "$MYSQL_ONLY" = false ]; then
ARTIFACTS=( "$BACKUP_DIR/mysql_$DATE.sql.gz" "$BACKUP_DIR/redis_$DATE.rdb" )
[ -f "$BACKUP_DIR/rabbitmq_$DATE.json" ] && ARTIFACTS+=( "$BACKUP_DIR/rabbitmq_$DATE.json" )
else
ARTIFACTS=( "$BACKUP_DIR/mysql_$DATE.sql.gz" )
fi

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
# --mysql-only 时跳过：预部署只备本地 MySQL，异地推送由 crontab 全量备份完成
if [ "$MYSQL_ONLY" = false ]; then
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
fi

# 兼容旧 ssh/scp 异地同步（可选，--mysql-only 同样跳过）
if [ "$MYSQL_ONLY" = false ]; then
BACKUP_REMOTE_HOST="${BACKUP_REMOTE_HOST:-}"
BACKUP_REMOTE_DIR="${BACKUP_REMOTE_DIR:-/data/backups/zxyz}"

if [ -n "$BACKUP_REMOTE_HOST" ]; then
  echo "同步备份到远程主机 $BACKUP_REMOTE_HOST..."
  REMOTE_DIR="$BACKUP_REMOTE_DIR/$DATE"
  # 审计 2.3.3：原来用 StrictHostKeyChecking=no（完全不校验主机密钥，可被 MITM 劫持）。
  # 改为 accept-new：首次连接按 TOFU 固化，之后密钥变化即失败；known_hosts 落到稳定路径，
  # 需要预置时用 BACKUP_SSH_KNOWN_HOSTS 指向受控文件。
  SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=${BACKUP_SSH_KNOWN_HOSTS:-$HOME/.ssh/known_hosts}")
  ssh "${SSH_OPTS[@]}" "$BACKUP_REMOTE_HOST" "mkdir -p $REMOTE_DIR" || {
    echo "WARN: 无法创建远程目录，跳过 ssh 异地化" >&2
  }
  scp "${SSH_OPTS[@]}" \
    "${ARTIFACTS[@]}" "$BACKUP_REMOTE_HOST:$REMOTE_DIR/" || {
    echo "WARN: ssh/scp 异地化备份失败，本地备份仍有效" >&2
  }
  echo "ssh/scp 异地化备份完成: $BACKUP_REMOTE_HOST:$REMOTE_DIR/"
fi
fi

# 清理过期备份（本地 + RabbitMQ 拓扑，均纳入清理周期）
echo "清理 ${KEEP_DAYS} 天前的备份..."
find "$BACKUP_DIR" -name "mysql_*.sql.gz" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "redis_*.rdb"     -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "rabbitmq_*.json" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "binlog_*.tar.gz" -mtime +$KEEP_DAYS -delete

if [ "$FAILED" -ne 0 ]; then
  echo "ERROR: 备份流程中存在失败步骤，请检查上方 WARN/ERROR。本地备份可能不完整。" >&2
  exit 1
fi

echo "=== 备份完成 ==="
echo "备份目录: $BACKUP_DIR"
# 收尾展示按实际产物列出：被跳过的组件文件不存在，ls 非零退出码
# 会成为脚本最终退出码（set -e 下即使 stderr 已静默），故逐项 || true
if [ "$MYSQL_SKIPPED" = false ]; then
  ls -lh "$BACKUP_DIR"/mysql_"$DATE".sql.gz 2>/dev/null || true
fi
if [ "$MYSQL_ONLY" = false ]; then
  ls -lh "$BACKUP_DIR"/redis_"$DATE".rdb 2>/dev/null || true
  ls -lh "$BACKUP_DIR"/binlog_"$DATE".tar.gz 2>/dev/null || true
fi
exit 0