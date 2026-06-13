# 指绣云章 (ZXYZ) 后端

## 项目简介

指绣云章（ZXYZ）是一个云端文件管理平台，支持团队协作与即时通讯。后端采用 Java Spring Boot 多模块微服务架构，包含 9 个子模块，通过 API Gateway 统一路由，使用 Nacos 作为服务注册中心，支持服务间同步调用（HTTP）和异步事件驱动（RabbitMQ）。

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 运行时 | JDK | 17 |
| 框架 | Spring Boot | 3.5.7 |
| 云原生 | Spring Cloud | 2025.0.0 |
| 服务注册 | Nacos | v3.2.1 |
| 网关 | Spring Cloud Gateway | - |
| ORM | MyBatis（注解方式） | 3.0.3 |
| 数据库 | MySQL | 8.4 |
| 缓存 | Redis | 7.4 |
| 消息队列 | RabbitMQ | 3.13 |
| 认证 | Sa-Token（token-style: uuid） | 1.44.0 |
| 文件存储 | 阿里云 OSS v2 | 0.3.1 |
| 分布式锁 | Redisson | 3.35.0 |
| 实时通信 | Netty WebSocket | - |
| 邮件 | Simple Java Mail | 8.12.6 |
| API 文档 | Knife4j + springdoc | 4.5.0 / 2.8.9 |
| 工具库 | Lombok、commons-lang3 | 1.18.42 / 3.18.0 |

## 模块结构

本项目包含 9 个子模块，各模块职责如下：

| 模块 | 端口 | 数据库 | 职责 |
|---|---|---|---|
| `zxyz-common` | — | — | 共享错误码、响应结构、权限码、工具类、OSS 签名 |
| `zxyz-gateway` | 18000 | 无 | API Gateway（Spring Cloud Gateway），Sa-Token 全局鉴权前置 |
| `zxyz-project-service` | 18080 | zxyz_project | 项目 CRUD、成员管理、配额、创建审批、存储用量 |
| `zxyz-im-service` | 18081 / 19090 | zxyz_im | IM 会话、Netty WebSocket 实时消息、团队通知、在线状态 |
| `zxyz-email-service` | 18082 | zxyz_email | 邮件发送、验证码、SMTP 配置管理 |
| `zxyz-user-service` | 18083 | zxyz_user | 用户注册/登录、资料管理、账号绑定、用户搜索 |
| `zxyz-share-service` | 18084 | zxyz_share | 分享链接创建/访问/下载 |
| `zxyz-file-service` | 18085 | zxyz_file | 文件上传/下载/删除、文件夹管理、回收站、IM 文件卡片 |
| `zxyz-team-service` | 18086 | zxyz_team | 团队 CRUD、成员管理、RBAC 权限、系统角色管理 |

## 本地开发环境搭建

### 1. 环境准备

确保本地安装以下软件：

- **JDK 17**（推荐 Eclipse Temurin）
- **Maven 3.9+**
- **MySQL 8.4**
- **Redis 7.4**
- **RabbitMQ 3.13**
- **Nacos v3.2.1**（Docker 部署必需，docker-compose 中所有业务服务均依赖；本地直接运行服务时如已配置静态地址可跳过）

### 2. 数据库初始化

执行 SQL 初始化脚本创建 8 个数据库（含 nacos）：

```bash
# 进入 SQL 目录
cd ZXYZdatabaseBack/sql

# 方式一：手动执行各 schema 文件（本地开发推荐）
mysql -u root -p < schema_project.sql
mysql -u root -p < schema_im.sql
mysql -u root -p < schema_email.sql
mysql -u root -p < schema_share.sql
mysql -u root -p < schema_file.sql
mysql -u root -p < schema_team.sql
mysql -u root -p < schema_user.sql
mysql -u root -p < schema_nacos.sql

# 方式二：Docker 部署时由容器自动执行
# 00-init-zxyz.sh 是 bash 脚本（不是 SQL 文件），在 MySQL 容器首次启动时
# 由 docker-entrypoint-initdb.d 自动执行，无需手动运行。
```

### 3. 配置文件

各服务的配置文件位于 `src/main/resources/application.yml`，公共配置在 `zxyz-common/src/main/resources/application-common.yml`。

**关键配置项（环境变量）：**

