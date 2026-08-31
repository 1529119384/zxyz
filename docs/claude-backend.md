# 后端架构详解

本文档为 CLAUDE.md 的补充，提供后端架构的详细说明。

## 架构模式

项目采用**混合架构**，两种模式共存：

**传统分层**（user-service, team-service, project-service, file-service, share-service）：
`controller/` → `service/` + `impl/` → `mapper/` → `entity/`，附带 `dto/`, `vo/`, `config/`, `satoken/`, `infrastructure/`

**DDD / 六边形架构**（email-service, im-service）：
`interfaces/` → `application/` → `domain/` + `infrastructure/` + `config/`

- `domain/` — 领域模型、值对象、领域事件、枚举（无框架依赖）
- `application/` — 应用服务，编排领域逻辑
- `infrastructure/` — 持久化（mapper, entity）、外部客户端、MQ 适配器
- `controller/` — 薄 REST 层

im-service 是最完整的 DDD 实现，`domain/model` 包含丰富的实体类（ImConversation, ImMessage, Team, TeamMember, UserPresence）。

## 配置架构

所有服务通过 `spring.config.import: classpath:application-common.yml` 共享配置（在各服务的 `application.yml` 中定义）。

共享的 `application-common.yml`（位于 `zxyz-common`）提供：Redis、Nacos、RabbitMQ、Sa-Token、CORS、服务间调用 URL、OSS、Resilience4j 默认值。

各服务的 `application.yml` 补充：`spring.application.name`、`server.port`、数据源、Flyway。

Profile 覆盖：`application-dev.yml` / `application-prod.yml`。

**配置单一事实来源（P3-7）**：每个 key 只允许在**一个**配置载体定义一次，避免跨文件/跨数据源重复定义依赖 import 合并顺序：
- 本地 `application.yml` 与 Nacos（`zxyz-{svc}.yml`/`zxyz-static.yml`/`zxyz-dynamic.yml`）**不得对同一 key 双轨重复**（如 `spring.datasource.*`、`server.port`、`app.*`）。本地为 dev 默认、Nacos 为 prod 覆盖的同一 key 只留一处，另一方删除或注释。
- `spring.datasource` 由 `zxyz-static.yml`（仅 `hikari.*` 连接池）与各 `zxyz-{svc}.yml`（`url/username/password`）共同组成——这是合法的**父子键拆分**；请勿在两侧定义同一子键（如两边都写 `url`）。同一父 key 下子键必须在同一载体内。
- Nacos 配置发布前 `nacos-config/import.sh` 已做单文件顶层重复 key 自检（检出即中止），勿绕过。

## 服务间通信

**同步调用**：`*ServiceClient` 类继承 `AbstractServiceClient`（位于 zxyz-common），通过 `X-Internal-Service-Token` header 鉴权，Resilience4j 重试（3 次 × 500ms）+ 熔断器（50% 失败率阈值）。

**异步通信**：RabbitMQ Topic Exchange `zxyz.topic`，通过 `*EventPublisher` 类发布。

## 六边形端口

zxyz-common 中的端口接口：
- `AuthServicePort` — 认证抽象（封装 Sa-Token 操作），使单元测试可脱离框架依赖
- `TeamPermissionAspect` — `@RequiresTeamPermission` 注解的抽象 AOP 切面，各服务提供具体实现

## 领域事件

跨服务状态传播使用 Spring `ApplicationEvent`（本地）+ RabbitMQ（远程）。

zxyz-common 中的事件定义（`uno.acloud.common.event`）：
- `TeamCreatedEvent`
- `TeamMemberAddedEvent`
- `UserProfileUpdatedEvent`
- `FileResourceChangedEvent`

## 后端约定与坑位

### 配置绑定

**Config binding pitfall**: All services use flat `app.internal-service-token` in YAML with `@Value` or `@ConfigurationProperties(prefix="app")`. Do NOT nest it under `app.internal.service-token` — Spring Boot cannot bind nested YAML to flat fields. If you see empty token values at runtime, check the YAML structure.

