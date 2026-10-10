# 基础设施与 CI/CD 详解

本文档为 CLAUDE.md 的补充，提供基础设施和 CI/CD 的详细说明。

## Docker 服务编排

`docker-compose.yml` 编排 17 个服务，统一网络 `zxyz-net`：

**基础设施层（6）**：
- `zxyz-mysql` — MySQL 8.4.0, utf8mb4, **896M 内存限制**, 数据持久化到 `${DATA_DIR}/mysql`
- `zxyz-nacos` — Nacos v3.2.1 standalone 模式, 使用 MySQL 后端, **768M 限制**
- `nacos-log-cleanup` — sidecar, 定期清理 Nacos 日志（保留 7 天，单文件限 100MB）
- `zxyz-redis` — Redis 8.10 Alpine, AOF 持久化, 256M 限制
- `zxyz-rabbitmq` — RabbitMQ 3.13 + management 插件, **384M 限制**
- `flyway` — 手动迁移工具容器（`profiles: [tools]`，默认不启动）

**业务服务层（10）**：使用统一 `ZXYZdatabaseBack/Dockerfile`，通过 `MODULE` build arg 选择 Maven 子模块。各服务独立 MySQL 数据库；Redis database 编号隔离（⚠️ 实际范围见下），端口范围 18080-18088 + gateway 18000。生产 compose 后端服务仅在容器内监听端口（`SERVER_PORT`），无 host 端口映射；对外仅 `frontend-nginx:80`。10 个服务 + gateway 统一 **448M 内存限制**（2026-10-03 由 512M 压降，见下文《维护窗口》）。

> ⚠️ **Redis database 隔离的实际范围是 9 个服务，不含 admin-service**（2026-10-07 复核）：compose 里 `REDIS_DATABASE` 出现 **9 处** —— gateway 0 / project 1 / im 2 / email 3 / user 4 / share 5 / file 6 / team 7 / audit 8。**admin-service 不消费该键**（只用 Redis Pub/Sub 做配置广播），故「各服务独立编号」对它不成立。相关：`ISSUE` 的 I-4 修复正是把 `.env.example` 里写死的 `REDIS_DATABASE=0` 删除，让这 9 处兜底生效。
>
> ⚠️ **Sa-Token 会话存储代码已切到独立库 db9（2026-10-08，`701af68` 合并；生产生效以部署后实测为准）**：9 个服务（上列 9 个，即除 audit 外全部）通过 `sa-token-alone-redis` 把会话统一落到独立连接 `sa-token.alone-redis`（`database: 9`，配置在 `zxyz-common/application-common.yml`）。⇒ 代码层语义为**业务库编号隔离（0-8）不含会话**：`satoken*` 键应全在 db9，其余键按 0-8 分库。✅ **生产已生效（2026-10-10 双批实测，51/52 号台账）**：db9 有 13 个 `satoken:login:*` 活跃键、db0–8 无 satoken 键——「satoken* 全在 db9」已是**实况**（10-09 时 CI 因新 CVE 红灯拦 deploy 的阻塞已随 Boot 4 迁移销账）。audit-service 纯 MQ 消费者、刻意不引该依赖。副作用提醒：随依赖补入的 `commons-pool2` 使 Boot 的 `isPoolEnabled()` 翻 true ⇒ 各服务**主** Redis 客户端从「无池」变「有池」，`spring.data.redis.lettuce.pool.*`（max-active 8 / max-idle 8 / min-idle 2）**真生效**（生产 compose/env 无 `REDIS_POOL_*` 覆盖 ⇒ 吃默认值）。部署后验证会话真的迁移：`redis-cli -n 9 --scan --pattern 'satoken*'` 见非空 key 才算生效（插件对配置异常静默吞，「起来了」≠「迁移了」）。
>
> 📍 **长期边界与演进路线**（库号 0–15 硬上限、`maxmemory 0`+`noeviction` 与会话、门禁不变量口径、部署验证不可省略项）集中记档在 **`docs/redis-session-layout.md`**，本节不重复。

**前端层（1）**：`frontend-nginx` — 唯一对外暴露端口（`${HTTP_PORT:-80}:80`）。

