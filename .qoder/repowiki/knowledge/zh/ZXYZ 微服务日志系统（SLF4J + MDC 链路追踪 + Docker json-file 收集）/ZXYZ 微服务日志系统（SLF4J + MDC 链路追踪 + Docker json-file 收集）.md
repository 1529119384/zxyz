---
kind: logging_system
name: ZXYZ 微服务日志系统（SLF4J + MDC 链路追踪 + Docker json-file 收集）
category: logging_system
scope:
    - '**'
source_files:
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/GlobalExceptionHandler.java
    - ZXYZdatabaseBack/zxyz-gateway/src/main/java/uno/acloud/gateway/filter/RequestIdFilter.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/client/AbstractServiceClient.java
    - ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml
    - nacos-config/zxyz-static.yml
    - docker-compose.yml
    - deploy/promtail-config.yml
---

## 1. 使用的框架与工具
- 日志门面：SLF4J，通过 Lombok `@Slf4j` 注解在各 Service/Client/Controller 中统一注入 Logger。
- 异常集中记录：`zxyz-common` 中的 `GlobalExceptionHandler` 使用 `LoggerFactory.getLogger(GlobalExceptionHandler.class)` 统一捕获并记录业务异常、参数校验异常、未登录/无权限异常及系统级异常。
- 链路追踪：基于 SLF4J MDC 的 `requestId` 字段，由 Gateway 的 `RequestIdFilter` 生成或透传，并在下游服务间通过 `InternalServiceHeaders.REQUEST_ID_HEADER` 传播。
- 容器日志驱动：所有服务在 `docker-compose.yml` 中统一配置 `logging.driver: json-file`，限制单文件大小与保留份数。
- 日志采集与聚合：Promtail (`deploy/promtail-config.yml`) 通过 Docker Socket 自动发现容器日志，推送到 Loki。

## 2. 核心文件与位置
- 全局异常处理与统一错误日志：`ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/GlobalExceptionHandler.java`
- 请求 ID 生成与 MDC 注入（Gateway）：`ZXYZdatabaseBack/zxyz-gateway/src/main/java/uno/acloud/gateway/filter/RequestIdFilter.java`
- 服务间调用客户端基类（MDC 透传 requestId）：`ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/client/AbstractServiceClient.java`
- 公共日志级别与 MyBatis/Sa-Token 开关：`ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml`
- Nacos 静态配置（含 Sa-Token is-log 等）：`nacos-config/zxyz-static.yml`
- Docker Compose 各服务 logging 约束：`docker-compose.yml`（每个 service 下 `logging.driver: json-file` + max-size/max-file）
- Promtail 采集配置：`deploy/promtail-config.yml`
- 各模块 AOP 日志切面（如 `LogAspect`、`TimeAspect`）：分布在 `file-service`、`project-service`、`share-service`、`team-service`、`user-service` 的 `aop` 包中
- 审计日志消费与清理（MQ 消费者中的 log.error/log.warn/log.debug）：`zxyz-audit-service` 下的 `OperateLogConsumer`、`AuditDlqConsumer`、`AuditLogCleanupService`

## 3. 架构与约定
- 日志输出方式：所有后端服务默认输出到 stdout/stderr，由 Docker json-file 驱动落盘为 `/var/lib/docker/containers/<id>/<id>-json.log`；Promtail 抓取这些 JSON 日志并推送至 Loki，实现集中式检索。
- 链路追踪约定：Gateway 层优先读取上游传入的 `X-Request-Id`，不存在则生成 UUID 放入 MDC(`requestId`) 并回写请求头；下游 `AbstractServiceClient.internalHeaders()` 从 MDC 取 `requestId` 写入出站请求头，保证跨服务链路一致。
- 日志级别策略：生产环境仅对关键路径使用 `info`，调试信息用 `debug`，警告与异常分别用 `warn`/`error`。MyBatis SQL 在生产关闭日志（`NoLoggingImpl`），测试环境可切换为 `StdOutImpl`。
- 安全相关日志：Sa-Token 的 `is-log` 在 `application-common.yml` 和 `zxyz-static.yml` 中开启，用于记录鉴权相关事件；Gateway 中显式关闭 `sa-token.is-log=false` 避免重复。
- 审计日志：通过 MQ 异步消费操作日志，消费者中按 `debug/warn/error/info` 分级记录反序列化失败、重复消息、清理任务执行结果等。

## 4. 约定与约束
- 所有 Java 组件统一使用 Lombok `@Slf4j` 获取 Logger，禁止直接 new Logger 实例（除 `GlobalExceptionHandler` 这种静态工具类场景）。
- 跨服务调用必须通过 `AbstractServiceClient` 或其子类发起，确保 `X-Request-Id` 自动注入与 Resilience4j 重试/熔断包装。
- 容器化部署时，每个服务的 `logging` 段必须保持 `driver: json-file`，且设置合理的 `max-size` 与 `max-file`，防止磁盘占满。
- 日志中不得打印敏感信息（密码、token 明文），如需记录需脱敏后再输出。
- 审计日志的幂等性：消费者中对重复消息使用 `log.warn` 跳过处理，避免重复入库。
- 健康检查与可观测性：Actuator 暴露 `loggers` 端点，可通过 `/actuator/loggers` 动态调整日志级别（生产建议受限访问）。