**`@ConfigurationProperties` prefix matching**: Each properties class binds to a specific YAML prefix. E.g. `TeamServiceProperties(prefix="app.team-service")` expects `app.team-service.internal-service-token`, NOT `app.internal-service-token` or `app.share.team-service.internal-service-token`. When adding new `@ConfigurationProperties` classes, ensure the YAML keys match the exact prefix. Common mistake: nesting service config under a parent service's `app.*` block instead of at the correct prefix level.

**Service URL config**: All service base URLs use `app.*-service.base-url` in `application-common.yml` (e.g., `app.user-service.base-url`). Individual service `application.yml` files map these directly to env vars (e.g., `${TEAM_SERVICE_BASE_URL:http://zxyz-team-service}`), NOT via `${services.*}` indirection — that causes `@ConditionalOnProperty` to fail before Nacos loads.

### MyBatis 与 MapStruct

**MyBatis + MapStruct conflict**: MyBatis `@MapperScan` can hijack MapStruct `@Mapper` interfaces in the same package. Keep MapStruct mappers in a separate package (e.g., `uno.acloud.{service}.convert`).

### 自动配置条件

**Auto-configuration conditions**: `RemoteStpInterfaceAutoConfig` requires `@ConditionalOnBean(RestClient.class)` — Gateway (WebFlux) has no `RestClient`, so it's skipped. `ConfigClientAutoConfiguration` requires `@ConditionalOnBean(RestClient.Builder.class)` — same reason, skipped in Gateway.

### 异常处理覆盖

**GlobalExceptionHandler coverage**: `basePackages` includes all 10 service packages: `uno.acloud.{user,team,project,file,share,email,audit,gateway,im,admin}`. When adding a new service module, add its package to `basePackages` in `zxyz-common/GlobalExceptionHandler.java`.

### 配置加密

**Config encryption**: Sensitive values in Nacos use `ENC(ciphertext)` format. Jasypt 3.0.5 + AES/GCM/NoPadding, key via `JASYPT_PASSWORD` env var. See `docs/jasypt-key-management.md`.

### admin-service 数据源与配置管理

**admin-service data source**: Uses `config.datasource.*` prefix (not `spring.datasource.*`) with `@Primary` DataSource. HikariCP requires `jdbc-url` (not `url`) in YAML when using `@ConfigurationProperties`.