| 环境变量 | 说明 | 默认值 |
|---|---|---|
| `REDIS_HOST` | Redis 地址 | localhost |
| `REDIS_PORT` | Redis 端口 | 6379 |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `*_DATASOURCE_URL` | 各服务数据库连接 URL | - |
| `*_DATASOURCE_USERNAME` | 数据库用户名 | - |
| `*_DATASOURCE_PASSWORD` | 数据库密码 | - |
| `INTERNAL_SERVICE_TOKEN` | 服务间调用鉴权 Token | - |
| `OSS_REGION` | 阿里云 OSS 区域 | - |
| `OSS_BUCKET` | OSS Bucket 名称 | - |
| `OSS_ACCESS_KEY_ID` | OSS AccessKey ID | - |
| `OSS_ACCESS_KEY_SECRET` | OSS AccessKey Secret | - |
| `EMAIL_HOST` | SMTP 服务器地址 | smtp.qq.com |
| `EMAIL_PORT` | SMTP 端口 | 587 |
| `EMAIL_USERNAME` | SMTP 账号 | - |
| `EMAIL_PASSWORD` | SMTP 授权码 | - |
| `EMAIL_FROM` | 发件人地址 | - |
| `NACOS_SERVER_ADDR` | Nacos 地址 | localhost:8848 |
| `NACOS_USERNAME` | Nacos 用户名 | nacos |
| `NACOS_PASSWORD` | Nacos 密码 | nacos |

**本地开发最小配置示例：**

```bash
# 设置环境变量
export REDIS_HOST=localhost
export REDIS_PASSWORD=your_redis_password
export PROJECT_DATASOURCE_URL="jdbc:mysql://localhost:3306/zxyz_project?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export PROJECT_DATASOURCE_USERNAME=root
export PROJECT_DATASOURCE_PASSWORD=your_mysql_password
export INTERNAL_SERVICE_TOKEN=dev-internal-token-change-me
# ... 其他服务类似
```

### 4. 启动依赖服务

```bash
# 启动 MySQL、Redis、RabbitMQ（Docker 方式）
docker-compose up -d mysql redis rabbitmq nacos
```

## 构建与运行命令

### Maven 构建

```bash
# 进入后端根目录
cd ZXYZdatabaseBack

# 编译检查（跳过测试）
mvn clean -DskipTests compile

# 运行全部测试
mvn test

# 运行单个模块测试
mvn test -pl zxyz-project-service

# 打包（跳过测试）
mvn clean package -DskipTests
```

### 启动各服务

```bash
# 启动项目服务（端口 18080）
mvn -pl zxyz-project-service spring-boot:run

# 启动 IM 服务（端口 18081，Netty 端口 19090）
mvn -pl zxyz-im-service spring-boot:run

# 启动邮件服务（端口 18082）
mvn -pl zxyz-email-service spring-boot:run

# 启动用户服务（端口 18083）
mvn -pl zxyz-user-service spring-boot:run

# 启动分享服务（端口 18084）
mvn -pl zxyz-share-service spring-boot:run

# 启动文件服务（端口 18085）
mvn -pl zxyz-file-service spring-boot:run

# 启动团队服务（端口 18086）
mvn -pl zxyz-team-service spring-boot:run

# 启动网关（端口 18000）
mvn -pl zxyz-gateway spring-boot:run
```

## API 概览

所有 API 通过 Gateway（端口 18000）统一入口，路径前缀 `/api`。

### 用户服务 (`/api/users/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/users/login` | 用户登录（白名单，无需 Token） |
| POST | `/api/users/register` | 用户注册（白名单，无需 Token） |
| POST | `/api/users/logout` | 用户登出 |
| GET | `/api/users/me` | 获取当前用户信息 |
| GET | `/api/users/settings` | 获取用户设置 |
| PATCH | `/api/users/settings` | 更新用户设置 |
| PATCH | `/api/users/password` | 修改密码 |
| PATCH | `/api/users/email` | 绑定邮箱 |
| PATCH | `/api/users/phone` | 绑定手机 |
| PATCH | `/api/users/default-team` | 设置默认团队 |
| POST | `/api/users/avatar/upload-sign` | 获取头像上传签名 |
| POST | `/api/users/email/verification-code` | 发送邮箱验证码 |
| POST | `/api/users/phone/verification-code` | 发送手机验证码 |
| POST | `/api/users/contact/verify` | 验证联系方式 |
| GET | `/api/users/linked-accounts` | 获取关联账号列表 |
| POST | `/api/users/linked-accounts/{targetUserId}/trust` | 信任关联账号 |
| POST | `/api/users/linked-accounts/{targetUserId}/switch` | 切换关联账号 |
| GET | `/api/users/search` | 搜索用户 |

