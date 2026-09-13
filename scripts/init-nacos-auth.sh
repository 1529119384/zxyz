#!/bin/bash
# =============================================================================
# Nacos 管理员用户初始化（幂等）
#
# 为什么需要这个脚本
# ------------------
# Nacos 3.x 在 `nacos.core.auth.enabled=false`（本项目当前形态）时**不会**自动创建
# 内置 nacos 用户 —— `nacos.users` 表保持为空。由此产生三个真实后果：
#   1. 控制台（127.0.0.1:18081）**完全无法登录**，是纯粹的运维盲区；
#   2. 登录接口 `POST /v3/auth/user/login` 永远返回 500 `User nacos not found`
#      ⇒ 拿不到 accessToken；
#   3. `nacos-config/import.sh` 的 token 鉴权通道形同虚设（只能回退 server-identity）。
# 而 `.env` 的 `NACOS_PASSWORD` 与 docker-compose 注释（"控制台管理员密码，必须在 .env 中
# 覆盖默认值"）都表明"本应存在一个管理员用户"——它只是从未被落库。本脚本补上这一步。
#
# 用法（**必须在服务器上运行**，需要能访问 zxyz-mysql 容器）
# -----------------------------------------------------------------------------
#   ./scripts/init-nacos-auth.sh [--reset-password] [--dry-run]
#     （默认幂等）用户已存在时只补 enabled / ROLE_ADMIN / 权限，**不改密码**
#     --reset-password  强制用 .env 的 NACOS_PASSWORD 重置密码
#     --dry-run         只打印将执行的动作
#
# 凭据来源：环境变量优先，否则 ../.env（部署目录里 scripts/ 与 .env 同级）
#   MYSQL_ROOT_PASSWORD  连库用（走 docker exec zxyz-mysql，口令经 MYSQL_PWD 传入，不进 argv）
#   NACOS_USERNAME       管理员用户名（默认 nacos）
#   NACOS_PASSWORD       管理员密码（明文，落库为 BCrypt）
#
# 为什么用 SQL 直插而不是调 API
# --------------------------
# 创建用户本身就需要管理员 token（鸡生蛋）。Nacos 的密码是 Spring Security BCrypt
# （Nacos 3.x 不支持 {noop} 前缀），故用 python3 的 bcrypt 生成，rounds=10 与 Nacos 默认一致。
# 该 BCrypt 串只落库、不落盘、不回显。
# =============================================================================
set -euo pipefail

RESET_PASSWORD=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --reset-password) RESET_PASSWORD=true ;;
    --dry-run)        DRY_RUN=true ;;
    -h|--help)        sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "✗ 未知参数: $arg（可用：--reset-password / --dry-run / --help）"; exit 2 ;;
  esac
done

ENV_FILE="${ENV_FILE:-}"
if [ -z "$ENV_FILE" ]; then
  for c in ../.env ./.env; do [ -f "$c" ] && ENV_FILE="$c" && break; done
fi
if [ -z "$ENV_FILE" ] || [ ! -f "$ENV_FILE" ]; then
  echo "✗ 找不到 .env（可用 ENV_FILE=/path/to/.env 指定）"; exit 1
fi
echo "凭据文件: ${ENV_FILE}"

get() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d '\r' || true; }
RP=$(get MYSQL_ROOT_PASSWORD)
NU=${NACOS_USERNAME:-$(get NACOS_USERNAME)}
NP=${NACOS_PASSWORD:-$(get NACOS_PASSWORD)}
NU=${NU:-nacos}
MYSQL_C=${MYSQL_CONTAINER:-zxyz-mysql}

[ -n "$RP" ] || { echo "✗ ${ENV_FILE} 缺少 MYSQL_ROOT_PASSWORD"; exit 1; }
[ -n "$NP" ] || { echo "✗ ${ENV_FILE} 缺少 NACOS_PASSWORD"; exit 1; }
echo "目标用户: ${NU}（密码长度 ${#NP}，不回显）"

command -v python3 >/dev/null || { echo "✗ 需要 python3"; exit 1; }
python3 -c 'import bcrypt' 2>/dev/null || {
  echo "✗ 缺少 python3 bcrypt 模块。安装：pip3 install bcrypt  （或 apt install python3-bcrypt）"
  exit 1
}
docker inspect "$MYSQL_C" >/dev/null 2>&1 || { echo "✗ 找不到容器 ${MYSQL_C}（本脚本须在服务器上运行）"; exit 1; }

Q() { docker exec -i -e MYSQL_PWD="$RP" "$MYSQL_C" mysql -uroot -N -B -e "$1"; }

EXISTING=$(Q "SELECT COUNT(*) FROM nacos.users WHERE username='${NU}';" | tr -d '\r')
echo "用户已存在: ${EXISTING}"

if [ "$DRY_RUN" = true ]; then
  echo "[dry-run] INSERT nacos.users(username=${NU}, password=<bcrypt>, enabled=1)"
  echo "[dry-run] INSERT IGNORE nacos.roles(username=${NU}, role=ROLE_ADMIN)"
  echo "[dry-run] INSERT IGNORE nacos.permissions(role=ROLE_ADMIN, resource=*, action=*)"
  exit 0
fi

if [ "$EXISTING" = "1" ] && [ "$RESET_PASSWORD" = false ]; then
  echo "→ 用户已存在且未要求重置密码：仅补 enabled/角色/权限"
  Q "UPDATE nacos.users SET enabled=1 WHERE username='${NU}';"
else
  echo "→ 生成 BCrypt 口令并落库（rounds=10）"
  HASH=$(printf '%s' "$NP" | python3 -c "import bcrypt,sys;print(bcrypt.hashpw(sys.stdin.read().encode(),bcrypt.gensalt(rounds=10)).decode())")
  echo "  hash_prefix=${HASH:0:7} hash_len=${#HASH}"
  Q "INSERT INTO nacos.users (username,password,enabled) VALUES ('${NU}','${HASH}',1) ON DUPLICATE KEY UPDATE password=VALUES(password), enabled=1;"
fi

Q "INSERT IGNORE INTO nacos.roles (username,role) VALUES ('${NU}','ROLE_ADMIN');"
Q "INSERT IGNORE INTO nacos.permissions (role,resource,action) VALUES ('ROLE_ADMIN','*','*');"

echo "→ 当前状态"
Q "SELECT 'users',COUNT(*) FROM nacos.users UNION ALL SELECT 'roles',COUNT(*) FROM nacos.roles UNION ALL SELECT 'permissions',COUNT(*) FROM nacos.permissions;"

# 落库后自检：必须真的能拿到 accessToken（否则"初始化成功"只是自说自话）
CONSOLE=${NACOS_CONSOLE_ADDR:-127.0.0.1:18081}
resp=$(curl -s -m 10 -X POST "http://${CONSOLE}/v3/auth/user/login" \
  --data-urlencode "username=${NU}" --data-urlencode "password=${NP}" || true)
TOKEN_LEN=$(printf '%s' "$resp" | python3 -c "import sys,json;print(len(json.load(sys.stdin).get('accessToken','')))" 2>/dev/null || echo 0)
if [ "${TOKEN_LEN:-0}" -gt 0 ]; then
  echo "✓ 自检通过：登录换取 accessToken 成功（token 长度 ${TOKEN_LEN}）"
  echo "  控制台(https://<host>/next/) 与 nacos-config/import.sh 的 token 通道现在均可用"
else
  echo "✗ 自检失败：用户已落库但登录仍失败，请检查 Nacos 鉴权配置（auth.admin.enabled / 密码是否被覆盖）"
  exit 1
fi
