# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

指绣云章 (ZXYZ) — 云端文件管理平台（团队协作 + IM）。详细文档见 `docs/architecture.md`、`docs/commands.md`、`docs/infrastructure.md`。

## Git

三个 git 仓库：

| 目录 | 分支 | 说明 |
|---|---|---|
| `zxyz/`（根目录） | dev | CI/CD 配置、docker-compose、nginx、SQL、ISSUE |
| `ZXYZdatabaseBack/` | dev | 后端 Java 代码 |
| `ZXYZdatabaseFront/` | main | 前端 Vue 代码 |

WHEN 执行 git 操作, DO cd 到对应目录再执行。
WHEN 修改前后端代码, DO 分别提交到各自子仓库。
WHEN 修改根目录文件（`docker-compose.yml`、`deploy/`、`sql/`、`.github/`）, DO 在根目录提交。

## Build & Test Commands

### Backend (run in `ZXYZdatabaseBack/`)

```bash
mvn clean -DskipTests compile                   # baseline compile check
mvn test                                         # all tests (~65 test classes)
mvn test -pl zxyz-team-service                   # single module tests
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # single test class
mvn clean package -DskipTests                    # package for Docker build
mvn -pl zxyz-project-service spring-boot:run     # run single service
```

### Frontend (run in `ZXYZdatabaseFront/`)

```bash
npm run dev       # dev server, port 5173
npm run build     # production build to dist/
npm run lint      # ESLint check
npm run lint:fix  # ESLint auto-fix
npm run format    # Prettier format
npm run test      # Vitest single run
npm run test:watch # Vitest watch mode
```

## Architecture

**Backend**: Java 17, Spring Boot 3.5.7, Spring Cloud 2025.0.0, Maven multi-module. Group: `uno.acloud`, base package: `uno.acloud.{service}`.

**Frontend**: Vue 3.5 (Composition API + `<script setup>`), Vite 7.3, Element Plus 2.11 (auto-import), Pinia 3.0, Axios 1.13, Vitest 4.1.

### Backend: 11 Maven Modules

| Module | Port | Database | Architecture |
|---|---|---|---|
| `zxyz-common` | — | — | Shared: error codes, Result, permissions, OSS client, service clients, ConfigServiceClient, audit, MQ constants |
| `zxyz-gateway` | 18000 | — | Spring Cloud Gateway (WebFlux), Sa-Token auth, Redis rate limiting |
| `zxyz-project-service` | 18080 | zxyz_project | Traditional layering |
| `zxyz-im-service` | 18081/19090 | zxyz_im | **DDD** (interfaces → application → domain) + Netty WebSocket |
| `zxyz-email-service` | 18082 | zxyz_email | **DDD** (interfaces → application → domain) |
| `zxyz-user-service` | 18083 | zxyz_user | Traditional layering |
| `zxyz-share-service` | 18084 | zxyz_share | Traditional layering |
| `zxyz-file-service` | 18085 | zxyz_file | Traditional layering |
| `zxyz-team-service` | 18086 | zxyz_team | Traditional layering |
| `zxyz-audit-service` | 18087 | — | RabbitMQ consumer for operation logs |
| `zxyz-admin-service` | 18088 | zxyz_config | Config management: ConfigService + Jasypt + Caffeine cache + Redis Pub/Sub |

详细架构说明：[后端](docs/claude-backend.md) · [前端](docs/claude-frontend.md) · [基础设施](docs/claude-infra.md)

## Backend Conventions

WHEN 编写后端代码, DO 在 `ZXYZdatabaseBack/` 目录执行 Maven 命令。
WHEN 新增服务, DO 使用 base package `uno.acloud.{service}`（如 `uno.acloud.project`）。
WHEN 测试命名, DO 使用 `*Test.java`（非 `*Tests.java`）。
WHEN 编写 MapStruct 转换器, DO 使用 `*Converter`/`*Assembler` 类名，与 Lombok 兼容。
WHEN email-service 或 im-service 需要新功能, DO 使用 DDD 风格。
WHEN 其他服务需要新功能, DO 使用传统分层。
WHEN 服务间调用, DO 通过 `*ServiceClient` + `X-Internal-Service-Token` 鉴权。
WHEN 需要异步通信, DO 使用 RabbitMQ Topic Exchange `zxyz.topic`。
WHEN 修改 Gateway 路由, DO 同步更新 `docs/infrastructure.md` 中的路由表。

