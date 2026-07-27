# Architecture

指绣云章 (ZXYZ) — 云端文件管理平台，包含团队协作与即时通讯。

## 后端技术栈

- JDK 17, Spring Boot 3.5.7, Spring Cloud 2025.0.0, Spring Cloud Alibaba 2025.0.0.0, Maven 3.9+
- Lombok 1.18.42 + MapStruct 1.6.3（注解处理器在 compiler plugin 中配置）
- MyBatis-Plus 3.5.9（注解 Mapper，无 XML），MySQL 8.4
- Sa-Token 1.45.0（token-style: uuid），Redis 存储会话
- 阿里云 OSS v2, Redisson 3.35.0, Flyway 10.22.0
- Netty WebSocket（IM 服务）, Resilience4j 2.2.0（重试 3×500ms，熔断 50%）
- Knife4j 4.5.0 + springdoc 2.8.9（`KNIFE4J_ENABLE=true` 开启增强 UI）

## 模块结构（11 模块）

| 模块 | 端口 | 数据库 | 职责 |
|---|---|---|---|
| `zxyz-common` | — | — | 共享错误码、响应结构、权限码、工具类 |
| `zxyz-gateway` | 18000 | 无 | API Gateway, Sa-Token 鉴权前置, Redis 限流 |
| `zxyz-project-service` | 18080 | zxyz_project | 项目 CRUD、成员管理、配额、创建审批 |
| `zxyz-im-service` | 18081/19090 | zxyz_im | IM 会话、Netty WebSocket、团队通知 |
| `zxyz-email-service` | 18082 | zxyz_email | 邮件发送、验证码、SMTP 配置 |
| `zxyz-user-service` | 18083 | zxyz_user | 用户注册/登录、资料管理 |
| `zxyz-share-service` | 18084 | zxyz_share | 分享链接创建/访问/下载 |
| `zxyz-file-service` | 18085 | zxyz_file | 文件上传/下载/删除、文件夹、回收站 |
| `zxyz-team-service` | 18086 | zxyz_team | 团队 CRUD、成员管理、RBAC 权限 |
| `zxyz-audit-service` | 18087 | zxyz_audit | 操作日志消费与审计（RabbitMQ 消费 + 持久化到 zxyz_audit 库） |
| `zxyz-admin-service` | 18088 | zxyz_config | 配置管理：ConfigService + Jasypt + Caffeine 缓存 + Redis Pub/Sub |

## 包结构约定

大多数服务采用传统分层（`uno.acloud.{service}`）：
`controller/` → `service/` + `impl/` → `mapper/` → `entity/`，辅以 `dto/`、`vo/`、`config/`、`satoken/`、`infrastructure/`

`zxyz-email-service` 和 `zxyz-im-service` 采用 DDD 风格：
`interfaces/` → `application/` → `domain/` + `infrastructure/` + `config/`

MapStruct 用于 DTO↔Entity 转换（`*Converter`/`*Assembler` 类）。

## 关键架构说明

- 9 个独立服务 + API Gateway，各服务独立数据库，HTTP（同步）+ RabbitMQ（异步）通信，Nacos 注册中心
- Gateway: Spring Cloud Gateway WebFlux，Sa-Token 全局鉴权，白名单放行公开接口
- 服务间通信：`X-Internal-Service-Token` 鉴权，`*ServiceClient` 封装 HTTP 调用
- 事件驱动：RabbitMQ Topic Exchange `zxyz.topic`
- Sa-Token 会话存储在 Redis，所有服务共享配置
- 权限系统支持系统级和团队级 RBAC，由 team-service 统一管理

## 前端技术栈

- Vue 3（Composition API + `<script setup>`）, Vite 7, Vue Router 4, Pinia 3
- Element Plus 2.11（unplugin-vue-components 自动导入）, Axios

## 前端架构

- API 模块严格按领域分离（`api/` 目录），领域间禁止交叉引用
- 三个 HTTP 客户端：`request.js`（需登录）、`imRequest.js`（IM）、`publicRequest.js`（公开分享）
- HttpOnly Cookie 携带 Token（`withCredentials: true`），自动处理 401 跳转
- IM WebSocket 由 `imWebSocket.js` 管理，状态通过 Pinia `im/` 同步
- 文件资源管理器通过 composables 处理导航、选择、拖拽、上传、搜索
- 路由守卫检查登录状态，确保会话引导（团队选择）完成
- 生产环境 API 走相对路径（`/`），Nginx 反代至 Gateway

## 前端领域模式

- 错误处理：`BusinessException` → `ErrorCode` → `Result`
- 文件空间：`PERSONAL=1`, `TEAM=2`, `PROJECT=3`
- 文件删除：`NORMAL=0`, `RECYCLE=1`, `DELETED=2`
- OSS 引用计数：`file_object_ref` 表防过早物理删除
