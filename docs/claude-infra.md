# 基础设施与 CI/CD 详解

本文档为 CLAUDE.md 的补充，提供基础设施和 CI/CD 的详细说明。

## Docker 服务编排

`docker-compose.yml` 编排 14 个服务，统一网络 `zxyz-net`：

**基础设施层（4）**：
- `zxyz-mysql` — MySQL 8.4.0, utf8mb4, 1G 内存限制, 数据持久化到 `${DATA_DIR}/mysql`
- `zxyz-nacos` — Nacos v3.2.1 standalone 模式, 使用 MySQL 后端, 1024M 限制
- `zxyz-redis` — Redis 7.4 Alpine, AOF 持久化, 256M 限制
- `zxyz-rabbitmq` — RabbitMQ 3.13 + management 插件, 512M 限制

**业务服务层（9）**：使用统一 `ZXYZdatabaseBack/Dockerfile`，通过 `MODULE` build arg 选择 Maven 子模块。各服务独立 MySQL 数据库、隔离的 Redis database 编号。端口范围 18080-18087 + gateway 18000。

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

`sql/00-init-zxyz.sh` 挂载到 MySQL 的 `/docker-entrypoint-initdb.d/`（首次启动时自动执行），创建 9 个数据库（8 业务 + nacos），均使用 utf8mb4/unicode_ci。

表结构由各服务的 Flyway 迁移脚本在运行时管理（`src/main/resources/db/migration/`）。

`sql/` 目录下的 `schema_*.sql` 文件用于手动重建，不会被 MySQL entrypoint 自动执行。

## CI/CD 流水线

`.github/workflows/ci-cd.yml` — 4 阶段流水线：

1. **detect-changes** — `dorny/paths-filter` 检测 11 个服务的变更。镜像标签：`dev` 分支 → `dev`，`main` → `latest`，tag → 版本号
2. **quality-check** — 前端（Node 22, lint + test）和后端（JDK 17, compile）并行执行
3. **build-and-push** — 矩阵构建变更的服务镜像。backend-common 变更触发所有后端重建。Docker Buildx + GHA 缓存，推送到 DockerHub（`aclouda/zxyz-*`）
4. **deploy** — SSH 到服务器，选择性拉取+重启变更的服务，分层健康检查（普通服务 50s，gateway 250s）

**关键规则**：
- `docker-compose.yml` 变更不触发镜像重建（运行时配置，非构建依赖）
- PR 仅执行 quality-check，不部署
- 服务器 `.env` 在 `/www/zxyz/.env`，独立于仓库维护，CI/CD 不同步
