# Infrastructure

## 数据库

MySQL 8.4，10 个独立数据库：`zxyz_project`, `zxyz_user`, `zxyz_file`, `zxyz_share`, `zxyz_team`, `zxyz_im`, `zxyz_email`, `zxyz_config`, `nacos`, `zxyz_audit`

Schema 文件：根目录 `sql/`（Docker 挂载源）和 `ZXYZdatabaseBack/sql/`（本地开发参考）。
初始化脚本 `sql/00-init-zxyz.sh` 在 Docker 首次启动时自动执行。

## Redis

默认 localhost:6379。Sa-Token 会话存储（所有服务共享）、分布式锁（Redisson）。

## Nacos

默认 localhost:8848，Spring Cloud 服务注册中心。
环境变量：`NACOS_SERVER_ADDR`, `NACOS_USERNAME`, `NACOS_PASSWORD`

## RabbitMQ

默认 localhost:5672，管理界面 localhost:15672。
Topic Exchange `zxyz.topic`，各服务监听各自 routing key。

## Gateway 路由映射

| 请求路径 | 目标服务 |
|---|---|
| `/api/users/**` | user-service |
| `/api/projects/**`, `/api/project-members/**`, `/api/project-catalog/**`, `/api/project-catalogs/**`, `/api/project-quotas/**`, `/api/project-create-requests/**`, `/api/storage/**` | project-service |
| `/api/files/**`, `/api/folders/**`, `/api/trash/**`, `/api/im-file-cards/**` | file-service |
| `/api/teams/**`, `/api/admin/teams/**`, `/api/permissions/**` | team-service |
| `/api/shares/**` | share-service（需登录） |
| `/api/public/shares/**` | share-service（无需登录） |
| `/api/email/**` | email-service |
| `/im-api/**` | im-service（StripPrefix） |
| `/api/im/**`, `/api/team-collaboration/**` | im-service |
| `/ws`, `/ws/**` | im-service:19090（直连 Netty） |
| `/api/admin/email/**` | email-service |
| `/api/admin/database/**` | project-service |
| `/api/internal/files/**` | file-service（内部服务直连，不经网关） |
| `/api/**`（兜底） | project-service |

鉴权白名单：`/api/users/login`, `/api/users/register`, `/api/public/shares/**`, `/actuator/**`

## 服务间同步调用

```
project-service → file-service    （存储用量查询）
project-service → team-service    （权限校验、成员查询）
project-service → user-service    （用户信息查询）
file-service → team-service       （文件访问权限校验）
share-service → file-service      （分享内容解析，内部直连 /api/internal/files/**，不经网关）
team-service → file-service       （团队存储统计）
team-service → project-service    （团队项目列表）
```

## RabbitMQ 事件路由

| Routing Key | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `team.created` | team-service | im-service | 团队创建同步 |
| `team.updated` | team-service | im-service | 团队资料更新 |
| `team.member.added` | team-service | im-service | 成员加入 |
| `team.member.removed` | team-service | im-service | 成员移除 |
| `file.resource.changed` | file-service | im-service | 文件变更→缓存失效 |
| `user.profile.updated` | user-service | im-service | 用户资料→缓存同步 |
| `audit.*` | 各服务 | audit-service | 操作日志审计 |

## Docker 部署

详见 `DEPLOYMENT.md`。
- `docker-compose.yml` 编排：MySQL, Redis, RabbitMQ, Nacos, Nacos-log-cleanup, Gateway, 9 业务服务, frontend-nginx, Loki, Promtail
- 统一 Dockerfile（`ZXYZdatabaseBack/Dockerfile`），`MODULE` 参数选择模块
- 环境变量：根目录 `.env`（基于 `.env.example`），密码类变量必须修改
- 启动顺序：MySQL → Redis → RabbitMQ → Nacos → 业务服务 → Gateway → frontend-nginx
- 备份脚本：`scripts/backup.sh`
