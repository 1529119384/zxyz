# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

指绣云章 (ZXYZ) — 云端文件管理平台（团队协作 + IM）。详细文档见 `docs/architecture.md`、`docs/infrastructure.md`。

## Git

### 仓库架构：根仓库为唯一提交与 CI 真相源

本项目采用 **monorepo + 嵌套独立仓库** 模式（非 git submodule）：

| 仓库 | 远程 | 分支 | 角色 |
|---|---|---|---|
| `zxyz/`（根） | `github.com/1529119384/zxyz.git` | dev | **唯一 CI/CD 真相源**：直接跟踪所有文件（含子目录源码），`.github/workflows/ci-cd.yml` 仅由根仓库 push 触发 |
| `ZXYZdatabaseBack/` | `github.com/1529119384/ZXYZdatabaseBack.git` | dev | 后端子仓库：独立开发历史，不触发 CI |
| `ZXYZdatabaseFront/` | `github.com/1529119384/ZXYZdatabaseFront.git` | main | 前端子仓库：独立开发历史，不触发 CI |

根仓库通过 `git ls-files` 直接管理 `ZXYZdatabaseBack/**` 和 `ZXYZdatabaseFront/**` 下的源码文件。子目录内的 `.git/` 被根 `.gitignore` 排除（`ZXYZdatabaseBack/.git/`、`ZXYZdatabaseFront/.git/`），两个仓库树互不嵌套引用。

### CI 触发面（与 `.github/workflows/ci-cd.yml` paths-filter 一致）

根仓库 `on.push` / `on.pull_request` 的 paths 白名单：

```
ZXYZdatabaseBack/**
ZXYZdatabaseFront/**
deploy/**
docker-compose.yml
.env.example
.github/workflows/**
```

不在此白名单内的文件变更（如 `CLAUDE.md`、`docs/**`、`nacos-config/**`、`scripts/**`、`sql/**`）**不触发** workflow。`dorny/paths-filter` 进一步按服务目录判断哪些镜像需要重建。

### 提交规范与同步顺序

WHEN 修改后端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseBack && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseBack/ && git commit` — **根仓库为 CI 真相源，遗漏此步则不触发构建**

WHEN 修改前端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseFront && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseFront/ && git commit` — 根仓库触发 CI

WHEN 修改根目录文件（`docker-compose.yml`、`deploy/`、`.env.example`、`.github/`）, DO 仅在根目录提交（无需同步子仓库）。

WHEN 执行 git 操作, DO cd 到对应目录再执行，避免跨仓库误操作。

### 同步遗漏检查

提交后执行以下检查确认根仓库与子仓库一致：

```bash
# 在根目录执行：检查根仓库是否有未同步的子仓库变更
git status ZXYZdatabaseBack/ ZXYZdatabaseFront/

# 对比子仓库 HEAD 与根仓库跟踪内容（无输出 = 一致）
cd ZXYZdatabaseBack && git diff HEAD --stat && cd ..
cd ZXYZdatabaseFront && git diff HEAD --stat && cd ..
```

若 `git status` 显示子目录有 `modified`/`new file` 未暂存，说明子仓库已提交但根仓库遗漏同步，需补提交到根仓库。

## Build & Test Commands

### Backend (run in `ZXYZdatabaseBack/`)

```bash
mvn clean -DskipTests compile                   # baseline compile check
mvn test                                         # all tests (~83 test classes)
mvn test -pl zxyz-team-service                   # single module tests
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # single test class
mvn clean package -DskipTests                    # package for Docker build
mvn -pl zxyz-project-service spring-boot:run     # run single service
```

### Frontend (run in `ZXYZdatabaseFront/`)