**可观测性（0，已迁出）**：`loki`/`promtail`/`prometheus`/`alertmanager`/`grafana` 自 2026-09-18 起迁入独立文件 `docker-compose.observability.yml`（`profiles: [observability]`，默认不启动、不随 CI 部署）。

**启动顺序**：基础设施层 → 业务服务 + gateway（并行）→ frontend-nginx。所有 Java 服务 30s 优雅停机。

## Nginx 配置

`deploy/nginx/default.conf` 通过 `envsubst` 模板化：
- 速率限制：API 10r/s，认证端点 1r/m
- gzip 压缩、安全头（X-Frame-Options, CSP 等）
- WebSocket 代理到 gateway `/ws`（3600s 超时）
- Vue history mode 回退到 `index.html`
- `/assets/` 长缓存，`index.html` 不缓存
- CSP 中 `${OSS_PUBLIC_BASE_URL}` 在容器启动时注入

`deploy/nginx/entrypoint.sh` 在启动时执行 envsubst 替换。

## 数据库初始化

`sql/00-init-zxyz.sh` 挂载到 MySQL 的 `/docker-entrypoint-initdb.d/`（首次启动时自动执行），创建 10 个数据库（9 个 `zxyz_*` 业务库含 `zxyz_audit` 和 `zxyz_config`，+ `nacos`），均使用 utf8mb4/unicode_ci。

表结构由各服务的 Flyway 迁移脚本在运行时管理（`src/main/resources/db/migration/`）。

手动重建脚本已移除，`sql/` 目录仅保留 `00-init-zxyz.sh`；表结构由各服务 Flyway 迁移在运行时管理，不会被 MySQL entrypoint 自动执行。

## CI/CD 流水线

`.github/workflows/ci-cd.yml` — 4 阶段流水线：

1. **detect-changes** — `dorny/paths-filter` 检测 11 个服务的变更。镜像标签：`dev` 分支 → `dev`，`main` → `latest`，tag → 版本号
2. **quality-check** — 前端（Node 22, lint + test）和后端（JDK 17, compile）并行执行
3. **build-and-push** — 矩阵构建变更的服务镜像。backend-common 变更触发所有后端重建。Docker Buildx + GHA 缓存，推送到 GHCR（`ghcr.io/<owner>/zxyz-*`）
4. **deploy** — SSH 到服务器，选择性拉取+重启变更的服务，分层健康检查（普通服务 6×5s=30s，gateway 12×5s=60s）

**关键规则**：
- `docker-compose.yml` 变更不触发镜像重建（运行时配置，非构建依赖）
- PR 仅执行 quality-check，不部署
- 服务器 `.env` 在 `/www/zxyz/.env`，独立于仓库维护，CI/CD 不同步

## 部署脚本与参数

**快速部署（开发用）**: CI/CD 构建完成后，SSH 到服务器运行 `scripts/deploy-fast.sh <服务名>` 拉取+重启，跳过完整健康检查等待。参数：
- `--no-health` 跳过健康检查
- `--all` 重启所有 11 个 app 服务（10 后端 + frontend-nginx，不含基础设施/loki/promtail）
- `--validate` 仅验证 .env
- `--clean-nacos` 清理 Nacos 日志后部署
- `--no-pull` 跳过镜像拉取
- `--build` 本地 Maven 构建 + docker compose build

**回滚**: `scripts/rollback.sh` 回滚到上一个部署版本，依赖 CI/CD 生成的 `.env.previous`，支持 `--no-pull`/`--validate`/指定服务。

**其他脚本**:
- `backup.sh` — MySQL（含 binlog 增量，PITR 用）+ Redis 备份；`--mysql-only` 仅备 MySQL（预部署用）。
  ⚠️ 位点查询用 `SHOW BINARY LOG STATUS` —— `SHOW MASTER STATUS` 已随 MySQL 8.4 移除（2026-10-03 修复）
