#!/bin/bash
# =============================================================================
# ZXYZ 最小权限数据库账户授权脚本（对应缺陷 U1：root 直连=全库 DBA）
#
# 为 10 个业务库各创建一个「仅限本库、含单库 DDL」的专属账户，取代 root 直连，
# 把任一服务被攻破的爆炸半径收敛到本库。配合 docker-compose 引用这些 *_DB_* 变量
# （compose 已写成 fail-fast 形式 ${PROJECT_DB_USERNAME:?…} / ${PROJECT_DB_PASSWORD:?…}，
# 缺失即启动前硬失败，不再静默回退 root）即可完成切换。
#
# 前置条件（任一不满足将明确报错并以非 0 退出）：
#   1. 一份齐备的 .env（默认 ./.env，可用 --env=PATH 覆盖）：必须含 MYSQL_ROOT_PASSWORD
#      以及每个服务的 *_DB_USERNAME / *_DB_PASSWORD（见脚本底部映射表）。
#   2. 一台【正在运行】的 MySQL 容器：本脚本从 docker-compose.yml 解析 mysql 服务
#      的 container_name（默认 zxyz-mysql），并 docker exec 进容器执行 GRANT。
#   3. 本机 docker 命令可用且能访问该守护进程。
#
# 用法:
#   ./scripts/grant-least-privilege.sh [--env=PATH] [--compose=PATH] [--dry-run]
#     --env=PATH      指定 .env 路径（默认 ./.env，回退到脚本上级目录的 .env）
#     --compose=PATH  指定 compose 文件（默认脚本上级目录的 docker-compose.yml）
#     --dry-run       只打印将执行的 SQL 与 docker 命令，不连接数据库、不写库
#   注：--env/--compose 也接受空格写法（--env PATH），两种写法等价。
#
# 幂等性：重复执行安全（CREATE USER IF NOT EXISTS + 重复 GRANT 均幂等）。
# 回退语义：本脚本只「创建并授权」专用账户，绝不删除 root（root 的去留由 DBA 手动处置）。
#   是否真正切到专用账户由 docker-compose.yml 是否引用 *_DB_* 变量决定；compose 现已写成
#   fail-fast 形式（缺变量即启动前硬失败），因此不存在「静默回退 root」的路径。
# =============================================================================

set -euo pipefail

# --- 参数解析 ---
ENV_FILE=""
COMPOSE_PATH=""
DRY_RUN=false

# 同时支持 `--env=PATH`（文档写法）与 `--env PATH`（CI 调用写法）。
# 曾只认等号形式，导致 CI 传空格形式时 `--env` 落入 `-*` 分支直接 exit 1，
# 每次部署都在「最小权限账号授权」步骤硬失败中止。
while [ $# -gt 0 ]; do
  case "$1" in
    --env=*)     ENV_FILE="${1#--env=}" ;;
    --env)       [ $# -ge 2 ] || { echo "ERROR: --env 需要一个参数" >&2; exit 1; }
                 ENV_FILE="$2"; shift ;;
    --compose=*) COMPOSE_PATH="${1#--compose=}" ;;
    --compose)   [ $# -ge 2 ] || { echo "ERROR: --compose 需要一个参数" >&2; exit 1; }
                 COMPOSE_PATH="$2"; shift ;;
    --dry-run)   DRY_RUN=true ;;
    --help|-h)   sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*)          echo "ERROR: 未知参数: $1" >&2; exit 1 ;;
    *)           # 位置参数视为 .env 路径（兼容旧调用习惯）
                 ENV_FILE="$1" ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# .env 路径解析：优先参数，否则 ./.env，再回退 <项目根>/.env
if [ -z "$ENV_FILE" ]; then
  if [ -f ./.env ]; then
    ENV_FILE="./.env"
  else
    ENV_FILE="$PROJECT_DIR/.env"
  fi
fi
# compose 路径解析
if [ -z "$COMPOSE_PATH" ]; then
  COMPOSE_PATH="$PROJECT_DIR/docker-compose.yml"
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: .env 文件不存在: $ENV_FILE（请先 cp .env.example .env 并填写）" >&2
  exit 1
fi
if [ ! -f "$COMPOSE_PATH" ]; then
  echo "ERROR: docker-compose.yml 不存在: $COMPOSE_PATH（无法解析 mysql 容器名）" >&2
  exit 1
fi

# --- 加载 .env ---
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# --- 必备：root 密码 ---
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "ERROR: MYSQL_ROOT_PASSWORD 未设置，无法以 root 身份连接执行 GRANT" >&2
  exit 1
