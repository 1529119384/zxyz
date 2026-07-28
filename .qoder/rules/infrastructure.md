# 基础设施与 CI/CD

## 中间件

- **MySQL 8.4**: 10 independent databases, Flyway migrations per service. DB schema exclusively managed by Flyway (do NOT maintain standalone `sql/schema_*.sql` files). DB init: `sql/00-init-zxyz.sh`
- **Redis**: localhost:6379, Sa-Token sessions (shared) + Redisson distributed locks
- **Nacos**: localhost:8848, service registry + Config（`spring.config.import:nacos:` 协议）。配置模板在 `nacos-config/` 目录
- **RabbitMQ**: localhost:5672, Topic Exchange `zxyz.topic`
- **Auth**: Sa-Token 1.45.0 (UUID token, Redis session store, HttpOnly cookie)
- **API Docs**: Knife4j 4.5.0 + springdoc 2.8.9

## Docker 编排

`docker-compose.yml` orchestrates 18 services = 5 基础设施（mysql / nacos / nacos-log-cleanup sidecar / redis / rabbitmq）+ 10 后端 + 1 frontend-nginx + 2 日志栈（grafana/loki:3.0.0、grafana/promtail:3.0.0）。统一 `Dockerfile` + `Dockerfile.base`（builder 阶段缓存镜像）with `MODULE` build arg。

- **GHCR**: 镜像推送到 `ghcr.io`（`IMAGE_PREFIX` 变量），每镜像同时打 `${tag}` 与 `${git_sha}` 两个 tag
- **Nginx CSP**: `deploy/nginx/default.conf` 用 `envsubst` 模板化，`OSS_PUBLIC_BASE_URL` 在启动时注入，不要硬编码 OSS 域名
- **内部鉴权**: 所有服务必须在 docker-compose environment 中传入 `INTERNAL_SERVICE_TOKEN`（无默认值）
- **RabbitMQ 连接**: 所有服务必须传入 `RABBITMQ_HOST: rabbitmq`，否则健康检查因连接 localhost 失败
- **JVM 启动优化**: `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"`

## CI/CD Pipeline

`.github/workflows/ci-cd.yml` — 基于路径变更的选择性构建部署：

- **触发**: push 到 `dev`/`main`、`v*` tag、PR、手动 dispatch
- **变更检测**: `dorny/paths-filter` 按服务目录判断哪些镜像需要重建
- **backend-common 变更**: 所有后端服务都重建（共享依赖）
- **docker-compose.yml 变更**: 不触发镜像重建（运行时配置，非构建依赖）
- **构建**: Docker Buildx + GHA 缓存，镜像推送到 GHCR
- **部署**: SSH 到服务器，只拉取+重启变更的服务，分层健康检查（普通服务 30s，gateway 60s）
- **手动触发**: workflow_dispatch 支持 `tag`（必填）、`skip_quality`（跳过 lint/test/compile）、`fast_deploy`（跳过健康检查等待）
- **镜像标签**: dev → `dev`，main → `latest`，tag → 版本号
- **前端构建**: 从根仓库 `ZXYZdatabaseFront/` 目录构建

## 部署脚本

**快速部署**: `scripts/deploy-fast.sh <服务名>` — `--no-health` 跳过健康检查，`--all` 重启所有 11 个 app 服务，`--validate` 仅验证 .env，`--clean-nacos` 清理 Nacos 日志，`--no-pull` 跳过镜像拉取，`--build` 本地构建。

**Windows 本地开发**:
```powershell
.\scripts\dev-up.ps1              # 启动基础设施
.\scripts\dev-up.ps1 down         # 停止基础设施
.\scripts\dev-up.ps1 reset        # 重置数据卷
.\scripts\dev-up.ps1 logs         # 查看所有服务日志
.\scripts\dev-up.ps1 logs mysql   # 查看指定服务日志
```

## 部署注意事项

- 修改 `.env` 后必须用 `docker compose up -d` 重建容器，`restart` 不会重新加载环境变量
- `.env` 中所有 `CHANGE_ME_*` 占位符必须在首次部署时替换
- Nacos 日志已配置 sidecar 定期清理（保留 7 天，单文件限 100MB）
- 10 个 Java 服务同时启动 CPU 压力大，建议分批：基础设施 → gateway → 业务服务
- 首次部署前运行 `./scripts/validate-env.sh .env` 检查配置完整性
- 服务器 `.env` 在 `/www/zxyz/.env`，独立于仓库维护

## 参考文档

- Gateway routing table: `docs/infrastructure.md`
- Tech stack details: `docs/architecture.md`
- Testing conventions: `docs/testing.md`
- Deployment guide: `DEPLOYMENT.md`
- Design proposals: `ISSUE/` 目录