- `health-check.sh` — 巡检 **15 个容器**（基础设施 4 + 后端 9 + gateway + frontend-nginx）：
  全部 healthy → exit 0，否则列出 unhealthy 并 exit 1。`loki`/`promtail` 已随可观测性栈迁出，
  **不在**巡检清单内（2026-10-03 清理陈旧条目）。⚠️ 该数组曾长期漏 `zxyz-admin-service`（14 项），
  导致「14/14 全绿」是**假绿灯**；**2026-10-09 已修复**为 15 项（含 admin，实测 `scripts/health-check.sh:39`），
  并新增 `Checked: N containers` 自打印以便日常对账 —— 生产实跑 `Healthy: 15 / 15`
- `check-nacos-config-sync.py` — nacos-config/ 与后端代码默认值的等价性门禁（阻断 [DIFF]）
- `check-env-example-values.py` — `.env.example` 取值形态断言（gitleaks 整文件豁免的补偿门禁，CI 调用）
- `check-nacos-drift.sh` — 线上 Nacos 与仓库 nacos-config/ 的只读逐份 md5 对账
- `setup-acr.sh` — GHCR / 阿里云 ACR 切换
- `validate-env.sh .env` — 校验 `CHANGE_ME_*` 占位符与缺失变量；会从 `.env.example` 自动补全缺失 KEY（会修改 .env），`--sync-only` 仅补全不校验
- `dev-up.sh` / `dev-up.ps1` — 本地 dev 启动基础设施（MySQL / Nacos / Redis / RabbitMQ），`down` 停止、`reset` 重置数据卷、`logs [服务]` 查看日志

**Windows 本地开发**（PowerShell）:
```powershell
.\scripts\dev-up.ps1              # 启动基础设施（MySQL / Nacos / Redis / RabbitMQ）
.\scripts\dev-up.ps1 down         # 停止基础设施
.\scripts\dev-up.ps1 reset        # 重置数据卷（清空所有数据）
.\scripts\dev-up.ps1 logs         # 查看所有服务日志
.\scripts\dev-up.ps1 logs mysql   # 查看指定服务日志
```

### repo → 运行目录同步与三层防线（2026-10-11 加固）

> 背景：2026-10-11 凌晨发现 `/www/zxyz/scripts/backup.sh` 落后仓库修复
> （BINLOG_ARTIFACT / `SHOW BINARY LOG STATUS` 修复未随部署落到运行目录，靠人工 cp 补齐）。
> 此前同步虽存在但失败被吞、无留痕，故制度化如下三层（实现在 `scripts/deploy-on-server.sh`，
> CI 侧配合传 `GITHUB_SHA`，见 `ci-cd.yml` deploy job）。

**同步规则**：CI 部署时引导层先把 `/www/zxyz-repo` 克隆**对准本次部署提交**（`git fetch origin <sha> && git checkout <sha>`，与并发 nacos-import 收敛到同一提交），随后 `deploy-on-server.sh` 做**白名单同步**——仅 `scripts/*.sh` 逐文件 `cp` + `chmod +x` + 逐文件 echo 留痕，任一拷贝失败即中止。`.env`/`logs/`/`data/`/`backups/`/`DEPLOYED_REVISION` 等运行时资产**绝不触碰**；`deploy/` 下模板不同步（`render-alertmanager.sh` 从克隆侧读取，见 DEPLOYMENT.md §11.9）。

**三层防线**（全部 fail-closed）：

| 层 | 内容 | 命中即 |
|---|---|---|
| ① 同步层 | 白名单逐文件同步 `scripts/*.sh`，逐文件留痕 | cp 失败 / 零文件同步 ⇒ 部署中止 |
| ② 检测层 | 克隆 HEAD == `GITHUB_SHA` 校验（防旧 checkout/错误分支）；`backup.sh`/`deploy-on-server.sh`/`rollback.sh` 同步后 `diff -q` 内容校验 | MISMATCH / DIFF ⇒ ERROR 退出 |
| ③ 自证层 | 部署终态写指纹到 `/www/zxyz/deploy-last.log`（append，保留最近 200 行）：`outcome`（success/no-op/rollback）+ `deployed_sha` + `repo_head`/`repo_branch` + `synced_scripts=[...]` + UTC 时间戳 | — |

