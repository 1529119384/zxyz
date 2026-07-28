# 后端编码约定

> 单向同步：根目录 `CLAUDE.md` 为唯一权威源（同步方向 CLAUDE.md → .qoder/rules）。修改约定请先更新 CLAUDE.md 对应章节，再同步至本文件，禁止只改本文件。

## 基本规范

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

## 配置绑定陷阱

**Config binding pitfall**: All services use flat `app.internal-service-token` in YAML with `@Value` or `@ConfigurationProperties(prefix="app")`. Do NOT nest it under `app.internal.service-token` — Spring Boot cannot bind nested YAML to flat fields. If you see empty token values at runtime, check the YAML structure.

**`@ConfigurationProperties` prefix matching**: Each properties class binds to a specific YAML prefix. E.g. `TeamServiceProperties(prefix="app.team-service")` expects `app.team-service.internal-service-token`, NOT `app.internal-service-token` or `app.share.team-service.internal-service-token`. When adding new `@ConfigurationProperties` classes, ensure the YAML keys match the exact prefix. Common mistake: nesting service config under a parent service's `app.*` block instead of at the correct prefix level.

**Service URL config**: All service base URLs use `app.*-service.base-url` in `application-common.yml` (e.g., `app.user-service.base-url`). Individual service `application.yml` files map these directly to env vars (e.g., `${TEAM_SERVICE_BASE_URL:http://zxyz-team-service}`), NOT via `${services.*}` indirection — that causes `@ConditionalOnProperty` to fail before Nacos loads.

## MyBatis + MapStruct 冲突

MyBatis `@MapperScan` can hijack MapStruct `@Mapper` interfaces in the same package. Keep MapStruct mappers in a separate package (e.g., `uno.acloud.{service}.convert`).

## Auto-configuration 条件

`RemoteStpInterfaceAutoConfig` requires `@ConditionalOnBean(RestClient.class)` — Gateway (WebFlux) has no `RestClient`, so it's skipped. `ConfigClientAutoConfiguration` requires `@ConditionalOnBean(RestClient.Builder.class)` — same reason, skipped in Gateway.

## GlobalExceptionHandler 覆盖

`basePackages` includes all 10 service packages: `uno.acloud.{user,team,project,file,share,email,audit,gateway,im,admin}`. When adding a new service module, add its package to `basePackages` in `zxyz-common/GlobalExceptionHandler.java`.

## 安全约定

**Config encryption**: Sensitive values in Nacos use `ENC(ciphertext)` format. Jasypt 3.0.5 + AES/GCM/NoPadding, key via `JASYPT_PASSWORD` env var. See `docs/jasypt-key-management.md`.

**Entity security**: `User` and `Share` entities use `@JsonProperty(access = WRITE_ONLY)` on `password` field + `@ToString(exclude = {"password"})` to prevent accidental serialization of BCrypt hashes. Any sensitive field (passwords, tokens, cipher text) on entities/DTOs must have `@JsonProperty(access = WRITE_ONLY)` or `@JsonIgnore`. Config classes that should never serialize use `@JsonIgnore`.

**Internal service token**: `INTERNAL_SERVICE_TOKEN` must NEVER have a default value in YAML (e.g., do NOT use `${INTERNAL_SERVICE_TOKEN:dev-internal-token}`). A predictable default means all gateway-forwarded internal calls use an attacker-known token if the env var is unset. Apply this to all YAML files including `application-dev.yml`.

**Filename XSS**: `FileDomainValidator.validateInputName()` and `FileRenameService.validateRenameName()` reject `< > " ' &` characters. Use these methods for all user-provided file/folder names.

**File upload type whitelist**: `FileUploadService.ALLOWED_EXTENSIONS` controls which file types can be uploaded. `BLOCKED_EXTENSIONS` (with dot prefix, e.g. `.exe`) is checked first and overrides ALLOWED. Note: `.js` is in BLOCKED (XSS risk in browsers) — do NOT add `js` to ALLOWED. `GetSignUrl.java` has a duplicate `BLOCKED_EXTENSIONS` set — keep both in sync when modifying.

## 权限注解

**`@RequiresTeamPermission` default**: `skipWhenTeamIdMissing` defaults to `false` — missing teamId throws `BAD_REQUEST`. Endpoints that support personal space (teamId=null) must explicitly set `skipWhenTeamIdMissing = true`. Otherwise personal-space operations like file listing, folder creation, and upload confirmation all break with "teamId 不能为空".

## 事务与消息

