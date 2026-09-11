#!/bin/bash
# =============================================================================
# ZXYZ 服务器端部署脚本
# 调用方：.github/workflows/ci-cd.yml 的 deploy job（经由 appleboy/ssh-action 引导层）
#
# 为什么是独立文件、而不是 workflow 里的内嵌 script：
#   GitHub 对 workflow 中单个标量的长度上限是 21000 —— 单位是 **UTF-8 字节**，不是字符
#   （中文注释每字 3 字节，本文件注释占比很高）。本脚本原先内嵌时已达 20860 字节、
#   只剩约 140 字节余量，任何追加都会让整个 workflow 变成 "Invalid workflow file"
#   （表现为 run 以文件路径为名、0 个 job 直接失败，
#    报错 "(Line: N, Col: C): Exceeded max expression length 21000"）。
#   抽成文件后彻底解除该限制：文件体积不受 workflow 标量上限约束。
#   改完本文件请跑 `bash -n` 自检；改 ci-cd.yml 请核对那个标量的字节数（见 prune-old-images.sh 顶部）。
#
# 调用契约（必需的环境变量，缺失即 fail-fast，由 ci-cd.yml 的 envs 注入）：
#   DEPLOY_PATH    部署目录（默认 /www/zxyz）
#   IMAGE_TAG      本次部署的不可变镜像 tag（= github.sha）
#   IMAGE_PREFIX   镜像前缀。compose 里是裸拼接 ${IMAGE_PREFIX:-}zxyz-<svc>:<tag>，
#                  故**语义上要求以 / 结尾**（CI 注入的 ghcr.io/<owner> 无尾斜杠，
#                  本脚本会就地归一化，见下方「归一化 IMAGE_PREFIX」）。
#   DEPLOY_ENV     环境名（production / development），仅用于日志
#   另有一组变更开关：BACKEND_COMMON / {PROJECT,IM,EMAIL,USER,SHARE,FILE,TEAM,AUDIT,ADMIN,GATEWAY}_SVC
#   / FRONTEND / DOCKER_CFG / FAST_DEPLOY —— 同样由 CI 注入，脚本内以 "$VAR" 直接读取。
#
# 字段说明：
#   $DEPLOY_DIR 是长期部署目录（存 .env / data / backups），$DEPLOY_DIR-repo 才是每次
#   git pull 的克隆；脚本优先使用克隆内的最新脚本，回退长期目录的副本（见 pick_script）。
#
# 排障时可以手工执行（跳过 CI，直接在本机/服务器上跑）：
#   DEPLOY_PATH=/www/zxyz IMAGE_TAG=<sha> IMAGE_PREFIX=ghcr.io/<owner>/ DEPLOY_ENV=manual \
#     bash scripts/deploy-on-server.sh
# =============================================================================

set -euo pipefail

DEPLOY_DIR="${DEPLOY_PATH:-/www/zxyz}"
# 为什么：部署必须消费「不可变」tag，否则回滚无效。
# build-and-push 已把镜像同时打了 dev/latest（可变）与 github.sha（不可变）两个 tag，
# 这里只用 40 位全量 commit sha，与 :409 已推送的 sha tag 精确匹配；
# .env.previous 复制出的旧 .env 也含旧 sha，rollback.sh 拉取 ...:<旧sha> 才能精确回滚。
: "${IMAGE_TAG:?CI 必须通过 envs 注入 IMAGE_TAG（=github.sha）}"
: "${IMAGE_PREFIX:?CI 必须通过 envs 注入 IMAGE_PREFIX}"

