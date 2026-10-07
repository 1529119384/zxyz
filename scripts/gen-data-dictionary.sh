#!/usr/bin/env bash
#
# gen-data-dictionary.sh — 生成 ZXYZ 数据字典（Markdown）
#
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ 前提 / 用法（重要）                                                    ║
# ╠══════════════════════════════════════════════════════════════════════╣
# ║ 本脚本通过 `docker exec` 连接【已经运行中的】MySQL 容器，             ║
# ║ 直接读取 information_schema 导出表结构，因此：                        ║
# ║   1) 执行机器必须装有 docker，且能 `docker ps` 看到目标容器。         ║
# ║   2) 必须先启动数据库，例如：                                        ║
# ║        docker compose -f docker-compose.yml up -d mysql              ║
# ║      （或 docker compose up -d 全量启动）。                           ║
# ║   3) 容器内账号需对 information_schema 及各业务库有读取权限          ║
# ║      （默认用 root，密码取下方 MYSQL_PASSWORD）。                    ║
# ║                                                                      ║
# ║ 典型调用（在仓库根目录执行）：                                        ║
# ║   ./scripts/gen-data-dictionary.sh                                   ║
# ║                                                                      ║
# ║ 覆盖连接参数（可选环境变量）：                                        ║
# ║   MYSQL_CONTAINER  容器名，默认 zxyz-mysql（取自 docker-compose.yml）║
# ║   MYSQL_USER       连接用户，默认 root                               ║
# ║   MYSQL_PASSWORD   连接密码，默认取 $MYSQL_ROOT_PASSWORD 或为空      ║
# ║   DATABASES        空格分隔的库名列表（默认见下方）                  ║
# ║   OUTPUT_FILE      输出 Markdown 路径（默认 <repo>/docs/data-dictionary.md）║
# ║                                                                      ║
# ║ 幂等性：每次运行都会先清空再重写 OUTPUT_FILE，可安全重复执行；       ║
# ║ 不影响数据库，只读 information_schema，不写任何业务数据。            ║
# ╠══════════════════════════════════════════════════════════════════════╣
# ║ ⚠ 本脚本不接入任何 CI workflow：CI 运行环境无 docker / 无运行中的   ║
# ║   MySQL，硬接会导致流水线变红。如需在 CI 产出字典，应在带有 DB 的   ║
# ║   专用 job / 自托管 runner 中调用，或由总指挥另行决策。             ║
# ╚══════════════════════════════════════════════════════════════════════╝

set -euo pipefail

# ── 可覆盖的连接参数 ──────────────────────────────────────────────────
MYSQL_CONTAINER="${MYSQL_CONTAINER:-zxyz-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"
DATABASES="${DATABASES:-zxyz_project zxyz_im zxyz_email zxyz_share zxyz_file zxyz_team zxyz_audit zxyz_config zxyz_user}"

# 仓库根目录（脚本位于 <repo>/scripts/ 下）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_FILE="${OUTPUT_FILE:-$REPO_ROOT/docs/data-dictionary.md}"

# ── 前置检查 ──────────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: 未找到 docker 命令。请在本机安装 docker 后再运行本脚本。" >&2
  exit 1
fi

if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
  echo "ERROR: 容器 '$MYSQL_CONTAINER' 未运行。请先启动数据库，例如：" >&2
  echo "       docker compose -f docker-compose.yml up -d mysql" >&2
  exit 1
fi

if [ -z "$MYSQL_PASSWORD" ]; then
  echo "WARN: MYSQL_PASSWORD 为空（也未设置 MYSQL_ROOT_PASSWORD）。" >&2
  echo "      若数据库 root 设了密码，请通过环境变量传入，否则连接会失败。" >&2
fi

# 将空格分隔的库名列表拼成 SQL IN(...) 所需的 'a','b' 形式
DB_IN_LIST="$(
  echo "$DATABASES" \
    | tr -s ' ' '\n' \
    | sed "s/^/'/; s/\$/'/" \
    | paste -sd, -
)"