**Transaction boundary pattern**: HTTP/MQ calls must NOT happen inside `@Transactional` methods — they hold DB connections during remote I/O. Use `TransactionSynchronizationManager.registerSynchronization(afterCommit)` to defer remote calls. The `afterCommit` callback MUST wrap the remote call in try-catch to prevent exceptions from propagating into Spring's transaction synchronization chain. See `RoleManagementService` for the canonical pattern. For in-process Spring `ApplicationEventPublisher` events (NOT RabbitMQ), no afterCommit is needed since they don't involve network I/O.

**MQ poison message handling**: When a consumer catches `JsonProcessingException` (deserialization failure that will never succeed on retry), throw `AmqpRejectAndDontRequeueException` to route the message to DLQ — do NOT just log and return (which silently ACKs the poison message). Import from `org.springframework.amqp.AmqpRejectAndDontRequeueException`.

## 缓存

**Redis cache eviction**: Do NOT use `@CacheEvict(allEntries = true)` — it clears ALL entries when only one team's data changed. Use `StringRedisTemplate.scan(ScanOptions)` with pattern matching to evict only the affected keys (e.g., `team-permission::{teamId}:*`). See `TeamPermissionCacheService` for reference.

## RestClient 与 AbstractServiceClient

**RestClient timeout**: All 9 services use `JdkClientHttpRequestFactory` with `connectTimeout=3s, readTimeout=10s`. When creating new `RestClient` beans, always configure timeouts via `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()` and `factory.setReadTimeout(Duration.ofSeconds(10))`.

**AbstractServiceClient HTTP helpers**: Base class provides `getJson`, `postJson`, `putJson`, `patchJson`, `deleteJson` — all wrapped with Resilience4j retry (3x, 500ms) + circuit breaker (10-window, 50%, 30s). Subclasses inherit these for free. New client classes should extend `AbstractServiceClient` (see admin-service's `EmailProviderClient`/`StorageProviderClient` as reference).

## API 响应契约

All backend APIs return `Result<T>` with `code: 1` = success (`ErrorCode.SUCCESS = 1`). Frontend `createApiClient.js` checks `payload?.code === 1`. See `docs/api-contract.md` for full contract.

## 服务间投影模式

窄端点优先、调用方投影、手动字段映射等服务间接口设计规范与已落地内部端点清单，见 `service-interface-design.md`。

## admin-service 特殊约定

**admin-service data source**: Uses `config.datasource.*` prefix (not `spring.datasource.*`) with `@Primary` DataSource. HikariCP requires `jdbc-url` (not `url`) in YAML when using `@ConfigurationProperties`.

**Config management API**: admin-service exposes `GET/PUT /configs` (no `/api/admin` prefix — Gateway's `RewritePath` strips it). `ConfigServiceClient.get(key)` calls `GET /api/admin/configs/{key}` — ensure this single-key endpoint exists in `ConfigAdminController`. Frontend page at `/setting/config-admin` (requires `requireSystemAdminRole()`). Redis Pub/Sub on `zxyz:config:changed` channel notifies config changes. Config keys follow `app.{domain}.{property}` naming convention.

**Gateway route rewrite**: Routes with `RewritePath=/api/admin/(?<segment>.*)` → `/${segment}` strip the `/api/admin` prefix. Backend controllers must map to the rewritten path (e.g., `@RequestMapping("/configs")`, NOT `@RequestMapping("/api/admin/configs")`). Known issue: `ProviderAdminController` previously had wrong path `/api/admin/providers` → fixed to `/providers`.

## ServiceClient 包位置约定

im/user/team/share 用 `xxx.infrastructure.client.*`（admin 用 `admin.client.*`）；但 project-service 与 file-service 把客户端放在 `xxx.service.impl.*` 子包（与本地 service 类并列），新建客户端时按各自服务现有约定放置。

## 运维提示

**Nginx DNS cache**: After restarting backend containers, their Docker network IPs change. Nginx caches DNS resolution at startup — restart it after service changes: `docker compose restart frontend-nginx`.

**RabbitMQ health check**: RabbitMQ often times out its Docker health check under load but still functions normally. Services that depend on it may show "unhealthy" status while actually running fine. Use `docker exec zxyz-rabbitmq rabbitmq-diagnostics -q ping` to verify.

**Env validation**: Run `./scripts/validate-env.sh .env` before first deployment to catch `CHANGE_ME_*` placeholders and missing required variables. The deploy script runs this automatically. 该脚本会从 `.env.example` 自动补全 `.env` 中缺失的 KEY（会修改 .env），`--sync-only` 参数仅补全不校验。