# --- 归一化 IMAGE_PREFIX 并 export（必须在任何 compose 调用之前）---
# 为什么必须在这里做、而不是只写进 .env：
#   docker-compose.yml 里是「裸拼接」—— ${IMAGE_PREFIX:-}zxyz-frontend-nginx:${APP_IMAGE_TAG:-latest}
#   没有中间斜杠，因此前缀**必须以 / 结尾**，否则 image 会解析成
#   ghcr.io/1529119384zxyz-frontend-nginx（owner 与仓库名黏在一起）→ GHCR 400/401。
#   而 ci-cd.yml 注入的是 `ghcr.io/${{ github.repository_owner }}`（无尾斜杠）。
#   两者过去只靠「写进 .env 时补斜杠」这一步调和 —— 但 compose 的插值优先级是
#   **shell 环境 > .env**，所以只要 IMAGE_PREFIX 出现在 shell 环境里（CI 的 envs
#   就会把它带上去），.env 里那个补好斜杠的值就会被静默覆盖，compose 拿到裸前缀。
#   实证：run 34609677202（3b4e7a0）部署失败
#         Image ghcr.io/1529119384zxyz-frontend-nginx:3b4e7a0… Pulling
#         unexpected status from HEAD … https://ghcr.io/v2/1529119384zxyz-frontend-nginx/…: 400 Bad Request
#   故这里就地归一化 + export：让 compose 无论从哪个来源取值都拿到已知正确的形式。
#   归一化幂等（有则不动、无则补一个），export 后下方所有 `docker compose` 与
#   .env 写入共用同一个值，不会再出现「.env 正确但实际生效的是另一个」。
IMAGE_PREFIX="${IMAGE_PREFIX%/}/"
export IMAGE_PREFIX

cd "$DEPLOY_DIR"

# $DEPLOY_DIR 是长期部署目录（存 .env / data / backups），$DEPLOY_DIR-repo 才是
# 每次部署 git pull 的克隆。提前定义 REPO_DIR 并提供 pick_script：
# 优先取克隆里的最新脚本（含本次修复），回退长期目录的旧副本。
# 背景：$DEPLOY_DIR 下的 scripts/ 不会自动更新，新增的 grant-least-privilege.sh
# 在旧服务器上根本不存在；若直接跳过授权，compose 又会用 fail-fast 专用账号起服务，
# 结果是「部署看似成功、所有服务连库失败」。
REPO_DIR="${DEPLOY_DIR}-repo"
pick_script() {
  local name="$1" cand=""
  for cand in "$REPO_DIR/scripts/$name" "$DEPLOY_DIR/scripts/$name"; do
    [ -f "$cand" ] && { printf '%s' "$cand"; return 0; }
  done
  return 1
}

# --- 提前刷新克隆 + 同步 scripts/（必须在 init-secrets 之前！）---
# 为什么：pick_script 会优先用 $REPO_DIR/scripts 的副本，而 pull 原本排在
# init-secrets/validate-env 之后 —— 于是这两步拿到的是「上次部署的旧克隆」。
# 后果实证（run #104）：旧 init-secrets 不认识本次新增的 *_DB_USERNAME/*_DB_PASSWORD
# 键，不会补空值 → 后续 grant 读到空用户名 → 报「未设置」→ 部署中止。
# 提前 pull 后，后续 pull 已是最新（无副作用），init-secrets/validate-env/grant 才是本次代码。
if [ -d "$REPO_DIR" ]; then
  if ! git -C "$REPO_DIR" pull --ff-only -q; then
    echo "::error::REPO_PULL_FAILED: 无法更新 $REPO_DIR（常见原因：工作区有漂移改动）。已中止，后续脚本需最新版本"
    git -C "$REPO_DIR" status --porcelain 2>/dev/null || true
    exit 1
  fi
  if [ -d "$REPO_DIR/scripts" ]; then
    mkdir -p "$DEPLOY_DIR/scripts"
    cp -f "$REPO_DIR/scripts/"*.sh "$DEPLOY_DIR/scripts/" 2>/dev/null || true
  fi
fi

echo "===== Deploying to ${DEPLOY_ENV:-unknown} ====="
echo "Image tag: $IMAGE_TAG"

# --- 预部署备份（回滚前的最后一道防线：先备 MySQL，再覆盖）---
# 为什么：部署会覆盖旧镜像/旧 .env，若部署中途失败且无备份则无回退点。
# 此处只备 MySQL（状态核心，且快），全量/异地备份仍由 crontab 跑 backup.sh 完成。
# 在 set -euo pipefail 下，backup.sh 返回非 0 即中止部署——这正是想要的行为。
echo "===== Pre-deploy MySQL backup ====="
if [ -f "$DEPLOY_DIR/scripts/backup.sh" ]; then
  if ! bash "$DEPLOY_DIR/scripts/backup.sh" --mysql-only; then
    echo "::error::PRE_DEPLOY_BACKUP_FAILED: 预部署 MySQL 备份失败，已中止部署以防无回退点"
    exit 1
  fi
  echo "::notice::PRE_DEPLOY_BACKUP_OK: 预部署 MySQL 备份完成"
