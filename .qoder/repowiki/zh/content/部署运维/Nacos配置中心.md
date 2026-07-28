# Nacos配置中心

<cite>
**本文引用的文件**   
- [nacos-config/import.sh](file://nacos-config/import.sh)
- [nacos-config/zxyz-dynamic.yml](file://nacos-config/zxyz-dynamic.yml)
- [nacos-config/zxyz-static.yml](file://nacos-config/zxyz-static.yml)
- [nacos-config/zxyz-admin-service.yml](file://nacos-config/zxyz-admin-service.yml)
- [nacos-config/zxyz-audit-service.yml](file://nacos-config/zxyz-audit-service.yml)
- [nacos-config/zxyz-email-service.yml](file://nacos-config/zxyz-email-service.yml)
- [nacos-config/zxyz-file-service.yml](file://nacos-config/zxyz-file-service.yml)
- [nacos-config/zxyz-gateway.yml](file://nacos-config/zxyz-gateway.yml)
- [nacos-config/zxyz-im-service.yml](file://nacos-config/zxyz-im-service.yml)
- [nacos-config/zxyz-project-service.yml](file://nacos-config/zxyz-project-service.yml)
- [nacos-config/zxyz-share-service.yml](file://nacos-config/zxyz-share-service.yml)
- [nacos-config/zxyz-team-service.yml](file://nacos-config/zxyz-team-service.yml)
- [nacos-config/zxyz-user-service.yml](file://nacos-config/zxyz-user-service.yml)
- [ZXYZdatabaseBack/README.md](file://ZXYZdatabaseBack/README.md)
- [DEPLOYMENT.md](file://DEPLOYMENT.md)
- [docker-compose.yml](file://docker-compose.yml)
- [docker-compose.dev.yml](file://docker-compose.dev.yml)
- [ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml](file://ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/config/AdminServiceProperties.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/config/AdminServiceProperties.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V3__fix_config_keys.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V3__fix_config_keys.sql)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/config/RabbitMqConfig.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/config/RabbitMqConfig.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/OperateLogConsumer.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/OperateLogConsumer.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/AuditDlqConsumer.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/AuditDlqConsumer.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz配置项路径说明.md](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application.yml)
- [docs/jasypt-key-management.md](file://docs/jasypt-key-management.md)
</cite>

## 目录
1. [引言](#引言)
2. [项目结构](#项目结构)
3. [核心组件](#核心组件)
4. [架构总览](#架构总览)
5. [详细组件分析](#详细组件分析)
6. [依赖关系分析](#依赖关系分析)
7. [性能考量](#性能考量)
8. [故障排查指南](#故障排查指南)
9. [结论](#结论)
10. [附录](#附录)

## 引言
本文件为 ZXYZ 项目的 Nacos 配置中心使用与治理文档，覆盖动态配置管理策略（分组、命名空间与环境隔离）、各服务配置结构（数据库连接池、Redis、RabbitMQ、限流熔断等中间件）、配置热更新机制（监听、动态刷新、版本管理）、敏感信息加密（Jasypt）、配置导入导出工具与同步策略、配置最佳实践（分层、默认值、校验）以及配置监控与审计（变更记录与访问日志）。目标是帮助研发、运维与合规团队统一理解并高效使用 Nacos。

## 项目结构
Nacos 相关配置以 YAML 形式集中存放于 nacos-config 目录，并提供批量导入脚本 import.sh。每个后端服务均包含 application.yml 及环境化配置文件（application-dev.yml、application-prod.yml），用于本地与生产环境的差异化加载。公共配置抽取至 zxyz-common 的 application-common.yml，供多模块复用。

```mermaid
graph TB
subgraph "Nacos 配置"
A["zxyz-dynamic.yml"]
B["zxyz-static.yml"]
C["各服务独立YAML<br/>admin/audit/email/file/gateway/im/project/share/team/user"]
end
subgraph "应用内配置"
D["application.yml"]
E["application-dev.yml / application-prod.yml"]
F["application-common.yml"]
end
G["import.sh 批量导入"]
H["Nacos Server"]
I["各微服务实例"]
G --> H
A --> H
B --> H
C --> H
H --> I
D --> I
E --> I
F --> I
```

图表来源
- [nacos-config/import.sh](file://nacos-config/import.sh)
- [nacos-config/zxyz-dynamic.yml](file://nacos-config/zxyz-dynamic.yml)
- [nacos-config/zxyz-static.yml](file://nacos-config/zxyz-static.yml)
- [ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml](file://ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml)

章节来源
- [ZXYZdatabaseBack/README.md](file://ZXYZdatabaseBack/README.md)
- [DEPLOYMENT.md](file://DEPLOYMENT.md)
- [docker-compose.yml](file://docker-compose.yml)
- [docker-compose.dev.yml](file://docker-compose.dev.yml)

## 核心组件
- 配置中心：Nacos Config，承载静态与动态配置，支持按 Data ID 划分、Group 分组、Namespace 隔离。
- 配置导入工具：nacos-config/import.sh，提供批量导入能力，便于 CI/CD 与初始化。
- 应用配置层：
  - 公共配置：zxyz-common/application-common.yml
  - 环境配置：各服务的 application-{env}.yml
  - 运行时配置：Nacos 中的 zxyz-dynamic.yml 与各服务 Data ID
- 配置管理与审计：
  - 配置模型与持久化：SysConfig、SysConfigAudit 及其 Mapper
  - 配置管理服务：ConfigService
  - 配置管理控制器：ConfigAdminController
  - 数据库迁移脚本：V1/V2/V3

章节来源
- [nacos-config/import.sh](file://nacos-config/import.sh)
- [nacos-config/zxyz-dynamic.yml](file://nacos-config/zxyz-dynamic.yml)
- [nacos-config/zxyz-static.yml](file://nacos-config/zxyz-static.yml)
- [ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml](file://ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V3__fix_config_keys.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V3__fix_config_keys.sql)

## 架构总览
下图展示 Nacos 配置中心在 ZXYZ 中的整体交互：导入脚本将 YAML 推送到 Nacos；各服务启动时从 Nacos 拉取配置；动态配置通过监听机制实现热更新；敏感配置由 Jasypt 解密后注入。

```mermaid
sequenceDiagram
participant Dev as "开发者/CI"
participant Import as "import.sh"
participant Nacos as "Nacos Server"
participant App as "微服务实例"
participant Jasypt as "Jasypt 解密"
participant DB as "配置审计库"
Dev->>Import : "执行批量导入"
Import->>Nacos : "上传Data ID与内容"
Note over Import,Nacos : "静态/动态配置分别推送"
App->>Nacos : "启动时拉取配置"
App->>Jasypt : "解密敏感字段"
Jasypt-->>App : "明文属性"
App->>Nacos : "订阅动态配置变更"
Nacos-->>App : "推送变更事件"
App->>App : "刷新Bean/连接池/限流等"
App->>DB : "记录配置变更审计"
```

图表来源
- [nacos-config/import.sh](file://nacos-config/import.sh)
- [nacos-config/zxyz-dynamic.yml](file://nacos-config/zxyz-dynamic.yml)
- [nacos-config/zxyz-static.yml](file://nacos-config/zxyz-static.yml)
- [docs/jasypt-key-management.md](file://docs/jasypt-key-management.md)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql)

## 详细组件分析

### 配置分组、命名空间与环境隔离
- 命名空间（Namespace）：建议按环境划分（dev/test/staging/prod），确保环境间配置完全隔离。
- 分组（Group）：可按业务域或技术栈划分（如 zxyz.common、zxyz.mq、zxyz.db），便于权限与检索。
- Data ID 设计：
  - 全局动态配置：zxyz-dynamic.yml
  - 全局静态配置：zxyz-static.yml
  - 服务级配置：zxyz-{service}-service.yml（如 zxyz-email-service.yml）
- 环境叠加：application.yml 作为基础，application-{env}.yml 覆盖特定环境，Nacos 配置优先级高于本地文件。

章节来源
- [nacos-config/zxyz-dynamic.yml](file://nacos-config/zxyz-dynamic.yml)
- [nacos-config/zxyz-static.yml](file://nacos-config/zxyz-static.yml)
- [nacos-config/zxyz-email-service.yml](file://nacos-config/zxyz-email-service.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml)

### 各服务配置结构与中间件参数
- 数据库连接池
  - 关键键：数据源 URL、用户名、密码、连接池大小、超时、驱动类名等
  - 建议：通过 Nacos 动态调整最大连接数与超时，避免重启
- Redis 配置
  - 关键键：主机、端口、密码、数据库索引、连接池、序列化方式、超时
  - 建议：区分读写库与缓存库，设置合理的 TTL 与重试策略
- RabbitMQ 参数
  - 关键键：主机、端口、虚拟主机、用户名、密码、交换器、队列、消费者并发、重试与死信队列
  - 示例参考：审计服务的 MQ 配置与消费者
- 限流与熔断
  - 关键键：令牌桶/漏桶参数、熔断阈值、降级开关、回退策略
  - 建议：结合网关与服务端双重限流，灰度发布配合熔断开关

章节来源
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/config/RabbitMqConfig.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/config/RabbitMqConfig.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/OperateLogConsumer.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/OperateLogConsumer.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/AuditDlqConsumer.java](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/java/uno/acloud/audit/mq/AuditDlqConsumer.java)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-audit-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-email-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-file-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-im-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-project-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-share-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-team-service/src/main/resources/application.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-dev.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-dev.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-prod.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application-prod.yml)
- [ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application.yml](file://ZXYZdatabaseBack/zxyz-user-service/src/main/resources/application.yml)

### 配置热更新机制（监听、动态刷新、版本管理）
- 监听与刷新
  - 通过 Nacos 客户端监听 Data ID 变更，触发回调后刷新对应 Bean 或连接池
  - 对连接型组件（DB、Redis、MQ）需支持优雅重建连接
- 版本管理
  - 利用 Nacos 版本历史进行回滚与对比
  - 结合配置审计表记录变更人、时间、内容与影响范围
- 热更新流程

```mermaid
flowchart TD
Start(["开始"]) --> Watch["监听Nacos配置变更"]
Watch --> Change{"是否变更?"}
Change --> |否| End(["结束"])
Change --> |是| Validate["校验配置合法性"]
Validate --> Valid{"校验通过?"}
Valid --> |否| Rollback["拒绝并告警"]
Valid --> |是| Apply["应用新配置"]
Apply --> Refresh["刷新Bean/连接池/限流等"]
Refresh --> Audit["记录审计日志"]
Audit --> End
Rollback --> End
```

章节来源
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql)

### 敏感信息加密（Jasypt）
- 存储格式：使用 Jasypt 加密前缀包裹密文（如 ENC(...)）
- 密钥管理：通过环境变量或外部密钥管理服务注入解密密钥
- 读取流程：Spring 启动时自动解密，注入到属性中
- 建议：
  - 仅对敏感字段（密码、密钥、Token）使用 ENC
  - 密钥与代码分离，禁止入库
  - 定期轮换密钥并评估影响面

章节来源
- [docs/jasypt-key-management.md](file://docs/jasypt-key-management.md)

### 配置导入导出与同步策略
- 导入工具：nacos-config/import.sh
  - 支持批量上传 YAML 到 Nacos
  - 可指定 Group、Namespace、Data ID
- 导出策略：
  - 基于 Nacos API 导出当前配置快照
  - 生成结构化 YAML 归档，纳入版本控制
- 同步策略：
  - 开发/测试/生产环境通过不同 Namespace 隔离
  - 使用 CI/CD 流水线在部署前执行导入与校验
  - 变更前后对比，失败自动回滚

章节来源
- [nacos-config/import.sh](file://nacos-config/import.sh)

### 配置最佳实践
- 配置分层
  - 基础配置：application.yml
  - 环境配置：application-{env}.yml
  - 公共配置：application-common.yml
  - Nacos 动态配置：zxyz-dynamic.yml 与服务级 Data ID
- 默认值设置
  - 所有关键参数需提供合理默认值，防止缺失导致启动失败
- 配置校验
  - 启动阶段进行必填项与取值范围校验
  - 对连接性参数进行连通性探测
- 安全与合规
  - 敏感信息一律加密
  - 操作留痕与审计

章节来源
- [ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml](file://ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml)

### 配置监控与审计
- 变更记录
  - 通过 SysConfigAudit 表记录每次变更的 Key、Value、操作人、时间
- 访问日志
  - 记录配置拉取、监听、刷新事件
- 指标与告警
  - 暴露配置变更次数、失败率、延迟等指标
  - 异常变更触发告警与回滚

章节来源
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/resources/db/migration/V1__init_config_schema.sql)

## 依赖关系分析
- 组件耦合
  - ConfigService 依赖 SysConfig/SysConfigAudit 进行持久化与审计
  - ConfigAdminController 暴露配置管理接口
  - 各服务通过 Nacos 客户端依赖配置中心
- 外部依赖
  - Nacos Server：配置注册与发现
  - Jasypt：敏感信息加解密
  - 数据库：配置审计与持久化

```mermaid
classDiagram
class ConfigService {
+获取配置()
+更新配置()
+删除配置()
+监听变更()
}
class ConfigAdminController {
+配置查询()
+配置写入()
+配置删除()
}
class SysConfig {
+id
+key
+value
+group
+namespace
}
class SysConfigAudit {
+id
+config_id
+operator
+action
+diff
+created_at
}
class SysConfigMapper
class SysConfigAuditMapper
ConfigService --> SysConfig : "读写"
ConfigService --> SysConfigAudit : "审计"
ConfigAdminController --> ConfigService : "调用"
SysConfigMapper --> SysConfig : "映射"
SysConfigAuditMapper --> SysConfigAudit : "映射"
```

图表来源
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/domain/SysConfig.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigMapper.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/mapper/SysConfigAuditMapper.java)

章节来源
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/config/AdminServiceProperties.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/config/AdminServiceProperties.java)

## 性能考量
- 配置拉取优化
  - 启用本地缓存，减少网络请求
  - 合理设置监听轮询间隔
- 连接池调优
  - 根据负载动态调整连接池大小与超时
  - 避免频繁重建连接造成抖动
- 限流与熔断
  - 在高并发场景下开启限流，保护下游
  - 熔断阈值与降级策略需与业务 SLA 对齐
- 审计与监控
  - 异步落盘审计，避免阻塞主流程
  - 指标上报与告警阈值精细化

[本节为通用指导，不直接分析具体文件]

## 故障排查指南
- 常见问题
  - 配置未生效：检查 Data ID、Group、Namespace 是否正确；确认监听是否成功
  - 连接失败：核对连接串、账号密码、网络可达性与防火墙策略
  - 限流/熔断误触发：检查阈值设置与流量峰值
- 定位步骤
  - 查看 Nacos 配置历史与差异
  - 检查应用日志中的配置加载与监听事件
  - 验证 Jasypt 解密是否正常
  - 核对数据库审计记录
- 恢复策略
  - 快速回滚到上一个稳定版本
  - 临时关闭非关键功能（如限流）
  - 逐步放量验证

章节来源
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/controller/ConfigAdminController.java)
- [ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java](file://ZXYZdatabaseBack/zxyz-admin-service/src/main/java/uno/acloud/admin/service/ConfigService.java)
- [docs/jasypt-key-management.md](file://docs/jasypt-key-management.md)

## 结论
通过 Nacos 配置中心，ZXYZ 实现了统一的配置治理与动态管理能力。借助分组、命名空间与环境隔离，保障了配置的安全与可维护性；通过热更新与版本管理，提升了变更效率与稳定性；结合 Jasypt 加密与审计机制，满足了安全与合规要求。建议在持续集成中完善导入校验与回滚策略，进一步提升交付质量与运行可靠性。

## 附录
- 常用命令与脚本
  - 批量导入：nacos-config/import.sh
  - 环境切换：修改 application-{env}.yml 或 Nacos Namespace
- 参考文档
  - Jasypt 密钥管理：docs/jasypt-key-management.md
  - 部署与编排：DEPLOYMENT.md、docker-compose.yml、docker-compose.dev.yml

[本节为补充信息，不直接分析具体文件]