```bash
npm run dev          # dev server, port 5173
npm run build        # production build to dist/
npm run preview      # preview build, port 4173
npm run lint         # ESLint check
npm run lint:fix     # ESLint auto-fix
npm run format       # Prettier format
npm run test         # Vitest single run
npm run test:watch   # Vitest watch mode
npm run test:coverage # Vitest + @vitest/coverage-v8 coverage

各服务可单独启动(端口见上表 Backend Modules)：`mvn -pl zxyz-{service}-service spring-boot:run`（audit 服务通常不单独 run）。
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
| `zxyz-audit-service` | 18087 | zxyz_audit | RabbitMQ consumer for operation logs + 持久化到 zxyz_audit 库 |
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
WHEN 新增 Maven 模块, DO 同时加入根 `pom.xml` 的 `<modules>` 列表（参考 `zxyz-web-tools/` 反例：未注册构建、包名 `uno.acloud.monitor.platform.web.tools.*` 违反 `uno.acloud.{service}` 约定，应清理）。
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

**Narrow internal endpoints (Projection pattern)**: Internal service-to-service calls must use narrow endpoints that return projection objects, NOT fat endpoints that return full domain DTOs. Fat endpoints (`FileInfoDTO`, `TeamVO`, etc.) leak internal domain structure across service boundaries. When a service consumer only needs a subset of fields, the provider must expose a dedicated narrow endpoint. Naming convention: `*-projection` suffix (e.g., `/{fileId}/share-projection`, `/batch-share-projection`, `/{parentId}/share-children-projection`). The client side maps the narrow response to a lightweight projection model (e.g., `ShareFileProjection`) via manual `mapToProjection(JsonNode)` — do NOT introduce MapStruct mappers for cross-service projections. See `ShareFileServiceClient` + `InternalFileController` share projection endpoints for the canonical pattern. Existing fat endpoints that are superseded by narrow ones should be deleted to prevent regression.

**Internal narrow endpoints reference**（内部端点前缀 `/api/internal/**`，被 gateway 的 SaToken filter 拒绝公网访问，仅 Docker 内网服务间直连）：

- **file-service** `InternalFileController`（share 投影）：`GET /{fileId}/share-projection`, `POST /batch-share-projection`, `GET /{parentId}/share-children-projection`, `POST /batch-share-children-projection`, `GET /{fileId}/share-download-url` (returns `String`)
- **file-service** `InternalStorageController`（存储用量）：`/sum-active`、`/sum-personal`、`/personal-usage-list`、`/team-usage-list`（GET/POST 双形态）
- **file-service** `InternalImFileCardController`：`POST /snapshot`、`POST /resolve`
- **team-service** `InternalTeamController`（团队投影）：`GET /ids/by-user/{userId}` (List<Long>)、`/{teamId}`、`/{teamId}/members`、`/{teamId}/quota`、`/{teamId}/member-count`、`/{teamId}/owner-id` 等 10 个
- **team-service** `InternalPermissionController`（权限）：`POST /check`、`POST /has`、系统角色/权限管理、`POST /team-roles`、`POST /team/grant-role` 等 18 个
- **user-service** `InternalUserController`：`/{userId}/info`、`POST /batch`、`POST /create-team-user`、`/all-ids`、`/verified-emails`、`/{id}/quota`、`/search` 等 11 个
- **share-service** `InternalShareController`：`POST /cleanup-by-files`
- **project-service** `InternalProjectController`：`/{userId}/active-projects-led-count`、`POST /{projectId}/access-check`、`/team/{teamId}/quota-sum`
- **project-service** `InternalStorageController`：`POST /check-quota`
- **im-service** 用 **`/api/im/internal/**`** 前缀（故意避开 `/api/internal/**`，以绕开 SaToken filter 的 internal 拒绝规则，仍受登录态校验保护）：`InternalTeamSyncController`、`InternalUserProfileSyncController`、`InternalSystemNotificationController`

Gateway 还有两条 admin→业务的"桥接路由"：`/api/admin/email/**` → email-service `/api/email/internal/**`、`/api/admin/database/**` → project-service `/api/database/internal/**`，配合 `RewritePath` + `AddRequestHeader=X-Internal-Service-Token` 注入内部 token。

**ServiceClient 包位置约定**：im/user/team/share 用 `xxx.infrastructure.client.*`（admin 用 `admin.client.*`）；但 project-service 与 file-service 把客户端放在 `xxx.service.impl.*` 子包（与本地 service 类并列），新建客户端时按各自服务现有约定放置。

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

**Env validation**: Run `./scripts/validate-env.sh .env` before first deployment to catch `CHANGE_ME_*` placeholders and missing required variables. The deploy script runs this automatically. 该脚本会从 `.env.example` 自动补全 `.env` 中缺失的 KEY（会修改 .env），`--sync-only` 参数仅补全不校验。

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

- **MySQL 8.4**: 10 independent databases (including zxyz_config), Flyway migrations per service. DB schema is exclusively managed by Flyway migrations (do NOT maintain standalone `sql/schema_*.sql` files). DB init: `sql/00-init-zxyz.sh`
- **Redis**: localhost:6379, Sa-Token sessions (shared) + Redisson distributed locks
- **Nacos**: localhost:8848, service registry + Config（`spring.config.import:nacos:` 协议，10 个服务已接入）。配置模板在 `nacos-config/` 目录
- **RabbitMQ**: localhost:5672, Topic Exchange `zxyz.topic`
- **Auth**: Sa-Token 1.45.0 (UUID token, Redis session store, HttpOnly cookie)
- **API Docs**: Knife4j 4.5.0 + springdoc 2.8.9 (available at each service's doc endpoint)
- **Docker**: `docker-compose.yml` orchestrates 18 services = 5 基础设施（mysql / nacos / nacos-log-cleanup sidecar / redis / rabbitmq）+ 10 后端 + 1 frontend-nginx + 2 日志栈（grafana/loki:3.0.0、grafana/promtail:3.0.0）。统一 `Dockerfile` + `Dockerfile.base`（builder 阶段缓存镜像）with `MODULE` build arg
- **GHCR**: 镜像推送到 `ghcr.io`（`IMAGE_PREFIX` 变量），workflow 需要 `permissions: packages: write`，每镜像同时打 `${tag}` 与 `${git_sha}` 两个 tag（后者用于精确回滚）
- **Nginx CSP**: `deploy/nginx/default.conf` 用 `envsubst` 模板化，`OSS_PUBLIC_BASE_URL` 在启动时注入，不要硬编码 OSS 域名
- **内部鉴权**: 所有服务（含 gateway）必须在 docker-compose environment 中传入 `INTERNAL_SERVICE_TOKEN`（无默认值，生产环境必须配置），gateway 的 `AddRequestHeader` filter 依赖此变量注入 `X-Internal-Service-Token` header
- **RabbitMQ 连接**: 所有服务（含 gateway、admin-service）必须在 docker-compose environment 中传入 `RABBITMQ_HOST: rabbitmq`，否则健康检查因 `RabbitHealthIndicator` 连接 localhost 失败

Gateway routing table and inter-service call map: `docs/infrastructure.md`
Tech stack details: `docs/architecture.md`
Testing conventions: `docs/testing.md`
Deployment guide: `DEPLOYMENT.md`
Design proposals: `ISSUE/` 目录（#09 CI/CD、#10 配置管理、#11 多存储、#12 性能优化、#13 硬编码配置热迁移）
Code review: `ISSUE/CODEX-CODE-REVIEW-RESULTS.md`（42 项问题，P0-P3 分级，阶段一~四已完成安全热修复、事务重构、性能优化、低优先级修复）

**前端测试**: 26 个测试文件，278 个用例（`npm run test`）。覆盖 composables、utils、api、store、router guards。测试文件命名 `*.spec.js`，放在对应目录的 `__tests__/` 下。新增测试使用 `vi.mock()` mock 外部依赖，测试命名用中文。测试约定详见 `docs/testing.md`。

**前端测试 import 顺序**: vitest/vue 导入在最前，空行后是 `vi.mock()` 调用（紧挨，无空行），再空行后是 `@/` 和第三方包导入。`element-plus` 的 `import` 必须放在 `vi.mock()` 之后（与 `@/` 导入同组），否则 `import-x/order` 报错。

## CI/CD

`.github/workflows/ci-cd.yml` — 基于路径变更的选择性构建部署：

- **触发**: push 到 `dev`/`main`、`v*` tag、PR、手动 dispatch。`on.push/pull_request` 有 paths 白名单，仅监控 `ZXYZdatabaseBack/**`、`ZXYZdatabaseFront/**`、`deploy/**`、`docker-compose.yml`、`.env.example`、`.github/workflows/**`，CLAUDE.md 等文档变更不触发 workflow
- **变更检测**: `dorny/paths-filter` 按服务目录判断哪些镜像需要重建
- **backend-common 变更**: 所有后端服务都重建（共享依赖）
- **docker-compose.yml 变更**: 不触发镜像重建（运行时配置，非构建依赖）
- **构建**: Docker Buildx + GHA 缓存，镜像推送到 GHCR（`ghcr.io/<owner>/zxyz-*`）
- **部署**: SSH 到服务器，只拉取+重启变更的服务，分层健康检查（普通服务 30s，gateway 60s）
- **手动触发**: workflow_dispatch 支持 3 个输入：`tag`（必填，镜像标签如 `latest`/`dev`/版本号）、`skip_quality`（跳过 lint/test/compile）、`fast_deploy`（跳过部署阶段健康检查等待）
- **镜像标签**: dev 分支 → `dev`，main 分支 → `latest`，tag → 版本号。每镜像同时打 `${tag}` 与 `${git_sha}` 两个 tag（后者用于精确回滚）
- **前端构建**: 从根仓库 `ZXYZdatabaseFront/` 目录构建，新组件必须同时提交到前端子仓库和根仓库

本地修改 `.env` 中的 `APP_IMAGE_TAG` 和 `IMAGE_PREFIX` 即可控制部署目标。

**快速部署（开发用）**: CI/CD 构建完成后，SSH 到服务器运行 `scripts/deploy-fast.sh <服务名>` 拉取+重启，跳过完整健康检查等待。参数：`--no-health` 跳过健康检查，`--all` 重启所有 11 个 app 服务（10 后端 + frontend-nginx，不含基础设施/loki/promtail），`--validate` 仅验证 .env，`--clean-nacos` 清理 Nacos 日志后部署，`--no-pull` 跳过镜像拉取，`--build` 本地 Maven 构建 + docker compose build。`scripts/` 还含 `backup.sh`（MySQL+Redis 备份）、`dev-up.sh`（本地 dev 启动基础设施）、`health-check.sh`（轮询 16 容器健康）、`setup-acr.sh`（GHCR/阿里云 ACR 切换）。

**Windows 本地开发**: PowerShell 等效脚本 `scripts/dev-up.ps1`，功能与 `dev-up.sh` 一致：
```powershell
.\scripts\dev-up.ps1              # 启动基础设施（MySQL / Nacos / Redis / RabbitMQ）
.\scripts\dev-up.ps1 down         # 停止基础设施
.\scripts\dev-up.ps1 reset        # 重置数据卷（清空所有数据）
.\scripts\dev-up.ps1 logs         # 查看所有服务日志
.\scripts\dev-up.ps1 logs mysql   # 查看指定服务日志
```

**部署注意事项**:
- 修改 `.env` 后必须用 `docker compose up -d` 重建容器，`docker compose restart` 不会重新加载环境变量
- `.env` 中所有 `CHANGE_ME_*` 占位符必须在首次部署时替换，否则服务启动后连接失败
- Nacos 日志会持续增长，已配置 sidecar 定期清理（保留 7 天，单文件限 100MB）
- 10 个 Java 服务同时启动 CPU 压力大，建议分批：基础设施 → gateway → 业务服务
- 首次部署前运行 `./scripts/validate-env.sh .env` 检查配置完整性

**服务器 `.env`** 在 `/www/zxyz/.env`，独立于仓库维护，包含 OSS 密钥等敏感配置。CI/CD 不同步此文件。

**JVM 启动优化**: docker-compose.yml 中 10 个后端服务配置了 `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1"`，牺牲少量峰值性能换启动速度。Dockerfile 中 Maven 使用 `-T 1C` 并发编译。

## 服务间接口设计规范

### 1. 窄端点优先
内部端点优先为调用方设计窄接口。
- 新增 ServiceClient 方法时，先确认对方是否有或应新增窄端点
- 避免调用胖接口后丢弃大部分字段（超过 30% 字段被丢弃即应改窄端点）

### 2. 调用方投影
ServiceClient 公开方法返回调用方自己的 POJO 或基本类型：
- ❌ 禁止：`public FileInfoDTO getFileInfoById(Long id)`（返回 zxyz-common 公共 DTO）
- ✅ 允许：`public ShareFileProjection getShareFileProjection(Long id)`（本服务投影）
- ✅ 允许：`public List<Long> listUserTeamIds(Long userId)`（标量集合）
- 投影 POJO 放在 `{service}/infrastructure/client/model/`（非 DDD）或 `domain/model/`（DDD）
- 投影字段必须经过"字段消费方对照"全调用点核实，公用字段保留、零使用字段剔除

### 3. 手动字段映射
JsonNode → Projection 使用手动字段提取，不使用 treeToValue：
- ❌ 禁止：`objectMapper().treeToValue(data, FileInfoDTO.class)`
- ✅ 允许：`data.path("id").asLong()` + `data.path("name").asText(null)`

### 4. 继承不传递 DTO
- ServiceClient 优先 `extends AbstractServiceClient`，只在确有共享场景才继承中间基类
- 子类内**禁止**新增返回上游公共 DTO 的方法
- 中间基类的 public 方法不应返回上游 DTO（`FileStorageClient` 的 3 个 public 方法都返回标量/Map，是良好中间基类范本）

### 5. 窄端点命名
- `/{资源}/{消费者}-projection`：为特定消费者设计的投影
- `/{资源}/ids/...`：返回 ID 列表
- `/{资源}/.../{单一量}`：返回标量值

### 6. ACL 双类不可去重
- 提供方 `XxxProjectionVO` 与调用方 `XxxProjection` 是两个独立类型，字段集故意相同
- 通过 JSON wire 解耦，版本独立演进，不合并、不去重、不放入 zxyz-common

### 7. 投影扩张约束
新增消费者投影前，字段差异 ≥ 3 才新建；否则复用最接近的现有 Projection VO。
超过 5 个并列 `*-projection` 方法时，引入 `InternalXxxQueryService` 内部 service 类按场景分发。

### 8. 判断职责污染要做全调用点核查
方法名表面"瘦"不代表职责不污染（`UserServiceClient.createTeamUser` 5 字段全用）；手动 grep 全部调用方，确认字段消费情况后再下判断。

### 9. 已落地窄端点清单
| 端点 | 提供方 | 返回类型 | 调用方 |
|---|---|---|---|
| `GET /api/internal/teams/ids/by-user/{userId}` | team-service | `List<Long>` | project-service |
| `GET /api/internal/files/{fileId}/share-projection` | file-service | `ShareFileProjectionVO` | share-service |
| `POST /api/internal/files/batch-share-projection` | file-service | `List<ShareFileProjectionVO>` | share-service |
| `GET /api/internal/files/{parentId}/share-children-projection` | file-service | `List<ShareFileProjectionVO>` | share-service |
| `POST /api/internal/files/batch-share-children-projection` | file-service | `Map<Long, List<ShareFileProjectionVO>>` | share-service |
| `GET /api/internal/files/{fileId}/share-download-url` | file-service | `String` | share-service |

## Work Principles

WHEN 不确定, DO 提问，不猜测。
WHEN 设计方案, DO 先理解"为什么这样设计"再决定"怎么改"。
WHEN 问题复杂, DO 拆解，能简单解决就不要复杂化（KISS）。
WHEN 实现方案, DO 保持现有风格，优先最小改动，避免重复代码。
WHEN 关注架构, DO 检查单一职责（SRP）、耦合度、可扩展性和回归风险。