### 项目服务 (`/api/projects/**`, `/api/project-members/**`, `/api/project-catalog/**`, `/api/project-quotas/**`, `/api/project-create-requests/**`, `/api/storage/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/project-catalog/teams/{teamId}/projects` | 获取团队项目列表 |
| POST | `/api/project-catalog/teams/{teamId}/projects` | 创建项目 |
| PATCH | `/api/project-lifecycle/projects/{projectId}/archive` | 归档项目 |
| GET | `/api/project-members/projects/{projectId}/members` | 获取项目成员列表 |
| POST | `/api/project-members/projects/{projectId}/members` | 添加项目成员 |
| PATCH | `/api/project-members/projects/{projectId}/leader` | 转让项目负责人 |
| PATCH | `/api/project-quotas/projects/{projectId}` | 更新项目配额 |
| POST | `/api/project-create-requests/teams/{teamId}` | 提交项目创建申请 |
| GET | `/api/project-create-requests/teams/{teamId}/pending` | 获取待审核项目申请 |
| POST | `/api/project-create-requests/{applicationId}/approve` | 批准项目申请 |
| POST | `/api/project-create-requests/{applicationId}/reject` | 拒绝项目申请 |
| GET | `/api/storage/usage` | 获取存储用量 |

### 文件服务 (`/api/files/**`, `/api/folders/**`, `/api/trash/**`, `/api/im-file-cards/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/files/uploads` | 获取文件上传签名 |
| POST | `/api/files/uploads/confirmations` | 批量确认上传 |
| GET | `/api/files` | 获取文件列表 |
| GET | `/api/files/{fileId}` | 获取文件详情 |
| GET | `/api/files/search` | 搜索文件 |
| GET | `/api/files/{fileId}/download-url` | 获取文件下载链接 |
| PATCH | `/api/files/{fileId}` | 更新文件信息 |
| PATCH | `/api/files` | 批量移动文件 |
| POST | `/api/files/copies` | 批量复制文件 |
| PATCH | `/api/files/{fileId}/trash` | 移入回收站 |
| PATCH | `/api/files/trash` | 批量移入回收站 |
| DELETE | `/api/files/{fileId}/trash` | 从回收站恢复 |
| DELETE | `/api/files/trash` | 批量从回收站恢复 |
| DELETE | `/api/files/{fileId}` | 彻底删除文件 |
| DELETE | `/api/files` | 批量彻底删除 |
| POST | `/api/folders` | 创建文件夹 |
| GET | `/api/trash/files` | 获取回收站文件列表 |
| POST | `/api/im-file-cards/snapshot` | 创建 IM 文件卡片快照 |
| POST | `/api/im-file-cards/resolve` | 解析 IM 文件卡片 |

### 团队服务 (`/api/teams/**`, `/api/admin/teams/**`, `/api/permissions/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/teams/my` | 获取我的团队列表 |
| PATCH | `/api/teams/{teamId}` | 更新团队信息 |
| POST | `/api/teams/{teamId}/avatar/upload-sign` | 获取团队头像上传签名 |
| GET | `/api/teams/{teamId}/members` | 获取团队成员列表 |
| POST | `/api/teams/{teamId}/members` | 创建团队成员 |
| PATCH | `/api/teams/{teamId}/members/{userId}/status` | 更新成员状态 |
| DELETE | `/api/teams/{teamId}/members/{userId}` | 移除成员 |
| POST | `/api/teams/{teamId}/leave` | 离开团队 |
| GET | `/api/teams/{teamId}/members/storage` | 获取成员存储用量 |
| PATCH | `/api/teams/{teamId}/members/{userId}/storage` | 更新成员个人存储上限 |
| POST | `/api/admin/teams` | 创建团队（管理员） |
| GET | `/api/admin/teams` | 获取所有团队（管理员） |
| PATCH | `/api/admin/teams/{teamId}/quota` | 更新团队配额（管理员） |
| POST | `/api/admin/teams/system-messages` | 广播系统消息（管理员） |
| POST | `/api/admin/teams/system-emails/scheduled-batches` | 定时批量发送系统邮件（管理员） |
| GET | `/api/permissions` | 获取系统权限列表 |
| GET | `/api/permissions/roles` | 获取系统角色列表 |
| POST | `/api/permissions/roles` | 创建系统角色 |
| PATCH | `/api/permissions/roles/{roleId}` | 更新系统角色 |
| DELETE | `/api/permissions/roles/{roleId}` | 删除系统角色 |
| POST | `/api/permissions/roles/{roleId}/permissions` | 分配角色权限 |
| POST | `/api/permissions/users/{userId}/roles` | 分配用户角色 |
| GET | `/api/permissions/audit` | 获取系统权限审计日志 |
| GET | `/api/permissions/teams/{teamId}/permissions` | 获取团队权限列表 |
| GET | `/api/permissions/teams/{teamId}/roles` | 获取团队角色列表 |
| POST | `/api/permissions/teams/{teamId}/roles` | 创建团队角色 |
| PATCH | `/api/permissions/teams/{teamId}/roles/{roleId}` | 更新团队角色 |
| DELETE | `/api/permissions/teams/{teamId}/roles/{roleId}` | 删除团队角色 |
| POST | `/api/permissions/teams/{teamId}/roles/{roleId}/permissions` | 分配团队角色权限 |
| POST | `/api/permissions/teams/{teamId}/member-roles` | 分配团队成员角色 |
| GET | `/api/permissions/teams/{teamId}/audit` | 获取团队权限审计日志 |

