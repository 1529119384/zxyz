# 基础设施与 CI/CD 详解

本文档为 CLAUDE.md 的补充，提供基础设施和 CI/CD 的详细说明。

## Docker 服务编排

`docker-compose.yml` 编排 18 个服务，统一网络 `zxyz-net`：

**基础设施层（5）**：
- `zxyz-mysql` — MySQL 8.4.0, utf8mb4, 1G 内存限制, 数据持久化到 `${DATA_DIR}/mysql`
- `zxyz-nacos` — Nacos v3.2.1 standalone 模式, 使用 MySQL 后端, 1024M 限制
- `nacos-log-cleanup` — sidecar, 定期清理 Nacos 日志（保留 7 天，单文件限 100MB）
- `zxyz-redis` — Redis 7.4 Alpine, AOF 持久化, 256M 限制
- `zxyz-rabbitmq` — RabbitMQ 3.13 + management 插件, 512M 限制

**业务服务层（10）**：使用统一 `ZXYZdatabaseBack/Dockerfile`，通过 `MODULE` build arg 选择 Maven 子模块。各服务独立 MySQL 数据库、隔离的 Redis database 编号。端口范围 18080-18088 + gateway 18000。生产 compose 后端服务仅在容器内监听端口（`SERVER_PORT`），无 host 端口映射；对外仅 `frontend-nginx:80`。

**前端层（1）**：`frontend-nginx` — 唯一对外暴露端口（`${HTTP_PORT:-80}:80`）。

**可观测性（2）**：
- `loki` — Grafana Loki 3.0.0 日志聚合
- `promtail` — 通过 Docker socket 抓取容器日志，推送到 Loki

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

`sql/` 目录下的 `schema_*.sql` 文件用于手动重建，不会被 MySQL entrypoint 自动执行。

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
- `backup.sh` — MySQL + Redis 备份
- `health-check.sh` — 轮询 16 容器健康
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

## 部署注意事项与运维提示

- 修改 `.env` 后必须用 `docker compose up -d` 重建容器，`docker compose restart` 不会重新加载环境变量
- `.env` 中所有 `CHANGE_ME_*` 占位符必须在首次部署时替换，否则服务启动后连接失败
- Nacos 日志会持续增长，已配置 sidecar 定期清理（保留 7 天，单文件限 100MB）
- 10 个 Java 服务同时启动 CPU 压力大，建议分批：基础设施 → gateway → 业务服务
- **JVM 启动优化**: docker-compose.yml 中 10 个后端服务配置了 `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"`，牺牲少量峰值性能换启动速度；Dockerfile 中 Maven 使用 `-T 1C` 并发编译
- **Nginx DNS cache**: 重启后端容器后它们的 Docker 网络 IP 会变，Nginx 在启动时缓存 DNS 解析——服务变更后需 `docker compose restart frontend-nginx`
- **RabbitMQ health check**: RabbitMQ 在高负载下经常超时 Docker health check 但仍正常工作，依赖它的服务可能显示 unhealthy 实则正常；用 `docker exec zxyz-rabbitmq rabbitmq-diagnostics -q ping` 验证