else
  echo "::warning::跳过预部署备份（backup.sh 不存在）"
fi

# --- 首次部署自动生成机密（幂等，已有值不覆盖）---
# 为什么：新环境 .env 里一堆 CHANGE_ME_* 占位符强密码需手填，体验极差。
# 本步骤在「校验/部署」之前、且独立于 REPO_DIR 是否存在，自动生成纯本地的
# 系统内部机密（DB/Redis/RabbitMQ/Nacos/Jasypt/Grafana 等），写回 .env 持久化，
# 完整值仅落服务器本地 init-secrets.log（chmod 600），终端只打码，不进 CI 公开日志。
# 幂等：已有非占位符值跳过，避免重启后换密码破坏 MySQL/Redis/Jasypt 连接与跨服务 token 一致。
# 外部凭证（OSS 访问密钥/邮箱密码/前端地址等）init-secrets.sh 不生成，仍由后续 validate-env 拦截手填。
echo "===== Initializing secrets (if missing) ====="
# 注意：不再要求 .env 预先存在 —— init-secrets.sh 自身在 .env 缺失时会
# 自动从 .env.example 复制（N5）。此前要求「脚本与 .env 同时存在」，
# 导致全新服务器直接跳过初始化，随后 compose 解析 ${SVC_GATEWAY_KEY:?...}
# 硬失败（报错信息与真实原因相距很远，极难定位）。
# 脚本优先取 $REPO_DIR（最新；本步只写 $DEPLOY_DIR/.env 与 $DEPLOY_DIR/init-secrets.log，
# 不会污染克隆工作区，故可安全使用克隆内副本）。
SECRETS_SCRIPT="$(pick_script init-secrets.sh || true)"
if [ -n "$SECRETS_SCRIPT" ]; then
  if ! bash "$SECRETS_SCRIPT" "$DEPLOY_DIR/.env"; then
    echo "::error::INIT_SECRETS_FAILED: 首次部署机密初始化失败，已中止部署"
    exit 1
  fi
else
  echo "::warning::跳过机密初始化（init-secrets.sh 不存在）"
fi

# --- 记录当前版本用于回滚 ---
echo "===== Recording current version for rollback ====="
if [ -f "$DEPLOY_DIR/.env" ]; then
  if grep -qE "^APP_IMAGE_TAG=" "$DEPLOY_DIR/.env" 2>/dev/null; then
    cp "$DEPLOY_DIR/.env" "$DEPLOY_DIR/.env.previous"
    echo "Current tag recorded to .env.previous"
  else
    echo "WARN: APP_IMAGE_TAG not found in .env, skip rollback record"
  fi
fi