### 分享服务 (`/api/shares/**`, `/api/public/shares/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/shares` | 创建分享 |
| GET | `/api/shares` | 获取我的分享列表 |
| GET | `/api/shares/{shareId}` | 获取分享详情 |
| PATCH | `/api/shares/{shareId}` | 更新分享状态 |
| POST | `/api/public/shares/{shareKey}/accesses` | 验证分享访问（白名单，无需 Token） |
| GET | `/api/public/shares/{shareKey}` | 获取公开分享信息（白名单，无需 Token） |
| GET | `/api/public/shares/{shareKey}/files` | 获取分享文件列表（白名单，无需 Token） |
| GET | `/api/public/shares/{shareKey}/files/{fileId}/download-url` | 获取分享文件下载链接（白名单，无需 Token） |

### IM 服务 (`/api/im/**`, `/api/team-collaboration/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/im/conversations` | 获取我的会话列表 |
| GET | `/api/im/conversations/{conversationId}` | 获取会话详情 |
| POST | `/api/im/direct-conversations` | 创建/获取私聊会话 |
| GET | `/api/im/teams/{teamId}/conversation` | 获取团队会话 |
| POST | `/api/im/conversations/{conversationId}/read` | 更新已读位置 |
| GET | `/api/im/conversations/{conversationId}/messages` | 获取会话消息列表 |
| GET | `/api/im/conversations/{conversationId}/messages/search` | 搜索会话消息 |
| POST | `/api/im/messages/{messageId}/recall` | 撤回消息 |
| POST | `/api/im/messages/{messageId}/file-card/resolve` | 解析文件卡片消息 |
| GET | `/api/im/system-notifications` | 获取系统通知列表 |
| GET | `/api/im/system-notifications/unread-count` | 获取未读通知数 |
| PATCH | `/api/im/system-notifications/{notificationId}/read` | 标记通知已读 |
| GET | `/api/im/presence/me` | 获取我的在线状态 |
| GET | `/api/im/presence/users` | 批量获取用户在线状态 |
| POST | `/api/im/projects/conversations` | 创建项目会话 |
| PATCH | `/api/im/projects/{projectId}/archive` | 归档项目会话 |
| POST | `/api/im/projects/creation-applications/messages` | 发送项目创建申请消息 |
| POST | `/api/im/projects/creation-applications/{applicationId}/result-messages` | 发送项目创建结果消息 |
| GET | `/api/team-collaboration/users/search` | 搜索用户（IM 侧） |
| POST | `/api/team-collaboration/teams/{teamId}/invitations` | 邀请用户加入团队 |
| POST | `/api/team-collaboration/teams/{teamId}/announcements` | 发布团队公告 |
| POST | `/api/team-collaboration/teams/{teamId}/mutes` | 禁言成员 |
| GET | `/api/team-collaboration/teams/{teamId}/mutes` | 获取禁言列表 |
| DELETE | `/api/team-collaboration/teams/{teamId}/mutes/{userId}` | 解除禁言 |
| POST | `/api/team-collaboration/teams/{teamId}/invite-links` | 创建邀请链接 |
| GET | `/api/team-collaboration/teams/{teamId}/join-requests` | 获取加入申请列表 |
| POST | `/api/team-collaboration/invite-links/{token}/join-requests` | 提交加入申请 |
| POST | `/api/team-collaboration/join-requests/{requestId}/approve` | 批准加入申请 |
| POST | `/api/team-collaboration/join-requests/{requestId}/reject` | 拒绝加入申请 |
| POST | `/api/team-collaboration/team-invitations/{invitationId}/accept` | 接受邀请 |
| POST | `/api/team-collaboration/team-invitations/{invitationId}/reject` | 拒绝邀请 |