**Config binding pitfall**: All services use flat `app.internal-service-token` in YAML with `@Value` or `@ConfigurationProperties(prefix="app")`. Do NOT nest it under `app.internal.service-token` — Spring Boot cannot bind nested YAML to flat fields. If you see empty token values at runtime, check the YAML structure.

**`@ConfigurationProperties` prefix matching**: Each properties class binds to a specific YAML prefix. E.g. `TeamServiceProperties(prefix="app.team-service")` expects `app.team-service.internal-service-token`, NOT `app.internal-service-token` or `app.share.team-service.internal-service-token`. When adding new `@ConfigurationProperties` classes, ensure the YAML keys match the exact prefix. Common mistake: nesting service config under a parent service's `app.*` block instead of at the correct prefix level.

**Service URL config**: All service base URLs use `app.*-service.base-url` in `application-common.yml` (e.g., `app.user-service.base-url`). Individual service `application.yml` files map these directly to env vars (e.g., `${TEAM_SERVICE_BASE_URL:http://zxyz-team-service}`), NOT via `${services.*}` indirection — that causes `@ConditionalOnProperty` to fail before Nacos loads.

**MyBatis + MapStruct conflict**: MyBatis `@MapperScan` can hijack MapStruct `@Mapper` interfaces in the same package. Keep MapStruct mappers in a separate package (e.g., `uno.acloud.{service}.convert`).

**Auto-configuration conditions**: `RemoteStpInterfaceAutoConfig` requires `@ConditionalOnBean(RestClient.class)` — Gateway (WebFlux) has no `RestClient`, so it's skipped. `ConfigClientAutoConfiguration` requires `@ConditionalOnBean(RestClient.Builder.class)` — same reason, skipped in Gateway.

**GlobalExceptionHandler coverage**: `basePackages` includes all 10 service packages: `uno.acloud.{user,team,project,file,share,email,audit,gateway,im,admin}`. When adding a new service module, add its package to `basePackages` in `zxyz-common/GlobalExceptionHandler.java`.

**Config encryption**: Sensitive values in Nacos use `ENC(ciphertext)` format. Jasypt 3.0.5 + AES/GCM/NoPadding, key via `JASYPT_PASSWORD` env var. See `docs/jasypt-key-management.md`.

**admin-service data source**: Uses `config.datasource.*` prefix (not `spring.datasource.*`) with `@Primary` DataSource. HikariCP requires `jdbc-url` (not `url`) in YAML when using `@ConfigurationProperties`.

