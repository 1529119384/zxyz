---
kind: configuration_system
name: ZXYZ 配置系统：Nacos + Spring Boot 多环境分层与热更新
category: configuration_system
scope:
    - '**'
source_files:
    - ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml
    - ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application.yml
    - ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-dev.yml
    - ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-prod.yml
    - nacos-config/zxyz-static.yml
    - nacos-config/zxyz-dynamic.yml
    - nacos-config/import.sh
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/config/InternalServiceAuthInterceptor.java
    - ZXYZdatabaseBack/zxyz-file-service/src/main/java/uno/acloud/file/config/ServiceProperties.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/oss/OSSProperties.java
    - ZXYZdatabaseBack/zxyz-email-service/src/main/java/uno/acloud/email/config/AppProperties.java
    - ZXYZdatabaseBack/zxyz-gateway/src/main/java/uno/acloud/gateway/filter/GatewayProperties.java
---

## 1. 系统与架构概览
ZXYZ 后端采用 **Spring Boot 3 + Nacos 配置中心** 的统一配置体系，通过 `spring.config.import` 将本地 YAML、Nacos 静态配置与动态配置分层加载，结合环境变量与 Jasypt 加密实现安全可运维的配置管理。所有微服务共享 `zxyz-common/application-common.yml` 作为公共基线，再通过 Nacos 的 `zxyz-static.yml`（不可热更新）和 `zxyz-dynamic.yml`（支持运行时刷新）进行覆盖。

## 2. 核心文件与包
- **公共配置基线**：`ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml`（Redis、Sa-Token、MyBatis、Actuator、CORS、Resilience4j、SpringDoc 等全局默认值）
- **各服务入口配置**：如 `zxyz-file-service/src/main/resources/application.yml`，通过 `spring.config.import` 引入公共配置与 Nacos 配置源
- **Nacos 配置集**：`nacos-config/` 目录下按服务拆分（`zxyz-*-service.yml`），以及共享的 `zxyz-static.yml`、`zxyz-dynamic.yml`
- **配置导入脚本**：`nacos-config/import.sh` 批量将本地 YAML 推送到 Nacos
- **Profile 覆盖**：每个服务提供 `application-dev.yml`、`application-prod.yml`、`application-test.yml` 用于环境差异化

## 3. 配置加载顺序与分层策略
Spring Boot 启动时按以下优先级合并（高优先级覆盖低优先级）：
1. `classpath:application-common.yml`（公共基线）
2. `nacos:zxyz-static.yml?group=ZXYZ&refreshEnabled=false`（静态共享配置，不热更新）
3. `nacos:zxyz-dynamic.yml?group=ZXYZ&refreshEnabled=true`（动态共享配置，支持运行时刷新）
4. `nacos:zxyz-{service}.yml?group=ZXYZ&refreshEnabled=false`（服务专属静态配置）
5. 环境变量（`${VAR:default}` 形式，最高优先级）
6. `application-{profile}.yml`（dev/prod/test profile 覆盖）

## 4. 配置绑定方式与约定
- **类型安全绑定**：优先使用 `@ConfigurationProperties(prefix = "app")` 绑定到 Java Bean（如 `ServiceProperties`、`OSSProperties`、`AppProperties`、`GatewayProperties`），由 `@ConfigurationPropertiesScan` 自动扫描注册
- **直接注入**：少数场景使用 `@Value("${app.internal-service-token:}")` 直接读取（如 `InternalServiceAuthInterceptor`），注释明确说明这是故意设计以兼容多来源覆盖
- **命名规范**：所有跨服务共享的配置统一放在 `app.*` 命名空间下（如 `app.cors.allowed-origins`、`app.internal-service-token`、`app.oss.*`、`app.team-service.base-url` 等），避免分散在 `services.*` 或散落的键名
- **敏感信息**：通过 Jasypt 的 `ENC()` 格式存储于 Nacos，启动时由 `jasypt-spring-boot-starter` 自动解密，密码通过 `${JASYPT_PASSWORD}` 环境变量注入

## 5. 环境与部署约定
- **开发环境**：默认激活 `dev` profile，数据库/Redis 直连 localhost，Nacos 默认 `localhost:8848`
- **生产环境**：通过 Docker Compose / K8s 环境变量注入所有敏感值（`FILE_DATASOURCE_URL`、`REDIS_PASSWORD`、`INTERNAL_SERVICE_TOKEN`、`AUTH_COOKIE_SECURE` 等），禁止硬编码
- **服务发现地址**：服务间调用 base-url 默认使用 Docker 网络名（如 `http://zxyz-team-service`），可通过环境变量 `TEAM_SERVICE_BASE_URL` 覆盖
- **存储后端切换**：通过 `app.storage.default-provider` 和 `app.storage.provider.{oss|local}.enabled` 控制 OSS 或本地存储，便于开发/测试灵活切换

## 6. 约束与强制规则
- 内部服务调用必须携带 `X-Internal-Service-Token` 请求头，由 `InternalServiceAuthInterceptor` 校验，未配置则抛出 `SYSTEM_ERROR`（强制要求生产环境注入 `INTERNAL_SERVICE_TOKEN`）
- 所有 `app.*` 下的服务 URL 必须非空，`ServiceUrl.normalizedBaseUrl()` 会在为空时抛出 `IllegalStateException`（强制要求配置完整）
- Nacos 配置通过 `import.sh` 脚本幂等导入，重复执行会覆盖已有配置，确保配置版本一致性
- Jasypt 加密算法固定为 `AES/GCM/NoPadding`，IV 生成器为 `RandomIvGenerator`，禁止随意更改