# --- 配置同步 ---
UP_FLAGS=""
if [ -d "$REPO_DIR" ]; then
  # --- 验签 .env（生产门禁）——在拉取/部署之前执行，失败即中止部署 ---
  # validate-env.sh 在检测到必需变量缺失/占位符(EERROR)时返回 1。
  # 此处不使用 --sync-only（它会跳过全部校验，等同于无效），
  # 也不吞掉失败；校验失败即硬失败中止部署。
  # --prod 门禁已是脚本默认行为（Nacos/RabbitMQ/OSS 等生产敏感项只在
  # 校验通过时放行）；此脚本当前不识别 --prod 参数，故直接用完整校验。
  VALIDATE_SCRIPT="$(pick_script validate-env.sh || true)"
  if [ -n "$VALIDATE_SCRIPT" ] && [ -f "$DEPLOY_DIR/.env" ]; then
    echo "===== Validating .env (prod gate) ====="
    if ! bash "$VALIDATE_SCRIPT" "$DEPLOY_DIR/.env"; then
      echo "::error::VALIDATE_ENV_FAILED: .env 校验未通过（必需变量缺失或仍为占位符），已中止部署"
      exit 1
    fi
    echo "::notice::VALIDATE_ENV_PASSED: .env 校验通过"
    echo ""
  else
    echo "::warning::跳过 .env 校验（validate-env.sh 或 .env 不存在）"
  fi

  # 组合 hash：把 TLS 覆盖层一并纳入「定义是否变更」判定，
  # 否则只改 docker-compose.tls.yml 时不会触发 --force-recreate。
  _compose_hash() {
    local h
    h="$(md5sum "$DEPLOY_DIR/docker-compose.yml" 2>/dev/null | cut -d' ' -f1 || true)"
    if [ -f "$DEPLOY_DIR/docker-compose.tls.yml" ]; then
      h="${h}$(md5sum "$DEPLOY_DIR/docker-compose.tls.yml" | cut -d' ' -f1)"
    fi
    printf '%s' "$h"
  }
  OLD_HASH="$(_compose_hash)"
  cd "$REPO_DIR" && git pull --ff-only -q

  # --- 漂移检测：拉取后若工作区非空即中止并要求人工确认 ---
  echo "===== 检测部署配置仓库工作区漂移 ====="
  DRIFT_OUTPUT=$(git -C "$REPO_DIR" status --porcelain)
  if [ -n "$DRIFT_OUTPUT" ]; then
    echo "::error::GIT_DRIFT_DETECTED: $REPO_DIR 工作区存在未提交/漂移变更"
    printf '%s\n' "$DRIFT_OUTPUT"
    echo "::error::若确需保留这些变更请人工确认并提交；在清理漂移前 CI 部署已中止，以防配置文件被静默覆盖"
    exit 1
  fi
  echo "工作区清洁，无漂移"

  cp "$REPO_DIR/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml"
  # U2：TLS 覆盖层此前从未同步到 $DEPLOY_DIR，导致 TLS_ENABLED=true 时
  # 下方「覆盖层不存在即中止」必然触发、TLS 部署根本无法上线。
  if [ -f "$REPO_DIR/docker-compose.tls.yml" ]; then
    cp "$REPO_DIR/docker-compose.tls.yml" "$DEPLOY_DIR/docker-compose.tls.yml"
  fi
  # scripts/ 已在流程最前面（init-secrets 之前）同步过，此处不再重复。
  # backup.sh 仍从 $DEPLOY_DIR 侧执行：它按自身位置写 ../backups，
  # 若改从 $REPO_DIR 调用会污染克隆工作区、触发下一轮漂移检测。
  cd "$DEPLOY_DIR"
  NEW_HASH="$(_compose_hash)"
  [ "$OLD_HASH" != "$NEW_HASH" ] && UP_FLAGS="--force-recreate" && echo "compose 定义（含 TLS 覆盖层）已变更，将 --force-recreate"
fi

# --- 更新 .env ---
# 为什么不用 `sed ... || echo 追加`：sed 对「行不存在」返回 0（不报错），
# || 兜底永远不触发。.env.example 若缺 IMAGE_PREFIX 行，前缀会静默丢失，
# compose 把 image 解析成无 registry 前缀的 zxyz-xxx → GHCR 私有镜像 pull 401。
if grep -qE "^APP_IMAGE_TAG=" .env; then
  sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=$IMAGE_TAG|" .env
else
  echo "APP_IMAGE_TAG=$IMAGE_TAG" >> .env
fi
# 这里的 IMAGE_PREFIX 已在文件顶部归一化（保证恰好一个尾斜杠）并 export，
# 直接落盘即可 —— 不要再在此处重新拼斜杠，否则又会出现「两处各算一次」的分叉。
# 注意：写对 .env 只是让「非 CI 手工执行」和「后续 rollback.sh 复用 .env」正确；
# CI 路径下真正生效的是 export 出去的那个值（shell 环境优先于 .env）。
if grep -qE "^IMAGE_PREFIX=" .env; then
  sed -i "s|^IMAGE_PREFIX=.*|IMAGE_PREFIX=$IMAGE_PREFIX|" .env
else
  echo "IMAGE_PREFIX=$IMAGE_PREFIX" >> .env
fi