**排障入口**：`tail -50 /www/zxyz/deploy-last.log`（最近部署指纹）、`cat /www/zxyz/DEPLOYED_REVISION`（线上镜像版本）。手工执行 `deploy-on-server.sh`（无 `GITHUB_SHA`）时检测层降级为告警，不阻断人工路径。

## 部署注意事项与运维提示

- 修改 `.env` 后必须用 `docker compose up -d` 重建容器，`docker compose restart` 不会重新加载环境变量
- `.env` 中所有 `CHANGE_ME_*` 占位符必须在首次部署时替换，否则服务启动后连接失败
- Nacos 日志会持续增长，已配置 sidecar 定期清理（保留 7 天，单文件限 100MB）
- 10 个 Java 服务同时启动 CPU 压力大，建议分批：基础设施 → gateway → 业务服务
- **JVM 启动优化**: docker-compose.yml 中 10 个后端服务配置了 `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"`，牺牲少量峰值性能换启动速度；Dockerfile 中 Maven 使用 `-T 1C` 并发编译
- **Nginx DNS cache**: 重启后端容器后它们的 Docker 网络 IP 会变，Nginx 在启动时缓存 DNS 解析——服务变更后需 `docker compose restart frontend-nginx`
- **RabbitMQ health check**: RabbitMQ 在高负载下经常超时 Docker health check 但仍正常工作，依赖它的服务可能显示 unhealthy 实则正常；用 `docker exec zxyz-rabbitmq rabbitmq-diagnostics -q ping` 验证

---

## 维护窗口：内存 limits 压降 + Redis 库隔离 + config 库最小权限（2026-10-03 批）

### 为什么是「一批」

本批三件事**必须同一次执行**，因为它们都要求**全量重建**、且半更新会让线上停在新旧混合状态：

| # | 改动 | 位置 | 为什么不能单独做 |
|---|---|---|---|
| ① | 内存 `limits` 压降（15 组） | `docker-compose.yml` | 改 compose ⇒ CI 判 `docker-config` ⇒ **11 服务全量重建** |
| ② | 删除 `REDIS_DATABASE=0` | 服务器 `/www/zxyz/.env` | 该键存在时**覆盖** compose 的 9 处 per-service 兜底 ⇒ 10 服务全挤在 db 0 |
| ③ | `CONFIG_DB_USERNAME` 切 `zxyz_config` | 服务器 `/www/zxyz/.env` | admin-service/flyway 现仍以 MySQL **root** 跑 config 库 DDL |

> ⚠️ **`docker compose restart` 不重载环境变量**（②③ 改的是 `.env`）⇒ 必须 `up -d`。

### 一、内存算术（含具体数字，请逐项核对）

| 项 | 改前 | 改后 |
|---|---|---|
| `mysql` | 1024 MiB | **896 MiB** |
| `nacos` | 1024 MiB | **768 MiB** |
| `redis` | 256 MiB | 256 MiB（不动） |
| `rabbitmq` | 512 MiB | **384 MiB** |
| 10 后端 + gateway（每个） | 512 MiB | **448 MiB**（共 10 个，4480 MiB） |
| `frontend-nginx` | 128 MiB | 128 MiB（不动） |
| **limits 累加** | **8064 MiB = 7.875 GiB** | **6912 MiB = 6.750 GiB** |
| **占物理内存**（生产 `free -m` 实测 **total = 7941 MiB**） | 8064 / 7941 = **101.5%（超卖）** | 6912 / 7941 = **87.0%** |
| **余量** | 7941 − 8064 = **−123 MiB（负余量）** | 7941 − 6912 = **1029 MiB（12.96%）** |

计算式：`896 + 768 + 256 + 384 + 448×10 + 128 = 6912 MiB`（降幅 1152 MiB = 1.125 GiB）。

