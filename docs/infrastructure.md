# Infrastructure

## 数据库

MySQL 8.4，10 个独立数据库：`zxyz_project`, `zxyz_user`, `zxyz_file`, `zxyz_share`, `zxyz_team`, `zxyz_im`, `zxyz_email`, `zxyz_config`, `nacos`, `zxyz_audit`

Schema 文件：**表结构仅由各服务的 Flyway 迁移管理** —— `ZXYZdatabaseBack/zxyz-<svc>/src/main/resources/db/migration/V*.sql`，**不要**再维护 `sql/schema_*.sql`（与 `CLAUDE.md` 一致）。
根目录 `sql/` 只放三件**非表结构**的东西：`00-init-zxyz.sh`（数据卷首次创建时初始化各库与账号，由 MySQL 官方镜像 entrypoint 执行，`/var/lib/mysql` 非空时**不会重跑**）、`nacos-mysql-schema.sql`（Nacos 3.x 不会自动建表，随 initdb 导入）、`grant-least-privilege.sql`（手工收敛账号权限，不在启动链路里）。

## Redis

默认 localhost:6379。分布式锁（Redisson）在**业务库**（按 `REDIS_DATABASE` 隔离：gateway 0 / project 1 / im 2 / email 3 / user 4 / share 5 / file 6 / team 7 / audit 8，admin 不消费该键）。

**Sa-Token 会话存储代码已切到独立库 db9**（2026-10-08，`701af68` 合并）：9 个服务（gateway/project/im/email/user/share/file/team/admin）引入 `sa-token-alone-redis`，会话统一落 `sa-token.alone-redis` 配置的独立连接（`database: 9`，配置在 `zxyz-common/src/main/resources/application-common.yml`；`zxyz-audit-service` 纯 MQ 消费者，刻意不引）。该键**刻意只写 application-common.yml、不进 nacos** —— gateway 不 import `zxyz-static.yml`，写错落点会导致「业务服务写 db9、gateway 校验读 db0」= 全站登录失效。✅ **生产已生效**（2026-10-10 实测：db9 的 `satoken:login:*` 键**非空**、db0–8 无 satoken 键，51/52 号台账；键数随会话自然波动（如 3–13 个），**勿钉死具体数字——判据是「非空」不是「等于 N」**，与 CLAUDE.md 同款纪律）：凡 db9 相关变更，部署后仍必须 `redis-cli -n 9 --scan --pattern 'satoken*'` 见非空 key 才算生效（插件对配置异常静默吞，**服务起来了 ≠ 会话已迁移**）。

## Nacos

默认 localhost:8848（API）/ localhost:8080（3.x React 控制台），Spring Cloud 服务注册与配置中心。

环境变量：`NACOS_SERVER_ADDR`, `NACOS_USERNAME`, `NACOS_PASSWORD`, `NACOS_NAMESPACE`
认证变量：`NACOS_AUTH_TOKEN`（JWT 签名密钥）, `NACOS_AUTH_IDENTITY_KEY`, `NACOS_AUTH_IDENTITY_VALUE`

配置模板存放于 `nacos-config/` 目录（**Nacos 的运行真源**），通过 `nacos-config/import.sh` 批量导入（group=`ZXYZ`，命名空间 public）。
改 `nacos-config/**` 并 push 后由 CI 作业 `nacos-import` **自动导入并逐份回读校验**；`zxyz-dynamic.yml` 入库即热更新，其余 11 份需重启服务生效。详见 `nacos-config/README.md`。
共享配置：`zxyz-static.yml`（连接池、Sa-Token 基础键、服务间地址；**会话库配置 `sa-token.alone-redis.*` 例外，不在 nacos**，见上 §Redis）、`zxyz-dynamic.yml`（CORS、认证超时、热更新项）。
敏感值使用 `ENC(...)` 格式（Jasypt AES/GCM 加密），启动时通过 `JASYPT_PASSWORD` 环境变量解密。

## RabbitMQ

默认 localhost:5672，管理界面 localhost:15672。
Topic Exchange `zxyz.topic`，各服务监听各自 routing key。

## Gateway 路由映射

Gateway 共 **14 条具名路由**（`ZXYZdatabaseBack/zxyz-gateway/src/main/resources/application.yml:59-179`），**无任何 `/api/**` 兜底路由**——未匹配下列任何前缀的请求一律 **404**，不会落到 project-service 或其他服务。