# --- 最小权限数据库账号（U1）---
# 为什么：此前所有服务用 MySQL root 连接，任一服务被攻破即等于拿到全库 DDL 权限。
# init-secrets.sh（上方）已为每个服务生成独立 zxyz_* 账号密码并写入 .env，
# 此处把这些账号/授权真正落进 MySQL（CREATE USER IF NOT EXISTS + 单库 GRANT）。
# 幂等：重复执行只补授权，不重置已有密码——密码由 init-secrets 幂等生成，
# 跨部署稳定，改密会导致运行中的服务断连，故此处绝不 ALTER USER。
# 前提：MySQL 已运行（与上方 backup.sh --mysql-only 完全同一前提；首次部署
# 需先手动起基础设施，本脚本不负责起 mysql 容器）。
GRANT_SCRIPT="$(pick_script grant-least-privilege.sh || true)"
if [ -n "$GRANT_SCRIPT" ]; then
  echo "===== Applying least-privilege DB accounts ====="
  if ! bash "$GRANT_SCRIPT" \
         --env="$DEPLOY_DIR/.env" --compose="$DEPLOY_DIR/docker-compose.yml"; then
    echo "::error::GRANT_LEAST_PRIVILEGE_FAILED: 最小权限数据库账号授权失败，已中止部署"
    exit 1
  fi
  echo "::notice::GRANT_LEAST_PRIVILEGE_OK: 最小权限数据库账号已就绪"
else
  # 不再仅告警：compose 已 fail-fast 引用专用账号，缺授权必然导致所有服务连库失败，
  # 与其「部署成功但服务全挂」不如就地中止并给出可执行指引。
  echo "::error::GRANT_LEAST_PRIVILEGE_MISSING: 未找到 grant-least-privilege.sh（$REPO_DIR/scripts 与 $DEPLOY_DIR/scripts 皆无）。compose 已要求专用账号，请先同步脚本再部署"
  exit 1
fi

# --- 筛选需要更新的服务 ---
UPDATE_SVC=()
if [ "$BACKEND_COMMON" = "true" ]; then
  UPDATE_SVC+=(project-service im-service email-service user-service share-service file-service team-service audit-service admin-service gateway)
else
  [ "$PROJECT_SVC" = "true" ] && UPDATE_SVC+=(project-service)
  [ "$IM_SVC" = "true" ] && UPDATE_SVC+=(im-service)
  [ "$EMAIL_SVC" = "true" ] && UPDATE_SVC+=(email-service)
  [ "$USER_SVC" = "true" ] && UPDATE_SVC+=(user-service)
  [ "$SHARE_SVC" = "true" ] && UPDATE_SVC+=(share-service)
  [ "$FILE_SVC" = "true" ] && UPDATE_SVC+=(file-service)
  [ "$TEAM_SVC" = "true" ] && UPDATE_SVC+=(team-service)
  [ "$AUDIT_SVC" = "true" ] && UPDATE_SVC+=(audit-service)
  [ "$ADMIN_SVC" = "true" ] && UPDATE_SVC+=(admin-service)
  [ "$GATEWAY_SVC" = "true" ] && UPDATE_SVC+=(gateway)
fi
# 前端入口容器独立于 backend-common：只要 frontend 有变更就更新
# （漏了它用户将没有任何 HTTP 入口——run 33963918081 教训）
[ "$FRONTEND" = "true" ] && UPDATE_SVC+=(frontend-nginx)
[ "$DOCKER_CFG" = "true" ] && UPDATE_SVC=(project-service im-service email-service user-service share-service file-service team-service audit-service admin-service gateway frontend-nginx)

