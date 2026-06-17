# ZXYZ 项目代码审核排查报告（二次复核）

> 初审日期: 2026-06-16
> 复核日期: 2026-06-17
> 修复日期: 2026-06-17
> 审核范围: 后端 11 个 Maven 模块 + 前端 Vue 3.5 项目
> 复核方法: 对初审 42 项问题逐一验证修复状态 + 按问题模式全库扫描同类新问题

---

## 复核统计

| 来源 | P1 | P2 | P3 | 合计 |
|------|----|----|-----|------|
| 初审未修复 | 2 | 1 | 0 | 3 |
| 新发现同类问题 | 7 | 5 | 3 | 15 |
| **合计** | **9** | **6** | **3** | **18** |

> 初审 42 项中 39 项已完全修复，不再列出。以下仅保留仍存在及新发现的问题。
> **2026-06-17 修复**: 10 项已修复，5 项确认为误报（见"五、误报说明"）。

---

## 一、初审遗留问题（3 项）

### L-01 | P1 | gateway 内部服务令牌仍使用弱默认值 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml`
**行号**: 第 114 行、第 144 行
**类别**: 安全

**问题描述**: `application-common.yml` 已移除 `dev-internal-token` 默认值，但 gateway 主配置文件中仍有两处：
- 第 114 行: `AddRequestHeader=X-Internal-Service-Token, ${INTERNAL_SERVICE_TOKEN:dev-internal-token}`
- 第 144 行: `app.internal-service-token: ${INTERNAL_SERVICE_TOKEN:dev-internal-token}`

若 `INTERNAL_SERVICE_TOKEN` 环境变量未设置，所有 gateway 转发的内部服务调用将使用可预测的默认令牌。

**修复**: 已移除 `:dev-internal-token` 默认值，改为 `${INTERNAL_SERVICE_TOKEN}`。

---

### L-02 | P1 | share-service TeamServiceProperties 属性绑定为 null ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-share-service/src/main/java/uno/acloud/share/config/TeamServiceProperties.java` (行 6)
**类别**: 配置 / 功能

**问题描述**: `@ConfigurationProperties(prefix = "app.team-service")` 期望 YAML 路径 `app.team-service.base-url`，但实际 YAML 结构为 `app.share.team-service.base-url`（多了一层 `share`）。`baseUrl` 和 `internalServiceToken` 始终为 `null`，调用 `normalizedBaseUrl()` 会抛出 `IllegalStateException`。

**修复**: 已将 prefix 改为 `app.share.team-service`，错误信息同步更新。

---

### L-03 | P2 | getFileResourceById 可访问已删除文件 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-file-service/src/main/java/uno/acloud/file/service/impl/FileQueryService.java` (行 200)
**类别**: 数据一致性

**问题描述**: `getFileResourceById()` 直接调用 `fileMapper.getFileNodeById(fileId)`，未过滤 `deleted` 状态。已删除（含永久删除）的文件资源仍可被访问。同文件第 156 行的 `getFileInfoById()` 已正确使用 `getActiveFileNodeById()`，说明修复不完整。

**修复**: 已将第 200 行改为 `fileMapper.getActiveFileNodeById(fileId)`。

---

## 二、新发现的事务内远程调用问题（8 项）

> 模式: `@Transactional` 方法体内直接发起 HTTP/MQ 调用，违反事务边界原则。事务回滚时远程操作无法撤回，HTTP 超时会长时间持有数据库连接。

### T-01 | P1 | UserRoleBindingService.assignRoleToUser() 事务内 HTTP 调用 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-team-service/src/main/java/uno/acloud/team/service/impl/UserRoleBindingService.java` (行 44-59)
**类别**: 数据一致性

**问题描述**: `@Transactional` 方法内直接调用 `userServiceClient.clearPermissionCache(userId)`（HTTP → user-service）。事务回滚时远程缓存已清除，且 HTTP 超时会长时间占用 DB 连接。

**修复**: 3 个方法（`assignRoleToUser`、`ensureDefaultRole`、`assignBootstrapAdminRole`）均使用 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 模式，将 HTTP 调用移到事务提交后。

---

### T-02 | P1 | TeamInvitationService.invite() 事务内 MQ 发送 — 误报

**文件**: `ZXYZdatabaseBack/zxyz-im-service/src/main/java/uno/acloud/im/application/TeamInvitationService.java` (行 62-107)
**类别**: 数据一致性

**问题描述**: `@Transactional` 方法内调用 `domainEventPublisher.publish()`（MQ）和 `systemNotificationService.createNotification()`（DB）。事务回滚时 MQ 消息已发出，IM 侧数据不一致。