| # | 请求路径 | 目标服务 | 转发语义 |
|---|---|---|---|
| 1 | `/ws`, `/ws/**` | im-service:19090（直连 Netty） | SetPath=/ws |
| 2 | `/im-api/**` | im-service | StripPrefix=1 |
| 3 | `/api/im/**`, `/api/team-collaboration/**` | im-service | 原样转发 |
| 4 | `/api/email/**` | email-service | 原样转发 |
| 5 | `/api/public/shares/**` | share-service（无需登录） | 原样转发 |
| 6 | `/api/shares/**` | share-service（需登录） | 原样转发 |
| 7 | `/api/files/**`, `/api/folders/**`, `/api/trash/**`, `/api/im-file-cards/**` | file-service | 原样转发 |
| 8 | `/api/teams/**`, `/api/admin/teams/**`, `/api/permissions/**` | team-service | 原样转发 |
| 9 | `/api/users/**` | user-service | 原样转发 |
| 10 | `/api/admin/email/**` | email-service | RewritePath → `/api/email/internal/**` + 网关注入内部头（见下注） |
| 11 | `/api/admin/database/**` | project-service | 原样转发，**刻意不注入**内部头（见下注） |
| 12 | `/api/admin/storage-providers/**` | file-service | 原样转发 |
| 13 | `/api/admin/**` | admin-service | RewritePath 剥 `/api/admin` 前缀 → `/{segment}` |
| 14 | `/api/projects/**`, `/api/project-members/**`, `/api/project-catalog/**`, `/api/project-catalogs/**`, `/api/project-quotas/**`, `/api/project-create-requests/**`, `/api/storage/**` | project-service | 原样转发 |

> **路由表纪律**：本表与 gateway `application.yml:59-179` 逐条比对于 2026-10-15（60 号审计 A-1，14 条一一对应）；**新增路由必须同步此表**（CLAUDE.md 既有纪律）。历史教训：本节曾长期声称「`/api/**`（兜底）→ project-service」，实际 gateway 从未有过兜底路由——排查 404 时勿按「有兜底」的直觉判断。
>
> - `/api/admin/email/**`：网关自注入 `X-Internal-Service-Token`（`${SVC_GATEWAY_KEY:${INTERNAL_SERVICE_TOKEN}}`）+ `X-Internal-Caller-Service: zxyz-gateway`，重写为 `/api/email/internal/**`——真正走服务间鉴权的桥接（详见 `docs/claude-backend.md`「服务间接口设计规范」）。
> - `/api/admin/database/**`：由 project-service 的 Sa-Token 登录态 + `@SaCheckRole(SYSTEM_ADMIN)` 把关，不参与服务间鉴权（详见 `docs/claude-backend.md`）。
> - `/api/internal/files/**` 是 Docker 内网服务间直连路径（share-service → file-service），**不经网关**，不占路由条目。
> - 2026-10-11（54 号 P2-3）：`/api/admin/email-providers/**` 端点已**删除**——它此前被 admin-service 通配路由剥前缀转发到只有 `/configs` 的 admin-service 必然 404（6 月起即死端点）；前端 0 调用方、SPI 自述未接入发送链路，按最小攻击面原则移除而非补路由。

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
- `docker-compose.yml` 编排：MySQL, Redis, RabbitMQ, Nacos, Nacos-log-cleanup, Flyway（tools profile，默认不启动）, Gateway, 9 业务服务（含 admin-service）, frontend-nginx；Loki/Promtail 已迁出至 `docker-compose.observability.yml`（默认不启动）
- 统一 Dockerfile（`ZXYZdatabaseBack/Dockerfile`），`MODULE` 参数选择模块
- 环境变量：根目录 `.env`（基于 `.env.example`），密码类变量必须修改
- 认证相关：`INTERNAL_SERVICE_TOKEN`（服务间鉴权）、`SHARE_COOKIE_SECRET`（Cookie 签名）、`JASYPT_PASSWORD`（配置加密密钥）、`AUTH_COOKIE_SECURE`/`AUTH_COOKIE_DOMAIN`/`AUTH_TOKEN_TIMEOUT`/`AUTH_LONG_LIVED_TIMEOUT`（Sa-Token 会话）
- 启动顺序：MySQL → Redis → RabbitMQ → Nacos → 业务服务 → Gateway → frontend-nginx
- 备份脚本：`scripts/backup.sh`
- 回滚脚本：`scripts/rollback.sh`（依赖 CI/CD 生成的 `.env.previous` 记录上一版本 tag）
- 快速部署：`scripts/deploy-fast.sh`（拉取+重启指定服务）
- 环境验证：`scripts/validate-env.sh`（检查 .env 必需变量）