> ⚠️ **分母口径（2026-10-09 订正）**：本表此前用 **8130.56 MiB** 当物理内存分母——那是把「7.94 GB」按 GiB 换算（7.94×1024）的结果，属 GB/MiB 混算；生产 `free -m` 实测 **total = 7941 MiB**，一切百分比以 7941 为准（改回 8130 分母会把余量高估约 190 MiB，详见 46 号 P2-4 / 49 号 P2-3）。历史数字（99.2%/85.0%/66.56/1218.56）保留在上一版记录中，**勿再引用作决策**。
>
> ⚠️ **声明值 ≠ 运行值（2026-10-08/09 双日实测）**：上表「改后」是 compose **声明值**。生产 `HostConfig.Memory` 实测：mysql/nacos 仍 1024 MiB、rabbitmq 仍 512 MiB（三容器创建于 2026-09-14、未重建）⇒ **运行值合计 7424 MiB = 93.5%、余量仅 517 MiB**。按旧的「1.19 GiB 余量」做容量决策会吃穿物理内存（rabbitmq 已于 2026-10-08 真实被 OOM 杀过一次）。**先重建 infra 三容器使声明值生效，再做任何扩容决策**。

> 【2026-10-10 `ISSUE/51-DAILY-AUDIT-2026-10-10.md` P1 内存防线追加（声明值口径更新）】
> - `redis` 256 MiB → **896 MiB**（reservations 128M → 448M 同步上调；db9 会话防线三件套：896M limit + `maxmemory 512mb` + `noeviction`，详见 docker-compose.yml redis 服务注释）；
> - `rabbitmq` limit 384 MiB **不动**，改加内存水位线（挂载 `conf/rabbitmq.conf`：`vm_memory_high_watermark.relative = 0.6`——env 变量方式在 3.13 已废弃且带空格会打崩 erl 启动）；
> - ⇒ **声明值累加 6912 → 7552 MiB = 7.375 GiB**：7552 / 7941 = **95.1%**，余量 **389 MiB（4.9%）**——低于 10-03 的 13.0%，属**有意取舍**：用宿主余量换「Redis OOM = 全站会话丢失」失败面的消失（noeviction + 896M limit 后 Redis 只会拒写、不会被 OOM 杀）。
> - ⚠️ **maxmemory / watermark 都必须容器重建才生效**（前者在 redis 启动命令参数里、后者是挂载文件），而 CI deploy 固定 `--no-deps`、从不重建 infra 容器——生产是否落地以 `redis-cli config get maxmemory` + `docker inspect HostConfig.Memory` + rabbitmq 水位实测为准（防线是否落地 + 实测命令见 `ISSUE/51-DAILY-AUDIT-2026-10-10.md` P1；2026-10-10 实测结论见 `ISSUE/52-DAILY-AUDIT-2026-10-10.md`）。


> ⚠️ **口径提醒**：448M 那组是 **10 个**（project/im/email/share/file/team/audit/admin/user + gateway），
> 不是 11 个 —— `frontend-nginx` 是 128M，**不要**并入 448 那组。本表数字由
> `scripts/` 抽取脚本从 `docker-compose.yml` 实测核算，勿手算。

> **实测前提**：`deploy.resources.limits.memory` 在**非 swarm** 模式下**生效**
> （`docker inspect` 的 `HostConfig.Memory` 为对应字节数）；同为 `deploy.resources` 下的
> `reservations` **不生效**（`MemoryReservation: 0`）。故上表 limits 累加 = 真实约束。
>
> ⚠️ **不要上调 `JAVA_OPTS` 的 `-XX:MaxRAMPercentage`**（现为 75.0）：堆 = limit × 百分比，
> limit 压到 448M 后，百分比上调会把**不可压缩的**非堆开销（Metaspace/CodeCache/线程栈/Direct）
> 挤没 —— 80% 时非堆余量只剩 90 MiB（比改前的 128 MiB 紧 38 MiB），OOM 风险**高于改前**。
> 详见 `docker-compose.yml` 顶部「内存 limits 压降」注释里的数字化反例表。

### 二、执行步骤（服务器上，一次性窗口）