**Config management API**: admin-service exposes `GET/PUT /configs` (no `/api/admin` prefix — Gateway's `RewritePath` strips it). `ConfigServiceClient.get(key)` calls `GET /api/admin/configs/{key}` — ensure this single-key endpoint exists in `ConfigAdminController`. Frontend page at `/setting/config-admin` (requires `requireSystemAdminRole()`). Redis Pub/Sub on `zxyz:config:changed` channel notifies config changes. Config keys follow `app.{domain}.{property}` naming convention.

**Gateway route rewrite**: Routes with `RewritePath=/api/admin/(?<segment>.*)` → `/${segment}` strip the `/api/admin` prefix. Backend controllers must map to the rewritten path (e.g., `@RequestMapping("/configs")`, NOT `@RequestMapping("/api/admin/configs")`). Known issue: `ProviderAdminController` previously had wrong path `/api/admin/providers` → fixed to `/providers`.

**RestClient timeout**: All 9 services use `JdkClientHttpRequestFactory` with `connectTimeout=3s, readTimeout=10s`. When creating new `RestClient` beans, always configure timeouts via `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()` and `factory.setReadTimeout(Duration.ofSeconds(10))`.

**AbstractServiceClient HTTP helpers**: Base class provides `getJson`, `postJson`, `putJson`, `patchJson`, `deleteJson` — all wrapped with Resilience4j retry (3x, 500ms) + circuit breaker (10-window, 50%, 30s). Subclasses inherit these for free. New client classes should extend `AbstractServiceClient` (see admin-service's `EmailProviderClient`/`StorageProviderClient` as reference).

**API response contract**: All backend APIs return `Result<T>` with `code: 1` = success (`ErrorCode.SUCCESS = 1`). Frontend `createApiClient.js` checks `payload?.code === 1`. See `docs/api-contract.md` for full contract.

**Entity security**: `User` and `Share` entities use `@JsonProperty(access = WRITE_ONLY)` on `password` field + `@ToString(exclude = {"password"})` to prevent accidental serialization of BCrypt hashes. Any sensitive field (passwords, tokens, cipher text) on entities/DTOs must have `@JsonProperty(access = WRITE_ONLY)` or `@JsonIgnore`. Config classes that should never serialize use `@JsonIgnore`.

**Internal service token**: `INTERNAL_SERVICE_TOKEN` must NEVER have a default value in YAML (e.g., do NOT use `${INTERNAL_SERVICE_TOKEN:dev-internal-token}`). A predictable default means all gateway-forwarded internal calls use an attacker-known token if the env var is unset. Apply this to all YAML files including `application-dev.yml`.

**Filename XSS**: `FileDomainValidator.validateInputName()` and `FileRenameService.validateRenameName()` reject `< > " ' &` characters. Use these methods for all user-provided file/folder names.

**File upload type whitelist**: `FileUploadService.ALLOWED_EXTENSIONS` controls which file types can be uploaded. `BLOCKED_EXTENSIONS` (with dot prefix, e.g. `.exe`) is checked first and overrides ALLOWED. Note: `.js` is in BLOCKED (XSS risk in browsers) — do NOT add `js` to ALLOWED. `GetSignUrl.java` has a duplicate `BLOCKED_EXTENSIONS` set — keep both in sync when modifying.

**`@RequiresTeamPermission` default**: `skipWhenTeamIdMissing` defaults to `false` — missing teamId throws `BAD_REQUEST`. Endpoints that support personal space (teamId=null) must explicitly set `skipWhenTeamIdMissing = true`. Otherwise personal-space operations like file listing, folder creation, and upload confirmation all break with "teamId 不能为空".

**Nginx DNS cache**: After restarting backend containers, their Docker network IPs change. Nginx caches DNS resolution at startup — restart it after service changes: `docker compose restart frontend-nginx`.

**RabbitMQ health check**: RabbitMQ often times out its Docker health check under load but still functions normally. Services that depend on it may show "unhealthy" status while actually running fine. Use `docker exec zxyz-rabbitmq rabbitmq-diagnostics -q ping` to verify.

**Transaction boundary pattern**: HTTP/MQ calls must NOT happen inside `@Transactional` methods — they hold DB connections during remote I/O. Use `TransactionSynchronizationManager.registerSynchronization(afterCommit)` to defer remote calls. The `afterCommit` callback MUST wrap the remote call in try-catch to prevent exceptions from propagating into Spring's transaction synchronization chain. See `RoleManagementService` for the canonical pattern. For in-process Spring `ApplicationEventPublisher` events (NOT RabbitMQ), no afterCommit is needed since they don't involve network I/O.

**MQ poison message handling**: When a consumer catches `JsonProcessingException` (deserialization failure that will never succeed on retry), throw `AmqpRejectAndDontRequeueException` to route the message to DLQ — do NOT just log and return (which silently ACKs the poison message). Import from `org.springframework.amqp.AmqpRejectAndDontRequeueException`.

**Redis cache eviction**: Do NOT use `@CacheEvict(allEntries = true)` — it clears ALL entries when only one team's data changed. Use `StringRedisTemplate.scan(ScanOptions)` with pattern matching to evict only the affected keys (e.g., `team-permission::{teamId}:*`). See `TeamPermissionCacheService` for reference.

**Env validation**: Run `./scripts/validate-env.sh .env` before first deployment to catch `CHANGE_ME_*` placeholders and missing required variables. The deploy script runs this automatically.

## Frontend Conventions

WHEN 编写前端代码, DO 在 `ZXYZdatabaseFront/` 目录执行 npm 命令。
WHEN 添加 API 接口, DO 按领域放入对应 `api/` 文件，禁止跨领域引用。
WHEN 选择 HTTP 客户端, DO 按场景选：`request.js`（已认证，默认）、`publicRequest.js`（公开/分享）、`imRequest.js`（IM）。所有客户端均 `withCredentials: true`。
WHEN 处理认证, DO 依赖 HttpOnly Cookie（`withCredentials: true`），不手动注入 Authorization Header。
WHEN 显示时间戳, DO 使用 `fmtTime()` 函数（`utils/format.js`），不要直接 `|| '-'` 显示原始值。
WHEN 设置时间戳默认值, DO 使用 `?? null`（而非 `|| null`），`||` 会将空字符串 `""` 和 `0` 误转为 `null`。
WHEN 处理文件操作, DO 使用 `composables/` 中的组合函数，不直接操作 store。
WHEN 提交代码, DO 使用 conventional commits 格式（Husky + commitlint 强制）。
WHEN 处理错误, DO 使用 `BusinessException` → `ErrorCode` → `Result` 模式。
WHEN 添加 API 接口, DO 参考 `src/api/README.md` 中的模块规范。
WHEN 添加 admin 页面, DO 放在 `src/views/setting/` 下，路由在 `src/router/index.js` 中配置。测试页面可不加 `beforeEnter` 权限守卫。
WHEN 添加 setting 子路由, DO 确保 `route.name` 在 Setting 组件 watcher 的 `{ immediate: true }` 执行前已就绪，否则会被重定向到第一个可见 tab。

## Infrastructure & CI/CD

- **MySQL 8.4**: 10 independent databases (including zxyz_config), Flyway migrations per service. DB init: `sql/00-init-zxyz.sh`
- **Redis**: localhost:6379, Sa-Token sessions (shared) + Redisson distributed locks
- **Nacos**: localhost:8848, service registry + Config（`spring.config.import:nacos:` 协议，10 个服务已接入）。配置模板在 `nacos-config/` 目录
- **RabbitMQ**: localhost:5672, Topic Exchange `zxyz.topic`
- **Auth**: Sa-Token 1.43.0 (UUID token, Redis session store, HttpOnly cookie)
- **API Docs**: Knife4j 4.5.0 + springdoc 2.8.9 (available at each service's doc endpoint)
- **Docker**: `docker-compose.yml` orchestrates 18 services (含 nacos-log-cleanup sidecar); unified `Dockerfile` with `MODULE` build arg
- **GHCR**: 镜像推送到 `ghcr.io`（`IMAGE_PREFIX` 变量），workflow 需要 `permissions: packages: write`
- **Nginx CSP**: `deploy/nginx/default.conf` 用 `envsubst` 模板化，`OSS_PUBLIC_BASE_URL` 在启动时注入，不要硬编码 OSS 域名
- **内部鉴权**: 所有服务（含 gateway）必须在 docker-compose environment 中传入 `INTERNAL_SERVICE_TOKEN`（无默认值，生产环境必须配置），gateway 的 `AddRequestHeader` filter 依赖此变量注入 `X-Internal-Service-Token` header
- **RabbitMQ 连接**: 所有服务（含 gateway、admin-service）必须在 docker-compose environment 中传入 `RABBITMQ_HOST: rabbitmq`，否则健康检查因 `RabbitHealthIndicator` 连接 localhost 失败

Gateway routing table and inter-service call map: `docs/infrastructure.md`
Build/run commands: `docs/commands.md`
Tech stack details: `docs/architecture.md`
Deployment guide: `DEPLOYMENT.md`
Design proposals: `ISSUE/` 目录（#09 CI/CD、#10 配置管理、#11 多存储、#12 性能优化、#13 硬编码配置热迁移）
Code review: `ISSUE/CODEX-CODE-REVIEW-RESULTS.md`（42 项问题，P0-P3 分级，阶段一~四已完成安全热修复、事务重构、性能优化、低优先级修复）

**前端测试**: 22 个测试文件，244 个用例（`npm run test`）。覆盖 composables、utils、api、store、router guards。测试文件命名 `*.spec.js`，放在对应目录的 `__tests__/` 下。新增测试使用 `vi.mock()` mock 外部依赖，测试命名用中文。

**前端测试 import 顺序**: vitest/vue 导入在最前，空行后是 `vi.mock()` 调用（紧挨，无空行），再空行后是 `@/` 和第三方包导入。`element-plus` 的 `import` 必须放在 `vi.mock()` 之后（与 `@/` 导入同组），否则 `import-x/order` 报错。

## CI/CD

`.github/workflows/ci-cd.yml` — 基于路径变更的选择性构建部署：

- **触发**: push 到 `dev`/`main`、`v*` tag、PR、手动 dispatch。`on.push/pull_request` 有 paths 白名单，仅监控 `ZXYZdatabaseBack/**`、`ZXYZdatabaseFront/**`、`deploy/**`、`docker-compose.yml`、`.env.example`、`.github/workflows/**`，CLAUDE.md 等文档变更不触发 workflow
- **变更检测**: `dorny/paths-filter` 按服务目录判断哪些镜像需要重建
- **backend-common 变更**: 所有后端服务都重建（共享依赖）
- **docker-compose.yml 变更**: 不触发镜像重建（运行时配置，非构建依赖）
- **构建**: Docker Buildx + GHA 缓存，镜像推送到 GHCR（`ghcr.io/<owner>/zxyz-*`）
- **部署**: SSH 到服务器，只拉取+重启变更的服务，分层健康检查（普通服务 30s，gateway 60s）
- **手动触发**: workflow_dispatch 支持 `skip_quality` 参数跳过 lint/test/compile，直接构建部署
- **镜像标签**: dev 分支 → `dev`，main 分支 → `latest`，tag → 版本号
- **前端构建**: 从根仓库 `ZXYZdatabaseFront/` 目录构建，新组件必须同时提交到前端子仓库和根仓库

本地修改 `.env` 中的 `APP_IMAGE_TAG` 和 `IMAGE_PREFIX` 即可控制部署目标。

**快速部署（开发用）**: CI/CD 构建完成后，SSH 到服务器运行 `scripts/deploy-fast.sh <服务名>` 拉取+重启，跳过完整健康检查等待。`--no-health` 跳过健康检查，`--all` 重启所有服务。`--validate` 仅验证 .env 配置。`--clean-nacos` 清理 Nacos 日志后部署。

**部署注意事项**:
- 修改 `.env` 后必须用 `docker compose up -d` 重建容器，`docker compose restart` 不会重新加载环境变量
- `.env` 中所有 `CHANGE_ME_*` 占位符必须在首次部署时替换，否则服务启动后连接失败
- Nacos 日志会持续增长，已配置 sidecar 定期清理（保留 7 天，单文件限 100MB）
- 10 个 Java 服务同时启动 CPU 压力大，建议分批：基础设施 → gateway → 业务服务
- 首次部署前运行 `./scripts/validate-env.sh .env` 检查配置完整性

**服务器 `.env`** 在 `/www/zxyz/.env`，独立于仓库维护，包含 OSS 密钥等敏感配置。CI/CD 不同步此文件。

**JVM 启动优化**: docker-compose.yml 中 10 个后端服务配置了 `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"`，牺牲少量峰值性能换启动速度。Dockerfile 中 Maven 使用 `-T 1C` 并发编译。

## Work Principles

WHEN 不确定, DO 提问，不猜测。
WHEN 设计方案, DO 先理解"为什么这样设计"再决定"怎么改"。
WHEN 问题复杂, DO 拆解，能简单解决就不要复杂化（KISS）。
WHEN 实现方案, DO 保持现有风格，优先最小改动，避免重复代码。
WHEN 关注架构, DO 检查单一职责（SRP）、耦合度、可扩展性和回归风险。