**误报说明**: `ImDomainEventPublisher` 使用 Spring `ApplicationEventPublisher`（进程内同步事件），非 RabbitMQ。im-service 中无任何 `@EventListener` 消费 `ImDomainEvent`，事件发布后即丢弃。`systemNotificationService.createNotification()` 是 DB 写入，正确参与事务。

---

### T-03 | P1 | TeamLifecycleService.leaveTeam() 事务内 MQ 发送 — 误报

**文件**: `ZXYZdatabaseBack/zxyz-im-service/src/main/java/uno/acloud/im/application/TeamLifecycleService.java` (行 41-53)
**类别**: 数据一致性

**问题描述**: `@Transactional` 方法内调用 `domainEventPublisher.publish()`。

**误报说明**: 同 T-02，`ImDomainEventPublisher` 是进程内 Spring Event，非 MQ。

---

### T-04 | P1 | TeamLifecycleService.removeMember() 事务内 MQ 发送 — 误报

**文件**: `ZXYZdatabaseBack/zxyz-im-service/src/main/java/uno/acloud/im/application/TeamLifecycleService.java` (行 55-78)
**类别**: 数据一致性

**问题描述**: 同 T-03，`@Transactional` 方法内调用 `domainEventPublisher.publish()`。

**误报说明**: 同 T-02，`ImDomainEventPublisher` 是进程内 Spring Event，非 MQ。

---

### T-05 | P2 | UserProfileService.updateSettings() 事务内 MQ 发送 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-user-service/src/main/java/uno/acloud/user/service/impl/UserProfileService.java` (行 65-79)
**类别**: 数据一致性

**问题描述**: `@Transactional` 方法内调用 `userEventPublisher.publishProfileUpdated()`。MQ 事件为通知性质，不会导致核心数据丢失，但会造成缓存不一致。

**修复**: 使用 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 模式，将 MQ 发送移到事务提交后。

---

### T-06 | P2 | ImMessageService.notifyMentionedUsers() 事务链内 MQ 发送 — 误报

**文件**: `ZXYZdatabaseBack/zxyz-im-service/src/main/java/uno/acloud/im/application/ImMessageService.java` (行 247-273)
**类别**: 数据一致性

**问题描述**: `notifyMentionedUsers()` 被 `doStoreTextMessage()` (行 155) 调用，而后者被 `@Transactional` 方法 `storeTextMessage()` 调用。事务链内调用 `domainEventPublisher.publish()` 和 `notificationService.batchCreateNotifications()`。

**误报说明**: 同 T-02，`ImDomainEventPublisher` 是进程内 Spring Event，非 MQ。`notificationService.batchCreateNotifications()` 是 DB 写入，正确参与事务。

---

## 三、新发现的敏感字段序列化保护缺失（4 项）

### S-01 | P1 | EmailServerConfig.passwordCipher 缺少序列化保护 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-email-service/src/main/java/uno/acloud/email/domain/EmailServerConfig.java` (行 26)
**类别**: 安全

**问题描述**: `private String passwordCipher` 字段无 `@JsonProperty(access = WRITE_ONLY)` 或 `@JsonIgnore`。若实体被直接序列化为 JSON 响应，密码密文会泄露。

**修复**: 已添加 `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`。

---

### S-02 | P2 | TeamInviteLink.token 缺少序列化保护 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-im-service/src/main/java/uno/acloud/im/domain/model/TeamInviteLink.java` (行 23)
**类别**: 安全

**问题描述**: `private String token` 字段无 `@JsonIgnore`。邀请令牌若被意外序列化会泄露邀请链接。

**修复**: 已添加 `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`。

---

### S-03 | P3 | EmailServerConfigRequest.password 缺少序列化保护 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-email-service/src/main/java/uno/acloud/email/dto/EmailServerConfigRequest.java` (行 41)
**类别**: 安全

**问题描述**: 请求 DTO 的 `password` 字段无 `@JsonProperty(access = WRITE_ONLY)`，不应在响应中返回。

**修复**: 已添加 `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`。

---

### S-04 | P3 | EmailProperties.password 缺少序列化保护 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-email-service/src/main/java/uno/acloud/email/config/EmailProperties.java` (行 10)
**类别**: 安全

**问题描述**: 配置类的 `password` 字段无 `@JsonIgnore`，若被意外序列化会泄露 SMTP 密码。

**修复**: 已添加 `@JsonIgnore`。

---

## 四、新发现的配置绑定问题（2 项）

### C-01 | P1 | admin-service AdminServiceProperties YAML 绑定失败 — 误报