**Config management API**: admin-service exposes `GET/PUT /configs` (no `/api/admin` prefix — Gateway's `RewritePath` strips it). `ConfigGetter.get(key)` calls `GET /api/admin/configs/{key}` — ensure this single-key endpoint exists in `ConfigAdminController`. ConfigGetter 是项目内唯一的 HTTP 配置客户端，禁止再自行构建另一套 HTTP 配置客户端副本（如历史上的 ConfigServiceClient）。 Frontend page at `/setting/config-admin` (requires `requireSystemAdminRole()`). Redis Pub/Sub on `zxyz:config:changed` channel notifies config changes. Config keys follow `app.{domain}.{property}` naming convention.

**Gateway route rewrite**: Routes with `RewritePath=/api/admin/(?<segment>.*)` → `/${segment}` strip the `/api/admin` prefix. Backend controllers must map to the rewritten path (e.g., `@RequestMapping("/configs")`, NOT `@RequestMapping("/api/admin/configs")`). Known issue: `ProviderAdminController` previously had wrong path `/api/admin/providers` → fixed to `/providers`.

### 服务调用客户端

**RestClient timeout**: All 9 services use `JdkClientHttpRequestFactory` with `connectTimeout=3s, readTimeout=10s`. When creating new `RestClient` beans, always configure timeouts via `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()` and `factory.setReadTimeout(Duration.ofSeconds(10))`.

**AbstractServiceClient HTTP helpers**: Base class provides `getJson`, `postJson`, `putJson`, `patchJson`, `deleteJson` — all wrapped with Resilience4j retry (3x, 500ms) + circuit breaker (10-window, 50%, 30s). Subclasses inherit these for free. New client classes should extend `AbstractServiceClient` (see admin-service's `EmailProviderClient`/`StorageProviderClient` as reference).

**API response contract**: All backend APIs return `Result<T>` with `code: 1` = success (`ErrorCode.SUCCESS = 1`). Frontend `createApiClient.js` checks `payload?.code === 1`. See `docs/api-contract.md` for full contract.

**ServiceClient 包位置约定**：im/user/team/share 用 `xxx.infrastructure.client.*`（admin 用 `admin.client.*`）；但 project-service 与 file-service 把客户端放在 `xxx.service.impl.*` 子包（与本地 service 类并列），新建客户端时按各自服务现有约定放置。

### 安全

**Entity security**: `User` and `Share` entities use `@JsonProperty(access = WRITE_ONLY)` on `password` field + `@ToString(exclude = {"password"})` to prevent accidental serialization of BCrypt hashes. Any sensitive field (passwords, tokens, cipher text) on entities/DTOs must have `@JsonProperty(access = WRITE_ONLY)` or `@JsonIgnore`. Config classes that should never serialize use `@JsonIgnore`.

**Internal service token**: `INTERNAL_SERVICE_TOKEN` must NEVER have a default value in YAML (e.g., do NOT use `${INTERNAL_SERVICE_TOKEN:dev-internal-token}`). A predictable default means all gateway-forwarded internal calls use an attacker-known token if the env var is unset. Apply this to all YAML files including `application-dev.yml`.

**Filename XSS**: `FileDomainValidator.validateInputName()` and `FileRenameService.validateRenameName()` reject `< > " ' &` characters. Use these methods for all user-provided file/folder names.

**File upload type whitelist**: `FileUploadService.ALLOWED_EXTENSIONS` controls which file types can be uploaded. `BLOCKED_EXTENSIONS` (with dot prefix, e.g. `.exe`) is checked first and overrides ALLOWED. Note: `.js` is in BLOCKED (XSS risk in browsers) — do NOT add `js` to ALLOWED. `GetSignUrl.java` has a duplicate `BLOCKED_EXTENSIONS` set — keep both in sync when modifying.

### 权限与事务

**`@RequiresTeamPermission` default**: `skipWhenTeamIdMissing` defaults to `false` — missing teamId throws `BAD_REQUEST`. Endpoints that support personal space (teamId=null) must explicitly set `skipWhenTeamIdMissing = true`. Otherwise personal-space operations like file listing, folder creation, and upload confirmation all break with "teamId 不能为空".

**Transaction boundary pattern**: HTTP/MQ calls must NOT happen inside `@Transactional` methods — they hold DB connections during remote I/O. Use `TransactionSynchronizationManager.registerSynchronization(afterCommit)` to defer remote calls. The `afterCommit` callback MUST wrap the remote call in try-catch to prevent exceptions from propagating into Spring's transaction synchronization chain. See `RoleManagementService` for the canonical pattern. For in-process Spring `ApplicationEventPublisher` events (NOT RabbitMQ), no afterCommit is needed since they don't involve network I/O.

### 消息队列与缓存

**MQ poison message handling**: When a consumer catches `JsonProcessingException` (deserialization failure that will never succeed on retry), throw `AmqpRejectAndDontRequeueException` to route the message to DLQ — do NOT just log and return (which silently ACKs the poison message). Import from `org.springframework.amqp.AmqpRejectAndDontRequeueException`.

**Redis cache eviction**: Do NOT use `@CacheEvict(allEntries = true)` — it clears ALL entries when only one team's data changed. Use `StringRedisTemplate.scan(ScanOptions)` with pattern matching to evict only the affected keys (e.g., `team-permission::{teamId}:*`). See `TeamPermissionCacheService` for reference.

## 服务间接口设计规范

**窄端点优先（Projection pattern）**: Internal service-to-service calls must use narrow endpoints that return projection objects, NOT fat endpoints that return full domain DTOs. Fat endpoints (`FileInfoDTO`, `TeamVO`, etc.) leak internal domain structure across service boundaries. When a service consumer only needs a subset of fields, the provider must expose a dedicated narrow endpoint. Naming convention: `*-projection` suffix (e.g., `/{fileId}/share-projection`, `/batch-share-projection`, `/{parentId}/share-children-projection`). The client side maps the narrow response to a lightweight projection model (e.g., `ShareFileProjection`) via manual `mapToProjection(JsonNode)` — do NOT introduce MapStruct mappers for cross-service projections. See `ShareFileServiceClient` + `InternalFileController` share projection endpoints for the canonical pattern. Existing fat endpoints that are superseded by narrow ones should be deleted to prevent regression.

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

### 内部窄端点清单

内部端点前缀 `/api/internal/**`，被 gateway 的 SaToken filter 拒绝公网访问，仅 Docker 内网服务间直连。已落地窄端点如下：

| 服务 | Controller | 端点 | 返回 | 调用方 |
|---|---|---|---|---|
| file-service | `InternalFileController`（share 投影） | `GET /api/internal/files/{fileId}/share-projection` | `ShareFileProjectionVO` | share-service |
| file-service | `InternalFileController`（share 投影） | `POST /api/internal/files/batch-share-projection` | `List<ShareFileProjectionVO>` | share-service |
| file-service | `InternalFileController`（share 投影） | `GET /api/internal/files/{parentId}/share-children-projection` | `List<ShareFileProjectionVO>` | share-service |
| file-service | `InternalFileController`（share 投影） | `POST /api/internal/files/batch-share-children-projection` | `Map<Long, List<ShareFileProjectionVO>>` | share-service |
| file-service | `InternalFileController`（share 投影） | `GET /api/internal/files/{fileId}/share-download-url` | `String` | share-service |
| file-service | `InternalStorageController`（存储用量） | `/sum-active`、`/sum-personal`、`/personal-usage-list`、`/team-usage-list`（GET/POST 双形态） | — | — |
| file-service | `InternalImFileCardController` | `POST /snapshot`、`POST /resolve` | — | — |
| team-service | `InternalTeamController`（团队投影） | `GET /api/internal/teams/ids/by-user/{userId}`（`List<Long>`）、`/{teamId}`、`/{teamId}/members`、`/{teamId}/quota`、`/{teamId}/member-count`、`/{teamId}/owner-id` 等 10 个 | — | project-service |
| team-service | `InternalPermissionController`（权限） | `POST /check`、`POST /has`、系统角色/权限管理、`POST /team-roles`、`POST /team/grant-role` 等 18 个 | — | — |
| user-service | `InternalUserController` | `/{userId}/info`、`POST /batch`、`POST /create-team-user`、`/all-ids`、`/verified-emails`、`/{id}/quota`、`/search` 等 11 个 | — | — |
| share-service | `InternalShareController` | `POST /cleanup-by-files` | — | — |
| project-service | `InternalProjectController` | `/{userId}/active-projects-led-count`、`POST /{projectId}/access-check`、`/team/{teamId}/quota-sum` | — | — |
| project-service | `InternalStorageController` | `POST /check-quota` | — | — |
| im-service | `InternalTeamSyncController`/`InternalUserProfileSyncController`/`InternalSystemNotificationController` | `/api/im/internal/**`（故意避开 `/api/internal/**` 以绕开 SaToken filter 的 internal 拒绝规则，仍受登录态校验保护） | — | — |

Gateway 有两条 admin→业务的"桥接路由"，鉴权方式不同，不要混为一谈：

- `/api/admin/email/**` → email-service `/api/email/internal/**`（`RewritePath` 重写）：由网关自注入
  `X-Internal-Service-Token`（取 `${SVC_GATEWAY_KEY:${INTERNAL_SERVICE_TOKEN}}`，矩阵模式优先网关专属密钥、
  过渡期回退共享 token）+ `X-Internal-Caller-Service: zxyz-gateway`。这是**真正走服务间鉴权**的桥接
  （email 的 `EmailInternalAuthInterceptor` 覆盖 `/api/email/internal/**`）。
- `/api/admin/database/**` → project-service：**不 RewritePath**，原样转发到 `/api/admin/database/**`，
  且**不注入任何内部头**。该路径由 Sa-Token 登录态 + `@SaCheckRole(SYSTEM_ADMIN)` 把关，
  project-service 的 `InternalServiceAuthInterceptor` 只覆盖 `/api/internal/**`，不消费内部 token。
  （历史版本曾在此注入 `X-Internal-Service-Token`，属无 caller 配对、无消费方的冗余遗留，已移除。）