```bash
# 0) 预检：确保凭据齐全（会从 .env.example 补全缺失 KEY）
cd /www/zxyz
bash scripts/validate-env.sh .env

# 1) ② 删除 REDIS_DATABASE 键（让 compose 的 per-service 兜底生效）
sed -i '/^REDIS_DATABASE=/d' .env
grep -c '^REDIS_DATABASE=' .env || echo "OK: REDIS_DATABASE 已移除"

# 2) ③ 切换 config 库专用账户（账户已由 grant-least-privilege.sh 建好）
sed -i 's/^CONFIG_DB_USERNAME=.*/CONFIG_DB_USERNAME=zxyz_config/' .env
grep '^CONFIG_DB_USERNAME=' .env

# 3) 同步最新 docker-compose.yml（CI 部署会自动做；手工操作需先 git pull）
#    然后一次性全量重建（不带 --no-deps：本批就是要连基础设施一起确认）
docker compose up -d

# 4) 等待并观察（10 个 JVM 冷启约 3~5 分钟）
docker compose ps
```

> `docker compose up -d` 会**重建全部 17 个服务**（含 mysql/redis/nacos/rabbitmq 等基础设施容器）。
> 数据在 `DATA_DIR` 数据卷内，不受影响。

### 三、上线后验证（逐条，可直接复制）

```bash
# ① 容器健康：应输出 14 个容器名、末尾 Healthy: 14 / 14、退出码 0
bash scripts/health-check.sh; echo "EXIT=$?"

# ② 复核新 limits 已落到容器（应见 896/768/448/384 MiB，非旧值）
for c in zxyz-mysql zxyz-nacos zxyz-rabbitmq zxyz-gateway zxyz-frontend-nginx; do
  printf '%-24s %s\n' "$c" \
    "$(docker inspect --format='{{.HostConfig.Memory}}' "$c")"
done
# 期望：zxyz-mysql=939524096(896M) zxyz-nacos=805306368(768M)
#       zxyz-rabbitmq=402653184(384M) zxyz-gateway=469762048(448M)
#       zxyz-frontend-nginx=134217728(128M)

# ③ Redis 库隔离实测：9 个服务应分别落在 db 0,1,2,3,4,5,6,7,8（不再是全 0）
for c in zxyz-gateway zxyz-project-service zxyz-im-service zxyz-email-service \
         zxyz-user-service zxyz-share-service zxyz-file-service \
         zxyz-team-service zxyz-audit-service; do
  printf '%-26s REDIS_DATABASE=%s\n' "$c" \
    "$(docker inspect --format='{{range .Config.Env}}{{println .}}{{end}}' "$c" \
       | sed -n 's/^REDIS_DATABASE=//p')"
done
# 期望：gateway=0 project=1 im=2 email=3 user=4 share=5 file=6 team=7 audit=8

# ④ 宿主机余量：容器起来后 MemAvailable 应显著为正（改前余量仅 66 MiB）
free -m
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}'

# ⑤ config 库最小权限：应不再有 WARN（该 WARN 由 validate-env.sh 在 root 时打印）
bash scripts/validate-env.sh .env 2>&1 | grep -i 'CONFIG_DB_USERNAME' || echo "OK: 无 root WARN"

# ⑥ 数据库能连（admin-service 与 flyway 用的是新账户）
docker compose logs --tail=50 zxyz-admin-service | grep -iE 'flyway|migrat|error' || true
```

### 四、回退

```bash
cd /www/zxyz
# 回退 ③：config 库改回 root（密码必须显式写 MYSQL_ROOT_PASSWORD 同值，
#         compose 不支持嵌套 ${A:-${B}}）
sed -i 's/^CONFIG_DB_USERNAME=.*/CONFIG_DB_USERNAME=root/' .env
sed -i "s/^CONFIG_DB_PASSWORD=.*/CONFIG_DB_PASSWORD=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)/" .env

# 回退 ①：git 回退 docker-compose.yml 到本批之前的提交
git -C /www/zxyz-repo checkout <本批之前的 sha> -- docker-compose.yml
cp /www/zxyz-repo/docker-compose.yml /www/zxyz/docker-compose.yml

# 回退 ②：恢复 REDIS_DATABASE=0（会重新把所有服务收回 db 0；仅在确认库隔离有问题时用）
echo 'REDIS_DATABASE=0' >> .env

docker compose up -d
bash scripts/health-check.sh
```

> **整批快速回退**：`scripts/rollback.sh` 会按 `.env.previous` 里的上一版 sha 回滚全部应用服务，
> 并在成功路径写入 `DEPLOYED_REVISION`（`ROLLBACK=true`）作为审计锚点。