**文件**: `ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/config/AdminServiceProperties.java` (行 9)
**类别**: 配置 / 功能

**问题描述**: `@ConfigurationProperties(prefix = "app")` 期望 `app.email-service.base-url` 和 `app.file-service.base-url`，但本地 `application.yml` 无此配置，nacos-config 使用 `services.*` 前缀。`emailService.baseUrl` 和 `fileService.baseUrl` 始终为 `null`，调用 `normalizedBaseUrl()` 抛出 `IllegalStateException`。

**误报说明**: `application-common.yml`（admin-service 的 `application.yml` 第 6 行 import）已提供 `app.email-service.base-url`（行 121-122）和 `app.file-service.base-url`（行 117-118）。Spring Boot relaxed binding 正确映射到 `AdminServiceProperties` 的嵌套字段。`final` 字段 + 预初始化 `ServiceUrl` 对象的工作方式：Spring 调用 getter 获取现有实例，再调用 `setBaseUrl()` 修改其内部状态。

---

### C-02 | P3 | share-service application-dev.yml 残留弱默认值 ✅ 已修复

**文件**: `ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-dev.yml` (行 30)
**类别**: 安全

**问题描述**: `internal-service-token: ${INTERNAL_SERVICE_TOKEN:dev-internal-token}` 仍使用弱默认值。虽然仅 dev 环境生效，但增加了开发环境的攻击面。

**修复**: 已移除 `:dev-internal-token` 默认值。

---

## 五、误报说明

经代码验证，以下 5 项为误报：

| ID | 原因 |
|----|------|
| T-02 | `ImDomainEventPublisher` 使用 Spring `ApplicationEventPublisher`（进程内同步事件），非 RabbitMQ。im-service 中无 `@EventListener` 消费 `ImDomainEvent` |
| T-03 | 同 T-02 |
| T-04 | 同 T-02 |
| T-06 | 同 T-02，`notificationService.batchCreateNotifications()` 是 DB 写入，正确参与事务 |
| C-01 | `application-common.yml` 已提供 `app.email-service.base-url` 和 `app.file-service.base-url`，Spring Boot relaxed binding 正确映射 |

## 六、确认无新问题的检查项

| 检查类别 | 结论 |
|----------|------|
| 日志泄露敏感信息 | 未发现新实例（P0-01 已修复，无同类新问题） |
| Gateway RewritePath 不匹配 | 未发现新实例（P0-06 已修复，所有路由正确匹配） |
| Redis KEYS 命令 | 未发现新实例（P1-12 已修复，无 `.keys()` 调用） |
| SQL 注入 | 全部使用 `#{}` 参数化绑定 |
| @RequiresTeamPermission 默认值 | 已修复为 `false`，无新问题 |
| 文件名 XSS 过滤 | 已修复，`validateInputName` 和 `validateRenameName` 均过滤 `<>&"'` |
| User/Share 密码字段保护 | 已修复，`@JsonProperty(WRITE_ONLY)` + `@ToString(exclude)` |
| 前端 redirect sanitize | 已修复，白名单校验 + 所有 redirect 点均经过 sanitize |
| 前端路由守卫 | 已修复，config-admin 路由已有 `requireSystemAdminRole()` |

---

## 修复记录

### 已修复（10 项）
| ID | 优先级 | 修复内容 |
|----|--------|----------|
| L-01 | P1 | gateway 移除 `dev-internal-token` 默认值 |
| L-02 | P1 | share-service TeamServiceProperties prefix 改为 `app.share.team-service` |
| L-03 | P2 | FileQueryService.getFileResourceById 改用 `getActiveFileNodeById` |
| T-01 | P1 | UserRoleBindingService 3 个方法 HTTP 调用移到 afterCommit |
| T-05 | P2 | UserProfileService.updateSettings MQ 发送移到 afterCommit |
| S-01 | P1 | EmailServerConfig.passwordCipher 添加 `@JsonProperty(WRITE_ONLY)` |
| S-02 | P2 | TeamInviteLink.token 添加 `@JsonProperty(WRITE_ONLY)` |
| S-03 | P3 | EmailServerConfigRequest.password 添加 `@JsonProperty(WRITE_ONLY)` |
| S-04 | P3 | EmailProperties.password 添加 `@JsonIgnore` |
| C-02 | P3 | share-service dev.yml 移除弱默认值 |

### 误报（5 项）
| ID | 原因 |
|----|------|
| T-02 | ImDomainEventPublisher 是进程内 Spring Event，非 MQ |
| T-03 | 同 T-02 |
| T-04 | 同 T-02 |
| T-06 | 同 T-02 |
| C-01 | application-common.yml 已提供正确配置 |