### 邮件服务 (`/api/email/**`)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/email/internal/send` | 发送邮件 |
| POST | `/api/email/internal/send-batch` | 批量发送邮件 |
| POST | `/api/email/internal/send-template` | 按模板发送邮件 |
| POST | `/api/email/internal/send-template-batch` | 批量按模板发送邮件 |
| POST | `/api/email/internal/verify-codes/send` | 发送验证码 |
| POST | `/api/email/internal/verify-codes/check` | 校验验证码 |
| POST | `/api/email/internal/scheduled-batches` | 创建定时批量邮件 |
| GET | `/api/email/internal/server-configs` | 获取 SMTP 配置列表 |
| GET | `/api/email/internal/server-configs/current` | 获取当前 SMTP 配置 |
| GET | `/api/email/internal/runtime-status` | 获取邮件服务运行状态 |
| POST | `/api/email/internal/server-configs` | 创建 SMTP 配置 |
| PUT | `/api/email/internal/server-configs/{id}` | 更新 SMTP 配置 |
| POST | `/api/email/internal/server-configs/{id}/test` | 测试 SMTP 连通性 |
| POST | `/api/email/internal/server-configs/{id}/activate` | 激活 SMTP 配置 |
| GET | `/api/email/internal/records` | 获取邮件发送记录 |
| GET | `/api/email/internal/records/{id}` | 获取邮件发送记录详情 |

## 配置说明

### 环境变量

完整环境变量列表请参考项目根目录的 `.env.example` 文件。复制为 `.env` 并修改后使用。

### 配置文件结构

```
zxyz-common/src/main/resources/
  application-common.yml          # 公共配置（Redis、Sa-Token、MyBatis、CORS、Nacos）

zxyz-gateway/src/main/resources/
  application.yml                 # 网关配置（路由、CORS、Sa-Token 鉴权）

zxyz-project-service/src/main/resources/
  application.yml                 # 项目服务配置（端口、数据库、Knife4j）

zxyz-im-service/src/main/resources/
  application.yml                 # IM 服务配置（端口、数据库、Netty、服务依赖）

zxyz-email-service/src/main/resources/
  application.yml                 # 邮件服务配置（端口、数据库、邮件参数）

zxyz-user-service/src/main/resources/
  application.yml                 # 用户服务配置（端口、数据库、OSS、服务依赖）

zxyz-share-service/src/main/resources/
  application.yml                 # 分享服务配置（端口、数据库、Cookie 密钥）

zxyz-file-service/src/main/resources/
  application.yml                 # 文件服务配置（端口、数据库、OSS、文件删除策略）

zxyz-team-service/src/main/resources/
  application.yml                 # 团队服务配置（端口、数据库、服务依赖）
```

### 关键配置项说明

**Sa-Token 认证配置（所有服务共享）：**
- Token 名称：`Authorization`
- Token 前缀：`Bearer`
- 存储模式：Redis
- 超时时间：30 天（2592000 秒）
- Redis 前缀：`satoken:`

**MyBatis 配置：**
- 驼峰命名映射：`map-underscore-to-camel-case: true`
- 使用注解方式 Mapper，无 XML 映射文件

## 数据库说明

项目使用 8 个独立数据库，各服务拥有独立的数据源：

| 数据库 | 服务 | 主要表 |
|---|---|---|
| `zxyz_project` | project-service | `project`、`project_member`、`project_quota`、`project_create_request`、`operate_log` |
| `zxyz_im` | im-service | `im_user_profile`、`im_user_presence`、`im_team`、`team_member`、`team_invitation`、`team_invite_link`、`team_join_request`、`team_mute`、`im_conversation`、`im_conversation_member`、`im_message`、`system_notification` |
| `zxyz_email` | email-service | `email_record`、`email_template`、`verify_code`、`email_server_config` |
| `zxyz_user` | user-service | `user`、`user_quota`、`account_switch_trust`、`contact_verification_code` |
| `zxyz_share` | share-service | `share`、`share_item` |
| `zxyz_file` | file-service | `file_node`、`file_object_ref`、`operate_log` |
| `zxyz_team` | team-service | `team`、`team_member`、`team_quota`、`permission`、`role`、`user_role`、`role_permission`、`permission_audit`、`operate_log`、`team_permission`、`team_role`、`team_member_role`、`team_role_permission` |
| `nacos` | Nacos 服务注册中心 | `config_info`、`tenant_info`、`tenant_capacity` 等（Nacos 内部管理表） |

**关键业务常量：**

| 常量类型 | 值 | 说明 |
|---|---|---|
| 文件空间类型 | `PERSONAL=1`、`TEAM=2`、`PROJECT=3` | 个人空间、团队空间、项目空间 |
| 文件节点类型 | `FOLDER=0`、`FILE=1` | 文件夹、文件 |
| 文件删除状态 | `NORMAL=0`、`RECYCLE=1`、`DELETED=2` | 正常、回收站、已彻底删除 |
| OSS 对象删除状态 | `ACTIVE`、`PENDING_DELETE`、`DELETING`、`DELETED` | 可用、待删除、删除中、已删除 |