if [ ${#UPDATE_SVC[@]} -eq 0 ]; then
  echo "No service changed, skip deploy"
  exit 0
fi
echo "Will update: ${UPDATE_SVC[*]}"

# --- TLS 覆盖层（U2）：TLS_ENABLED=true 时叠加 docker-compose.tls.yml ---
# 用 COMPOSE_FILE 环境变量统一生效，而非在每个 docker compose 命令后追加 -f：
# 后者要改多处，漏一处就会造成「pull 带 TLS、up 不带」之类的不一致。
# 分隔符在 Linux 上为 ':'。未启用 TLS 时行为与历史完全一致。
export COMPOSE_FILE="docker-compose.yml"
if grep -qE '^TLS_ENABLED=true' "$DEPLOY_DIR/.env" 2>/dev/null; then
  if [ -f "$DEPLOY_DIR/docker-compose.tls.yml" ]; then
    # 证书预检：镜像内 default-ssl.conf 固定引用 fullchain.pem / privkey.pem；
    # 缺证书时 nginx 会启动即崩并入 restart 循环，此处提前给出可操作报错。
    if [ ! -f "$DEPLOY_DIR/deploy/nginx/certs/fullchain.pem" ] \
       || [ ! -f "$DEPLOY_DIR/deploy/nginx/certs/privkey.pem" ]; then
      echo "::error::TLS_CERT_MISSING: TLS_ENABLED=true 但缺少 $DEPLOY_DIR/deploy/nginx/certs/{fullchain.pem,privkey.pem}，nginx 将无法启动"
      exit 1
    fi
    export COMPOSE_FILE="docker-compose.yml:docker-compose.tls.yml"
    echo "::notice::TLS_ENABLED=true，已叠加 docker-compose.tls.yml（证书预检通过）"
  else
    echo "::error::TLS_ENABLED=true 但 $DEPLOY_DIR/docker-compose.tls.yml 不存在，已中止部署"
    exit 1
  fi
fi

# --- 并行拉取镜像（失败必须显式暴露，不能静默降级为源码构建）---
# 为什么不能只写 `cmd &` + 无参 `wait`：无参 wait 只等所有子进程结束，
# **不会把任一子进程的非零退出码透传出来**，于是 pull 失败被静默吞掉、脚本继续往下，
# 最终 `docker compose up -d` 因本地无镜像而退化去执行 compose 里的 build: 段，
# 报出与真实原因毫无关系的错误。实证（run 34609677202）：
#   Image ghcr.io/1529119384zxyz-frontend-nginx:3b4e7a0… Pulling
#   unexpected status from HEAD … /v2/1529119384zxyz-frontend-nginx/manifests/…: 400 Bad Request
#   然而日志下一行是 "Pull finished"（看似成功），真正的报错在其后很远：
#   resolve : lstat ***/ZXYZdatabaseBack: no such file or directory（服务器上确实没有该目录）
# 判据选择：不直接拿 pull 的退出码当结论，而是检查「compose up 时该镜像在不在本地」——
# 这才是决定它会不会退化成源码构建的真正前提；退出码非零但镜像已在本地不算故障。
echo "===== Pulling images ====="
PULL_PIDS=()
for svc in "${UPDATE_SVC[@]}"; do
  docker compose pull "$svc" &
  PULL_PIDS+=("$!:$svc")
done
pull_failed=()
for entry in "${PULL_PIDS[@]}"; do
  pid="${entry%%:*}"; svc="${entry#*:}"
  if ! wait "$pid"; then
    pull_failed+=("$svc")
  fi
done

# 镜像名与 compose 保持同一套契约：${IMAGE_PREFIX}zxyz-<service>:${APP_IMAGE_TAG}
# （IMAGE_PREFIX 已在文件顶部补好尾斜杠；APP_IMAGE_TAG 由上方写入 .env）
missing=()
for svc in "${UPDATE_SVC[@]}"; do
  docker image inspect "${IMAGE_PREFIX}zxyz-$svc:$IMAGE_TAG" >/dev/null 2>&1 || missing+=("$svc")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "::error::IMAGE_MISSING: 以下镜像既未成功拉取、本地也不存在：${missing[*]}"
  echo "::error::  拉取失败的服务：${pull_failed[*]:-（无，可能是本地 tag 缺失）}"
  echo "::error::  若 compose up 继续执行，它会退化成源码构建并报与真实原因无关的 lstat 错误。"
  echo "::error::  排查方向：① IMAGE_PREFIX 是否形如 ghcr.io/<owner>/（必需尾斜杠，CI 注入值无尾斜杠，本脚本已归一化）"
  echo "::error::            ② tag $IMAGE_TAG 是否已由 build-and-push 推送成功"
  echo "::error::            ③ 服务器能否访问 ghcr.io（网络 / 凭据 docker login）"
  exit 1
fi
if [ ${#pull_failed[@]} -gt 0 ]; then
  echo "::warning::以下服务 pull 返回非零，但目标镜像已在本地，继续部署：${pull_failed[*]}"
fi
echo "Pull finished"

# --- 渲染 Alertmanager 告警投递配置（缺陷 U8）---
# 为什么：Alertmanager 自身不展开环境变量，且 deploy 流水线只同步 docker-compose.yml，
# 不会同步任意 deploy/ 文件；故渲染逻辑直接读取 .env，把结果写到
# $DEPLOY_DIR/deploy/alertmanager/alertmanager.yml（容器挂载点），
# 不触碰 $REPO_DIR（git clone 工作区），因此不会触发仓库漂移检测。
# 渲染脚本取自 $REPO_DIR（每次部署都会 git pull，必为最新版本）。
# 优雅降级：ALERT_WEBHOOK_URL 与 ALERT_EMAIL_TO 皆未配置时，渲染为
# 静默丢弃的合法 no-op receiver，Alertmanager 仍正常启动，监控不中断。
if [ -f "$REPO_DIR/scripts/render-alertmanager.sh" ]; then
  echo "===== Rendering Alertmanager config ====="
  if ! bash "$REPO_DIR/scripts/render-alertmanager.sh" \
         "$DEPLOY_DIR/.env" \
         "$DEPLOY_DIR/deploy/alertmanager/alertmanager.yml" \
         "$REPO_DIR/deploy/alertmanager/alertmanager.yml.tmpl"; then
    echo "::error::RENDER_ALERTMANAGER_FAILED: 渲染 Alertmanager 配置失败，已中止部署"
    exit 1
  fi
  echo "::notice::ALERTMANAGER_RENDERED: 告警投递配置已渲染"
else
  echo "::warning::跳过 Alertmanager 渲染（render-alertmanager.sh 不存在，将沿用仓库内置占位配置）"
fi

# 渲染后热重载：Alertmanager 支持 SIGHUP 重载配置，否则新告警渠道要等容器重启才生效。
# 首次部署时容器尚未创建，此处失败属正常，故仅告警不阻断。
if docker ps --format '{{.Names}}' | grep -qx 'zxyz-alertmanager'; then
  if docker kill -s HUP zxyz-alertmanager >/dev/null 2>&1; then
    echo "::notice::ALERTMANAGER_RELOADED: 已热重载 Alertmanager 配置"
  else
    echo "::warning::Alertmanager 热重载失败（配置将在下次容器重启后生效）"
  fi
fi

# --- 重启变更服务 ---
echo "===== Restarting services ====="
docker compose up -d $UP_FLAGS "${UPDATE_SVC[@]}"

# --- 热重载 nginx：重新解析后端 upstream 容器 IP ---
# nginx 的 proxy_pass http://<service>:<port> 依赖 Docker 内置 DNS，
# 该解析在 nginx 启动时完成并缓存；若后端容器被重建（IP 变化）而 nginx 未重载，
# 会一直转发到旧 IP → 持续 502（曾实测：重建后端后前端 502，force-recreate nginx 才恢复）。
# 此处后端重建后主动 reload，避免该隐性故障。
# 首次部署 / nginx 未运行时跳过（不阻断）。
if docker ps --format '{{.Names}}' | grep -qx 'zxyz-frontend-nginx'; then
  if docker exec zxyz-frontend-nginx nginx -t >/dev/null 2>&1 \
     && docker exec zxyz-frontend-nginx nginx -s reload >/dev/null 2>&1; then
    echo "::notice::NGINX_RELOADED: 已热重载 frontend-nginx，重新解析后端 upstream"
  else
    echo "::warning::NGINX_RELOAD_FAILED: frontend-nginx 重载失败，502 风险未消除，请检查 nginx 配置"
  fi
fi

# --- 健康检查（fast_deploy 时跳过） ---
if [ "$FAST_DEPLOY" = "true" ]; then
  echo "===== Fast deploy: skipping health checks, sleep 10s ====="
  sleep 10
else
  HEALTH_OK=true

  # --- 分层健康检查 ---
  # 普通服务：30 次 × 10s = 300s（8G 机器 10 个 JVM 同启实测需 2-6 分钟，60s 窗口曾误判回滚）
  echo "===== Waiting for common services ====="
  for i in $(seq 1 30); do
    if ! docker compose ps 2>/dev/null | grep -qE "(im-service|email-service|user-service|share-service|file-service|team-service|audit-service|admin-service).*(unhealthy|starting)"; then
      echo "Common services ready"
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "ERROR: Common services not all healthy after 300s"
      echo "--- 诊断: 容器状态 ---"
      docker compose ps 2>/dev/null || true
      echo "--- 诊断: 最近日志 ---"
      for svc in im-service email-service user-service share-service file-service team-service audit-service admin-service; do
        echo ">>> $svc <<<"
        docker logs --tail 20 "zxyz-$svc" 2>&1 || true
      done
      HEALTH_OK=false
    fi
    sleep 10
  done

  # Gateway：30 次 × 10s = 300s
  if [ "$HEALTH_OK" = true ]; then
    echo "===== Waiting for gateway ====="
    for i in $(seq 1 30); do
      if ! docker compose ps 2>/dev/null | grep -qE "gateway.*(unhealthy|starting)"; then
        echo "Gateway ready"
        break
      fi
      if [ "$i" -eq 30 ]; then
        echo "ERROR: Gateway startup timeout after 300s"
        echo "--- 诊断: 容器状态 ---"
        docker compose ps 2>/dev/null || true
        echo "--- 诊断: gateway 最近日志 ---"
        docker logs --tail 30 zxyz-gateway 2>&1 || true
        HEALTH_OK=false
      fi
      sleep 10
    done
  fi

  # --- 健康检查失败 → 自动回滚 ---
  if [ "$HEALTH_OK" = false ]; then
    echo "::error::HEALTH_CHECK_FAILED: triggering automatic rollback"
    echo "===== Starting automatic rollback ====="

    if [ -f "$DEPLOY_DIR/.env.previous" ]; then
      PREV_TAG=$(sed -n 's/^APP_IMAGE_TAG=//p' "$DEPLOY_DIR/.env.previous" 2>/dev/null | head -1)
      if [ -n "$PREV_TAG" ]; then
        echo "Rolling back to previous tag: $PREV_TAG"

        # 恢复 .env 到上一版本
        sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=$PREV_TAG|" .env 2>/dev/null || true

        # 拉取上一版本镜像
        echo "===== Pulling previous version images ====="
        for svc in "${UPDATE_SVC[@]}"; do
          docker compose pull "$svc" &
        done
        wait
        echo "Pull complete"

        # 重启服务
        echo "===== Restarting with previous version ====="
        docker compose up -d "${UPDATE_SVC[@]}"

        # 回滚同样重建了容器（IP 变化），需再次热重载 nginx，否则前端仍 502
        if docker ps --format '{{.Names}}' | grep -qx 'zxyz-frontend-nginx'; then
          if docker exec zxyz-frontend-nginx nginx -t >/dev/null 2>&1 \
             && docker exec zxyz-frontend-nginx nginx -s reload >/dev/null 2>&1; then
            echo "::notice::NGINX_RELOADED: 回滚后已热重载 frontend-nginx"
          else
            echo "::warning::NGINX_RELOAD_FAILED: 回滚后 frontend-nginx 重载失败"
          fi
        fi

        # 回滚后健康检查：30 次 × 10s = 300s（同上，10 个 JVM 重建后需要数分钟）
        echo "===== Verifying rollback health ====="
        ROLLBACK_OK=false
        for i in $(seq 1 30); do
          if ! docker compose ps 2>/dev/null | grep -qE "(unhealthy|starting)"; then
            ROLLBACK_OK=true
            echo "Rollback health check passed (${i}0s)"
            break
          fi
          sleep 10
        done

        if [ "$ROLLBACK_OK" = true ]; then
          echo "===== Rollback successful: services restored to $PREV_TAG ====="
          docker compose ps
          echo "::notice::AUTO_ROLLBACK_SUCCESS: reverted to $PREV_TAG"
          exit 0
        else
          echo "::error::ROLLBACK_HEALTH_FAILED: services still unhealthy after rollback"
          docker compose ps 2>/dev/null || true
          exit 1
        fi
      else
        echo "::error::ROLLBACK_FAILED: APP_IMAGE_TAG empty in .env.previous"
        exit 1
      fi
    else
      echo "::error::ROLLBACK_FAILED: .env.previous not found, cannot auto-rollback"
      exit 1
    fi
  fi
fi

echo "===== Container status ====="
docker compose ps
echo "===== Deploy complete ====="