# ── 查询 information_schema ───────────────────────────────────────────
# 单条查询同时取 表注释 与 列信息，避免多次 docker exec。
# 字段顺序：TABLE_SCHEMA, TABLE_NAME, TABLE_COMMENT,
#           COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
SQL="SELECT c.TABLE_SCHEMA, c.TABLE_NAME, t.TABLE_COMMENT, \
c.COLUMN_NAME, c.COLUMN_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT, c.COLUMN_COMMENT \
FROM information_schema.COLUMNS c \
JOIN information_schema.TABLES t \
  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME \
WHERE c.TABLE_SCHEMA IN (${DB_IN_LIST}) \
ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION;"

# 通过 -e 传入密码到容器环境变量（不会出现在容器进程 argv / ps 中）
RAW="$(
  docker exec -e "MYSQL_PWD=${MYSQL_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -N -B -e "${SQL}" 2>/dev/null
)" || {
  echo "ERROR: 查询 information_schema 失败。请检查容器名、用户与密码是否正确。" >&2
  exit 1
}

# ── 生成 Markdown ─────────────────────────────────────────────────────
mkdir -p "$(dirname "$OUTPUT_FILE")"

{
  echo "# ZXYZ 数据字典"
  echo
  echo "> 本文档由 \`scripts/gen-data-dictionary.sh\` 自动生成，可重复运行（幂等）。"
  echo "> 生成时间: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "> 数据来源: 容器 \`${MYSQL_CONTAINER}\` 的 information_schema"
  echo "> 覆盖库: ${DATABASES}"
  echo
  echo "**说明**：表/列注释来自数据库 COMMENT；若某列无注释则留空。默认值 \`—\` 表示未设置默认。"
  echo
} > "$OUTPUT_FILE"

if [ -z "$RAW" ]; then
  {
    echo "> ⚠ 未查询到任何表结构。请确认上述库是否存在且已被初始化（执行过建表 SQL）。"
    echo
  } >> "$OUTPUT_FILE"
  echo "WARN: 查询结果为空，已生成占位字典：$OUTPUT_FILE" >&2
  exit 0
fi

# 用 awk 将 TSV 转换为 Markdown（按 库 → 表 → 列 分组）
printf '%s\n' "$RAW" | awk -F'\t' '
function clean(s) {
  # 去掉会破坏 Markdown 表格的换行/制表符，并把 | 转义
  gsub(/[\r\n\t]+/, " ", s)
  gsub(/[|]/, "\\|", s)
  return s
}
function disp_default(d) {
  if (d == "" || d == "NULL") return "—"
  return d
}
function disp_nullable(n) {
  if (n == "NO") return "否"
  if (n == "YES") return "是"
  return n
}
{
  for (i = 1; i <= NF; i++) $i = clean($i)
  schema = $1; table = $2; tcomment = $3
  col = $4; ctype = $5; nullable = disp_nullable($6); cdef = disp_default($7); ccomment = $8

  if (schema != prev_schema) {
    if (prev_schema != "") print ""
    printf "\n## 数据库: %s\n", schema
    prev_schema = schema
    prev_table = ""
  }
  if (table != prev_table) {
    printf "\n### 表: %s", table
    if (tcomment != "") printf "（%s）", tcomment
    printf "\n\n| 列名 | 类型 | 可空 | 默认值 | 注释 |\n|---|---|---|---|---|\n"
    prev_table = table
  }
  printf "| %s | %s | %s | %s | %s |\n", col, ctype, nullable, cdef, ccomment
}
END { print "" }
' >> "$OUTPUT_FILE"

echo "OK: 数据字典已生成 -> $OUTPUT_FILE"
echo "    覆盖库: $DATABASES"
echo "    行数: $(wc -l < "$OUTPUT_FILE")"