## 架构说明

### 微服务架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Frontend (Vue 3 SPA)                      │
└─────────────────────────┬───────────────────────────────────┘
                          │ HTTP
┌─────────────────────────▼───────────────────────────────────┐
│                    API Gateway (18000)                        │
│              Spring Cloud Gateway + Sa-Token                 │
└───┬─────────┬─────────┬─────────┬─────────┬─────────┬───────┘
    │         │         │         │         │         │
    ▼         ▼         ▼         ▼         ▼         ▼
┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐
│project│ │  im   │ │ email │ │ user  │ │ share │ │ file  │ │ team  │
│ :18080│ │:18081 │ │:18082 │ │:18083 │ │:18084 │ │:18085 │ │:18086 │
└───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘
    │         │         │         │         │         │         │
    └─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
                          │
    ┌─────────────────────┼─────────────────────┐
    ▼                     ▼                     ▼
┌───────┐           ┌───────┐           ┌───────────┐
│ MySQL │           │ Redis │           │ RabbitMQ  │
│ 8.4   │           │ 7.4   │           │ 3.13      │
└───────┘           └───────┘           └───────────┘
```

### Gateway 路由映射

| 请求路径 | 目标服务 | 说明 |
|---|---|---|
| `/api/users/**` | user-service | 登录注册、用户资料 |
| `/api/projects/**`, `/api/project-members/**`, `/api/project-catalog/**`, `/api/project-catalogs/**`, `/api/project-quotas/**`, `/api/project-create-requests/**`, `/api/storage/**` | project-service | 项目管理 |
| `/api/files/**`, `/api/folders/**`, `/api/trash/**`, `/api/im-file-cards/**` | file-service | 文件操作 |
| `/api/teams/**`, `/api/admin/teams/**`, `/api/permissions/**` | team-service | 团队与权限 |
| `/api/shares/**` | share-service | 分享管理（需登录） |
| `/api/public/shares/**` | share-service | 公开分享页（无需登录） |
| `/api/email/**` | email-service | 邮件服务 |
| `/im-api/**` | im-service（StripPrefix=1） | IM HTTP API（前端直连） |
| `/api/im/**`, `/api/team-collaboration/**` | im-service | IM HTTP API |
| `/ws`, `/ws/**` | im-service:19090 | WebSocket |
| `/api/**`（兜底） | project-service | 未匹配请求 |

**鉴权白名单（无需 Token）：**
- `/api/users/login`
- `/api/users/register`
- `/api/public/shares/**`
- `/actuator/**`

### 服务间同步调用关系

```
project-service → file-service    （存储用量查询）
project-service → team-service    （权限校验、成员查询）
project-service → user-service    （用户信息查询）
file-service → team-service       （文件访问权限校验）
share-service → file-service      （分享内容解析）
team-service → file-service       （团队存储统计）
team-service → project-service    （团队项目列表）
```

### 服务间调用鉴权

服务间同步调用使用 `X-Internal-Service-Token` 请求头进行鉴权，所有服务必须配置相同的 `INTERNAL_SERVICE_TOKEN` 环境变量。

### RabbitMQ 事件路由

| Routing Key | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `team.created` | team-service | im-service | 团队创建同步 |
| `team.updated` | team-service | im-service | 团队资料更新 |
| `team.member.added` | team-service | im-service | 成员加入 |
| `team.member.removed` | team-service | im-service | 成员移除 |
| `file.resource.changed` | file-service | im-service | 文件变更→缓存失效 |
| `user.profile.updated` | user-service | im-service | 用户资料→缓存同步 |

### 认证机制

- **Sa-Token（token-style: uuid）**：所有服务共享 Redis 会话存储，配置（token-name、timeout、redis.prefix）必须一致。pom.xml 虽然包含 `sa-token-jwt` 依赖，但实际配置为 UUID 令牌模式
- **Gateway 前置鉴权**：所有 `/api/**` 请求在 Gateway 层统一校验登录态，白名单路径放行
- **前端认证方式**：前端通过 HttpOnly Cookie 携带 Token，非 Authorization 请求头方式
- **服务间调用**：使用 `X-Internal-Service-Token` 请求头鉴权
- **权限系统**：支持系统级 RBAC（`system_admin`、`system_user`）和团队级 RBAC（`team_owner`、`team_admin`、`team_member` 及自定义角色）

### 权限码体系

**系统级权限码（`SystemPermissionCodes`）：**
- `system:access` - 系统访问
- `file:upload` / `file:read` / `file:write` / `file:delete` - 文件操作
- `folder:create` - 文件夹创建
- `trash:read` - 回收站查看
- `share:create` / `share:read` / `share:manage` - 分享操作
- `im:file-card` - IM 文件卡片
- `system:role:manage` / `system:permission:read` / `system:audit:read` - 系统管理
- `team:create` - 团队创建

**团队级权限码（`TeamPermissionCodes`）：**
- `team:view` / `team:update` - 团队查看/更新
- `team:member:view` / `team:member:create` / `team:member:invite` / `team:member:assign-role` / `team:member:remove` - 成员管理
- `team:announcement:publish` - 公告发布
- `team:mute:manage` - 禁言管理
- `team:invite-link:manage` - 邀请链接管理
- `team:join-request:review` - 加入申请审核
- `team:role:manage` / `team:permission:read` / `team:audit:read` - 权限管理
- `team:project:manage` - 项目管理
- `team:file:read` / `team:file:write` / `team:file:delete` - 团队文件操作
- `team:storage:allocate` - 存储分配

### 包结构约定

**大多数服务**（project-service、user-service、file-service、share-service、team-service）采用传统分层：
- `controller/` — REST 接口
- `service/` — 业务逻辑
- `mapper/` — MyBatis Mapper 接口
- `entity/` — 数据库实体
- `dto/` — 请求 DTO
- `vo/` — 响应视图对象
- `config/` — Spring 配置

**`zxyz-email-service`** 和 **`zxyz-im-service`** 采用 DDD 风格：
- `interfaces/` — REST 控制器和 DTO
- `application/` — 应用服务
- `domain/` — 领域模型和枚举
- `infrastructure/` — Mapper、外部服务实现
- `config/` — 配置类

## Docker 部署

### 快速启动

```bash
# 1. 复制环境变量模板
cp .env.example .env

# 2. 修改 .env 文件中的密码和配置
# 必须修改所有 CHANGE_ME 开头的变量

# 3. 启动所有服务
docker-compose up -d
```

### 启动顺序

Docker Compose 通过 `depends_on` + `condition: service_healthy` 保证启动顺序：

```
MySQL → Redis → RabbitMQ → Nacos → 业务服务 → Gateway → Frontend
```

### 访问地址

**Docker 部署（通过 docker-compose）：**

仅以下服务映射了外部端口，其余服务仅在 Docker 内部网络可达：

- 前端：`http://localhost:${HTTP_PORT:-80}`（通过 Nginx 反代至 Gateway）
- Nacos 控制台：`http://localhost:${NACOS_PORT:-8848}/nacos`
- RabbitMQ 管理界面：`http://localhost:${RABBITMQ_MGMT_PORT:-15672}`

> 注意：Docker 部署下 Gateway（18000）和各业务服务端口未映射到宿主机，需通过前端或 Nginx 访问。

**本地直接运行服务：**

- API Gateway：`http://localhost:18000`
- 各业务服务：`http://localhost:{端口号}`（如项目服务 18080、IM 服务 18081 等）
- Knife4j API 文档：`http://localhost:18080/doc.html`（需设置 `knife4j.enable=true`）

## 目录结构

```
ZXYZdatabaseBack/
├── pom.xml                              # Maven 根工程（聚合 9 个子模块）
├── Dockerfile                           # 通用 Docker 构建文件
├── sql/                                 # 数据库 Schema 文件
│   ├── 00-init-zxyz.sh                  # 数据库初始化脚本
│   ├── schema_project.sql               # 项目服务 Schema
│   ├── schema_im.sql                    # IM 服务 Schema
│   ├── schema_email.sql                 # 邮件服务 Schema
│   ├── schema_share.sql                 # 分享服务 Schema
│   ├── schema_file.sql                  # 文件服务 Schema
│   ├── schema_team.sql                  # 团队服务 Schema
│   └── schema_user.sql                  # 用户服务 Schema
├── zxyz-common/                         # 公共模块
│   └── src/main/java/uno/acloud/
│       ├── common/                      # 共享常量、工具类
│       │   ├── ErrorCode.java           # 错误码定义
│       │   ├── Result.java              # 统一响应结构
│       │   ├── GlobalExceptionHandler.java  # 全局异常处理
│       │   ├── SystemPermissionCodes.java   # 系统权限码
│       │   ├── TeamPermissionCodes.java     # 团队权限码
│       │   ├── TeamPermissionPolicy.java    # 团队权限策略
│       │   ├── SystemRoleCodes.java         # 系统角色码
│       │   ├── TeamRoleCodes.java           # 团队角色码
│       │   ├── FileSpaceType.java           # 文件空间类型
│       │   ├── FileNodeType.java            # 文件节点类型
│       │   ├── FileDeleteStatus.java        # 文件删除状态
│       │   ├── FileObjectDeleteStatus.java  # OSS 对象删除状态
│       │   └── oss/                         # OSS 签名相关
│       ├── exception/                   # 异常定义
│       ├── dto/                         # 共享 DTO
│       ├── vo/                          # 共享 VO
│       └── event/                       # 领域事件
├── zxyz-gateway/                        # API Gateway
│   └── src/main/java/uno/acloud/gateway/
│       ├── ZxyzGatewayApplication.java  # 启动类
│       └── filter/                      # 鉴权过滤器
├── zxyz-project-service/                # 项目服务
│   └── src/main/java/uno/acloud/
│       ├── controller/                  # REST 接口
│       ├── service/                     # 业务逻辑
│       ├── mapper/                      # MyBatis Mapper
│       ├── entity/                      # 数据库实体
│       ├── dto/                         # 请求 DTO
│       ├── vo/                          # 响应 VO
│       ├── config/                      # 配置类
│       └── infrastructure/              # 外部服务调用封装
├── zxyz-im-service/                     # IM 服务
│   └── src/main/java/uno/acloud/im/
│       ├── interfaces/                  # REST 接口
│       ├── application/                 # 应用服务
│       ├── domain/                      # 领域模型
│       ├── infrastructure/              # 基础设施
│       └── config/                      # 配置类
├── zxyz-email-service/                  # 邮件服务（DDD 风格）
│   └── src/main/java/uno/acloud/email/
│       ├── interfaces/                  # REST 接口
│       ├── application/                 # 应用服务
│       ├── domain/                      # 领域模型
│       ├── infrastructure/              # 基础设施
│       └── config/                      # 配置类
├── zxyz-user-service/                   # 用户服务
│   └── src/main/java/uno/acloud/user/
│       ├── controller/                  # REST 接口
│       ├── service/                     # 业务逻辑
│       ├── mapper/                      # MyBatis Mapper
│       ├── entity/                      # 数据库实体
│       ├── dto/                         # 请求 DTO
│       ├── vo/                          # 响应 VO
│       └── config/                      # 配置类
├── zxyz-share-service/                  # 分享服务
│   └── src/main/java/uno/acloud/share/
│       ├── controller/                  # REST 接口
│       ├── service/                     # 业务逻辑
│       ├── mapper/                      # MyBatis Mapper
│       ├── entity/                      # 数据库实体
│       ├── dto/                         # 请求 DTO
│       └── vo/                          # 响应 VO
├── zxyz-file-service/                   # 文件服务
│   └── src/main/java/uno/acloud/file/
│       ├── controller/                  # REST 接口
│       ├── service/                     # 业务逻辑
│       ├── mapper/                      # MyBatis Mapper
│       ├── entity/                      # 数据库实体
│       ├── dto/                         # 请求 DTO
│       ├── vo/                          # 响应 VO
│       └── config/                      # 配置类
└── zxyz-team-service/                   # 团队服务
    └── src/main/java/uno/acloud/team/
        ├── controller/                  # REST 接口
        ├── service/                     # 业务逻辑
        ├── mapper/                      # MyBatis Mapper
        ├── entity/                      # 数据库实体
        ├── dto/                         # 请求 DTO
        ├── vo/                          # 响应 VO
        └── config/                      # 配置类
```

## API 文档

项目集成了 Knife4j + springdoc OpenAPI，运行时可通过以下地址访问 API 文档：

- 项目服务：`http://localhost:18080/doc.html`（需设置 `knife4j.enable=true`）
- Swagger UI：`http://localhost:18080/swagger-ui/index.html`

**注意：** `knife4j.enable` 默认为 `false`，生产环境不建议开启。

## 健康检查

各服务均集成了 Spring Boot Actuator，提供健康检查端点：

```
GET /actuator/health
```

Docker 部署时，健康检查用于确保服务启动顺序和可用性。

## IDE 处理建议

如果在 IDE 中出现"源码存在但包不存在 / 找不到符号"的红线，请按以下顺序处理：

1. 打开的是 `ZXYZdatabaseBack` 根目录（不是单独的子模块）
2. Maven 面板中同时存在所有子模块
3. 重新导入 `ZXYZdatabaseBack/pom.xml`
4. 强制刷新 Maven 项目
5. 确认 Project SDK 为 JDK 17
6. 重建 IDE 索引或清理缓存

命令行 `mvn clean -DskipTests compile` 成功后，不再围绕这些 import 做额外无效修改。