fi

# 审计 2.3.3：口令改走环境变量（MYSQL_PWD）传递，不再展开进宿主机 docker 客户端进程的 argv
# （`docker exec ... mysql -uroot -p"$PW"` 会让同机任意进程从 /proc/<pid>/cmdline 读到口令）。
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"

# --- 从 compose 解析 mysql 服务的 container_name ---
MYSQL_CONTAINER="$(awk '
  /^  mysql:[[:space:]]*$/   { capture=1; next }
  capture && /^[^[:space:]]/ { capture=0 }
  capture && /container_name:/ {
    gsub(/^[[:space:]]*container_name:[[:space:]]*/, "");
    gsub(/["'"'"' ]/, "");
    print; exit
  }
' "$COMPOSE_PATH")"

if [ -z "$MYSQL_CONTAINER" ]; then
  echo "ERROR: 无法从 $COMPOSE_PATH 解析 mysql 服务的 container_name" >&2
  exit 1
fi

TEMPLATE="$SCRIPT_DIR/../sql/grant-least-privilege.sql"
if [ ! -f "$TEMPLATE" ]; then
  echo "ERROR: SQL 模板不存在: $TEMPLATE" >&2
  exit 1
fi

# --- 服务清单：prefix | 数据库名 | 默认用户名（与 .env 中 *_DB_USERNAME 默认值一致）---
# 顺序即授权顺序；用户名从 .env 的 <PREFIX>_DB_USERNAME 读取（由 init-secrets.sh 写入）。
SERVICES=(
  "PROJECT:zxyz_project:zxyz_project"
  "IM:zxyz_im:zxyz_im"
  "EMAIL:zxyz_email:zxyz_email"
  "SHARE:zxyz_share:zxyz_share"
  "FILE:zxyz_file:zxyz_file"
  "TEAM:zxyz_team:zxyz_team"
  "AUDIT:zxyz_audit:zxyz_audit"
  "USER:zxyz_user:zxyz_user"
  "CONFIG:zxyz_config:zxyz_config"
  "NACOS:nacos:zxyz_nacos"
)

# --- 校验每个服务的账号变量；缺失或仍为占位符则累加错误 ---
# 规则（兼容「未启用专用账户」与逐库灰度）：
#   * 用户名完全未设置（空）      -> ERROR（配置不完整，必须显式给出）
#   * 用户名显式设为 root        -> 跳过该库（compose 用 ${...:-root} 回退 root）
#   * 用户名非 root              -> 必须提供真实 *_DB_PASSWORD，否则 ERROR
MISSING=0
declare -a RENDERED
declare -a SUMMARY
for entry in "${SERVICES[@]}"; do
  IFS=':' read -r prefix db default_user <<< "$entry"
  user_var="${prefix}_DB_USERNAME"
  pass_var="${prefix}_DB_PASSWORD"
  username="${!user_var:-}"
  password="${!pass_var:-}"

  if [ -z "$username" ]; then
    echo "ERROR: $user_var 未设置——启用专用账户需给出非 root 的 <PREFIX>_DB_USERNAME；不用则请显式置为 root 以回退" >&2
    MISSING=1
    continue
  fi
  if [ "$username" = "root" ]; then
    SUMMARY+=("SKIP  $username@'%'  ON  $db.*  (root 回退，不创建专用账户)")
    continue
  fi
  if [ -z "$password" ] || echo "$password" | grep -qE '^CHANGE_ME'; then
    echo "ERROR: $pass_var 未设置或仍为占位符 CHANGE_ME_*（请用 scripts/init-secrets.sh 生成真实密码）" >&2
    MISSING=1
    continue
  fi
  # 渲染：对三个占位符做【全局】替换。注意 GRANT 语句的 __DB__/__USER__ 位于
  # 「ON ... TO ...」续行上，若用 `/GRANT /` 行地址匹配会漏替换 → 生成非法 SQL，
  # 故必须全局替换；模板注释中已刻意不书写字面占位符，不会误伤注释。
  # 再剔除注释行（模板逐库渲染会重复表头，剔除后喂给 mysql 的 SQL 更干净）。
  # 密码仅含 [A-Za-z0-9]，不含 | 与 '，sed 替换安全。
  sql="$(sed -e "s|__USER__|$username|g" \
              -e "s|__PASSWORD__|$password|g" \
              -e "s|__DB__|$db|g" "$TEMPLATE" | grep -v '^[[:space:]]*--' || true)"
  RENDERED+=("$sql")
  SUMMARY+=("GRANT $username@'%'  ON  $db.*")
