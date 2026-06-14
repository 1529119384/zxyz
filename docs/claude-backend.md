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