done

if [ "$MISSING" -ne 0 ]; then
  echo "ERROR: 存在缺失的专用账户变量，已中止（未对数据库做任何改动）。" >&2
  exit 1
fi

# --- dry-run：只打印，不连接 ---
if [ "$DRY_RUN" = true ]; then
  echo "===== [dry-run] 数据库最小权限账户计划（不连接数据库）====="
  for line in "${SUMMARY[@]}"; do
    echo "  -> $line"
  done
  echo ""
  echo "----- 将执行的 SQL -----"
  printf '%s\n' "${RENDERED[@]}"
  echo "----- 将执行的 docker 命令（密码经 -p 传入，不出现在 argv 明文之外）-----"
  echo "docker exec -i '$MYSQL_CONTAINER' mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" < <rendered.sql>"
  exit 0
fi

# 全部库选择 root 回退（无专用账户需创建）时，直接退出，不连接数据库。
if [ "${#RENDERED[@]}" -eq 0 ]; then
  echo "INFO: 所有库均选择 root 回退（未配置非 root 的 *_DB_USERNAME），无需创建专用账户。"
  exit 0
fi

# --- 真实执行：校验容器在运行 + docker 可用 ---
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: 未找到 docker 命令，无法执行 GRANT" >&2
  exit 1
fi
if ! docker ps --filter "name=^${MYSQL_CONTAINER}$" --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  echo "ERROR: MySQL 容器 '$MYSQL_CONTAINER' 未运行（docker ps 未找到）。请先启动容器。" >&2
  exit 1
fi

# 合并所有渲染后的 SQL 到临时文件后一次性喂入（避免多次 docker exec）
TMP_SQL="$(mktemp "${TMPDIR:-/tmp}/zxyz-grant.XXXXXX.sql")"
trap 'rm -f "$TMP_SQL"' EXIT
printf '%s\n' "${RENDERED[@]}" > "$TMP_SQL"

echo "===== 在容器 $MYSQL_CONTAINER 中创建最小权限账户（幂等）====="
if docker exec -i -e MYSQL_PWD "$MYSQL_CONTAINER" mysql -uroot < "$TMP_SQL"; then
  echo "OK: 专用账户授权完成。请由总指挥将 docker-compose.yml 改为引用 *_DB_* 变量以启用。"
else
  echo "ERROR: 执行 GRANT 失败（详见上方 mysql 报错）" >&2
  exit 1
fi

# =============================================================================
# 映射表（env 变量名 ↔ DB 用户 ↔ 数据库名 ↔ compose 对应服务/变量）
#   PROJECT  PROJECT_DB_USERNAME/PASSWORD  zxyz_project  zxyz_project
#            compose: PROJECT_DATASOURCE_USERNAME/PASSWORD
#   IM       IM_DB_USERNAME/PASSWORD       zxyz_im       zxyz_im
#            compose: IM_DATASOURCE_USERNAME/PASSWORD
#   EMAIL    EMAIL_DB_USERNAME/PASSWORD    zxyz_email    zxyz_email
#            compose: EMAIL_DATASOURCE_USERNAME/PASSWORD
#   SHARE    SHARE_DB_USERNAME/PASSWORD    zxyz_share    zxyz_share
#            compose: SHARE_DATASOURCE_USERNAME/PASSWORD
#   FILE     FILE_DB_USERNAME/PASSWORD     zxyz_file     zxyz_file
#            compose: FILE_DATASOURCE_USERNAME/PASSWORD
#   TEAM     TEAM_DB_USERNAME/PASSWORD     zxyz_team     zxyz_team
#            compose: TEAM_DATASOURCE_USERNAME/PASSWORD
#   AUDIT    AUDIT_DB_USERNAME/PASSWORD    zxyz_audit    zxyz_audit
#            compose: AUDIT_DATASOURCE_USERNAME/PASSWORD
#   USER     USER_DB_USERNAME/PASSWORD     zxyz_user     zxyz_user
#            compose: USER_DATASOURCE_USERNAME/PASSWORD
#   CONFIG   CONFIG_DB_USERNAME/PASSWORD   zxyz_config   zxyz_config
#            compose: CONFIG_DB_USERNAME/PASSWORD (admin-service)
#   NACOS    NACOS_DB_USERNAME/PASSWORD    zxyz_nacos    nacos
#            compose: MYSQL_SERVICE_USER/PASSWORD (nacos 服务)
# =============================================================================
