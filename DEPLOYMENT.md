# ZXYZ Docker 部署文档

本文档详细说明"指绣云章 (ZXYZ)"平台的 Docker 容器化部署流程，涵盖环境准备、配置说明、启动步骤、架构解析及运维操作。

---

## 目录

- [1. 环境要求](#1-环境要求)
- [2. 服务架构总览](#2-服务架构总览)
- [3. 快速部署步骤](#3-快速部署步骤)
- [3.1 自托管 Runner 安装（CI/CD 加速）](#31-自托管-runner-安装cicd-加速)
- [4. 环境变量详解](#4-环境变量详解)
- [5. 数据库初始化说明](#5-数据库初始化说明)
- [6. Dockerfile 解析](#6-dockerfile-解析)
- [7. docker-compose.yml 详解](#7-docker-composeyml-详解)
- [8. Nginx 反向代理配置说明](#8-nginx-反向代理配置说明)
  - [8.5 TLS 终止（容器内 nginx，opt-in）](#85-tls-终止容器内-nginxopt-in)
- [9. 常见问题排查](#9-常见问题排查)
- [10. 生产环境部署建议](#10-生产环境部署建议)
- [11. 维护与更新操作](#11-维护与更新操作)
- [12. 阿里云 ACR 镜像仓库配置](#12-阿里云-acr-镜像仓库配置)

---

## 1. 环境要求

| 依赖 | 最低版本 | 说明 |
|---|---|---|
| Docker Engine | 20.10+ | 支持 BuildKit 和多阶段构建 |
| Docker Compose | 2.0+ | 使用 `services` 顶层键（非 Compose v1 的 `version` 字段） |
| 磁盘空间 | 10 GB+ | 含镜像构建缓存和数据卷 |
| 内存 | 4 GB+ | 建议 8 GB 以上，8 个 Java 服务 + MySQL + Redis + RabbitMQ + Nacos |
| 操作系统 | Linux x86_64 | 推荐 Ubuntu 22.04 / CentOS 8+，Windows/macOS 仅供开发测试 |

---

## 2. 服务架构总览

### 2.1 容器清单

| 容器名 | 镜像 | 内部端口 | 外部映射端口 | 依赖 |
|---|---|---|---|---|
| `zxyz-mysql` | `mysql:8.4.0-oraclelinux8` | 3306 | 无 | - |
| `zxyz-nacos` | `nacos/nacos-server:v3.2.1` | 8848 | `${NACOS_PORT:-8848}` | mysql |
| `zxyz-redis` | `redis:7.4-alpine` | 6379 | 无 | - |
| `zxyz-rabbitmq` | `rabbitmq:3.13-management-alpine` | 5672 / 15672 | `${RABBITMQ_MGMT_PORT:-15672}` | - |
| `zxyz-project-service` | `zxyz-project-service` | 18080 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-im-service` | `zxyz-im-service` | 18081, 19090 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-email-service` | `zxyz-email-service` | 18082 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-user-service` | `zxyz-user-service` | 18083 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-share-service` | `zxyz-share-service` | 18084 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-file-service` | `zxyz-file-service` | 18085 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-team-service` | `zxyz-team-service` | 18086 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-audit-service` | `zxyz-audit-service` | 18087 | 无 | mysql, redis, rabbitmq, nacos |
| `zxyz-gateway` | `zxyz-gateway` | 18000 | 无 | nacos |
| `zxyz-frontend-nginx` | `zxyz-frontend-nginx` | 80 | `${HTTP_PORT:-80}` | gateway |

### 2.2 请求流向

```
浏览器
  │
  ▼
frontend-nginx (:80)
  │
  ├── 静态资源 → Nginx 本地 /usr/share/nginx/html
  ├── /api/** → gateway (:18000) → 各业务服务
  ├── /im-api/** → gateway (:18000) → im-service (:18081)
  └── /ws → gateway (:18000) → im-service Netty (:19090)
```

### 2.3 服务间同步调用

```
project-service ──→ file-service    (存储用量查询)
project-service ──→ team-service    (权限校验、成员查询)
project-service ──→ user-service    (用户信息查询)
file-service    ──→ team-service    (文件访问权限校验)
share-service   ──→ file-service    (分享内容解析)
team-service    ──→ file-service    (团队存储统计)
team-service    ──→ project-service (团队项目列表)
```

### 2.4 RabbitMQ 事件路由

| Routing Key | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `team.created` | team-service | im-service | 团队创建同步 |
| `team.updated` | team-service | im-service | 团队资料更新 |
| `team.member.added` | team-service | im-service | 成员加入 |
| `team.member.removed` | team-service | im-service | 成员移除 |
| `file.resource.changed` | file-service | im-service | 文件变更缓存失效 |
| `user.profile.updated` | user-service | im-service | 用户资料缓存同步 |

### 2.5 网络与卷

- **网络**：所有容器共享 `zxyz-net` 桥接网络，容器间通过服务名互相访问。
- **持久化卷**（绑定挂载到 `${DATA_DIR:-./data}/` 目录）：

| 挂载路径 | 容器内路径 | 用途 |
|---|---|---|
| `${DATA_DIR}/mysql` | `/var/lib/mysql` | MySQL 数据文件 |
| `${DATA_DIR}/redis` | `/data` | Redis AOF 持久化 |
| `${DATA_DIR}/rabbitmq` | `/var/lib/rabbitmq` | RabbitMQ 数据 |
| `${DATA_DIR}/nacos` | `/home/nacos/data` | Nacos 配置数据 |

---

## 3. 快速部署步骤

### 3.1 克隆代码

```bash
git clone <仓库地址> zxyz
cd zxyz
```

### 3.2 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件，**必须修改**以下变量（将 `CHANGE_ME` 开头的值替换为你自己的密码和密钥）：

- `MYSQL_ROOT_PASSWORD` — MySQL root 密码
- `REDIS_PASSWORD` — Redis 密码
- `RABBITMQ_USER` / `RABBITMQ_PASSWORD` — RabbitMQ 凭据
- `INTERNAL_SERVICE_TOKEN` — 服务间调用鉴权 Token（建议 32 位随机字符串）
- `SHARE_COOKIE_SECRET` — 分享链接 Cookie 签名密钥
- `JASYPT_PASSWORD` — Jasypt 加密密钥（解密 Nacos 配置中的敏感值）
- `NACOS_PASSWORD` — Nacos 登录密码
- `NACOS_AUTH_TOKEN` — Nacos JWT 签名密钥
- `NACOS_AUTH_IDENTITY_VALUE` — Nacos 身份验证 Value
- `CONFIG_DB_PASSWORD` — 配置中心数据库密码
- `FRONTEND_BASE_URL` — 前端访问地址（如 `http://你的服务器IP`）
- `OSS_*` — 阿里云 OSS 配置
- `EMAIL_*` — SMTP 邮件配置（暂不使用时可设 `EMAIL_ENABLED=false`）

生成随机密钥示例：

```bash
# 生成 32 位随机字符串
openssl rand -hex 16
# 生成 Nacos JWT 密钥（Base64 编码，解码后 32 字节）
openssl rand -base64 32
```

### 3.3 构建并启动

```bash
# 构建所有镜像并后台启动
docker compose up -d --build
```

首次启动会：
1. 拉取基础镜像（MySQL、Redis、RabbitMQ、Nacos、Maven、Node、Nginx）
2. 使用 Maven 多阶段构建编译 8 个后端服务镜像
3. 使用 Node 多阶段构建编译前端并打包为 Nginx 镜像
4. 按依赖顺序启动容器（通过健康检查 + `depends_on` 控制）

#### 3.3.1 初始管理员引导

`zxyz-user-service` 每次启动都会幂等地确保存在一个**初始管理员账号**，用于首次登录与管理：

- **是否创建**：若配置的用户名（`app.admin.bootstrap.username`，默认 `admin`）已存在，则直接跳过——可安全重复运行（重启/重新部署都不会重复创建）。否则在数据库创建该账号。
- **密码来源**：
  - 配置了 `app.admin.bootstrap.password`（部署变量 `ADMIN_INIT_PASSWORD`）→ 使用该密码。
  - 未配置 → 自动生成 16 位字母数字随机密码，并在 `zxyz-user-service` 日志中以 **warn** 级别打印一次明文（含「请立即登录修改密码」提示）。获取方式：
    ```bash
    docker logs zxyz-user-service 2>&1 | grep "初始管理员"
    ```
- **角色分配**：账号创建成功后，通过内部调用 `team-service` 授予 `SYSTEM_ADMIN` 角色（唯一正确的管理员授权入口）。角色分配失败会被捕获并仅记日志，**不会阻止应用启动**。
- **开关**：`app.admin.bootstrap.enabled`（默认 `true`）设为 `false` 可完全禁用引导。

**环境变量**：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADMIN_INIT_USERNAME` | `admin` | 初始管理员用户名。映射到 `app.admin.bootstrap.username` |
| `ADMIN_INIT_PASSWORD` | 空 | 初始管理员密码；留空则首次启动随机生成并打印到日志。映射到 `app.admin.bootstrap.password` |

**安全建议**：生产环境**务必**在部署前于 `.env` 中显式设置 `ADMIN_INIT_PASSWORD` 为高强度密码，避免依赖随机密码明文日志；无论何种方式创建，请登录后立即修改密码。

### 3.4 验证服务状态

```bash
# 查看所有容器状态
docker compose ps

# 查看日志（实时跟踪）
docker compose logs -f

# 查看单个服务日志
docker compose logs -f gateway

# 检查 Gateway 健康
curl http://localhost:${HTTP_PORT:-80}/actuator/health
```

所有容器的 `STATUS` 显示为 `healthy` 后，在浏览器访问 `http://服务器IP` 即可打开前端页面。


### 3.1 自托管 Runner 安装（CI/CD 加速）

GitHub-hosted Runner 在国内存在队列等待和 Docker 层缓存丢失问题。将 Runner 安装到部署服务器上可显著缩短 CI/CD 耗时。

**前提条件**：
- 服务器已安装 Docker Engine 20.10+ 和 Docker Compose 2.0+
- 服务器 CPU >= 2 核，内存 >= 4 GB（构建 Java 镜像需要 ~2GB）
- 服务器已安装 Git

**安装步骤**：

```bash
# 1. 创建 runner 用户（建议不要用 root）
useradd -m -s /bin/bash github-runner
usermod -aG docker github-runner
su - github-runner

# 2. 下载 runner 包（从 GitHub 仓库 Settings -> Actions -> Runners 获取最新版本）
mkdir actions-runner && cd actions-runner
curl -o actions-runner-linux-x64.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.321.0/actions-runner-linux-x64-2.321.0.tar.gz
tar xzf actions-runner-linux-x64.tar.gz

# 3. 配置并注册 runner
./config.sh --url https://github.com/1529119384/zxyz --token <TOKEN>

# 4. 安装并启动 systemd 服务
sudo ./svc.sh install github-runner
sudo ./svc.sh start

# 5. 验证状态
sudo systemctl status actions.runner.1529119384.zxyz
```

**获取 Token**：GitHub 仓库 -> Settings -> Actions -> Runners -> New runner -> 复制 token。

**CI/CD 配置变更**：

在 `.github/workflows/ci-cd.yml` 中将需要自托管的 job `runs-on` 改为 `self-hosted`：

```yaml
jobs:
  build-and-push:
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.repository_owner }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          file: ZXYZdatabaseBack/Dockerfile
          push: true
          tags: ghcr.io/${{ github.repository_owner }}/zxyz-project-service:${{ inputs.tag }}
```

**注意事项**：
- 自托管 Runner 执行的是仓库代码，请确保仓库权限可控
- 构建时 CPU/内存占用较高，建议在低峰期推送
- Runner 更新：重新下载新版本包后执行 `./svc.sh stop && ./config.sh ... && ./svc.sh start`
- 若服务器重启，Runner 会自动恢复（systemd 服务）

---
### 3.5 停止与清理

```bash
# 停止所有服务（保留数据卷）
docker compose down

# 停止并删除数据卷（会清除所有数据库数据，慎用）
docker compose down -v
```

---

## 4. 环境变量详解

以下变量定义在 `.env` 文件中，由 `docker-compose.yml` 引用。

### 4.1 基础设施密码（必须修改）

| 变量 | 默认值 | 说明 | 是否必须修改 |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `CHANGE_ME_MYSQL_PASSWORD` | MySQL root 密码 | **是** |
| `REDIS_PASSWORD` | `CHANGE_ME_REDIS_PASSWORD` | Redis 访问密码 | **是** |
| `RABBITMQ_USER` | `CHANGE_ME_RABBITMQ_USER` | RabbitMQ 用户名 | **是** |
| `RABBITMQ_PASSWORD` | `CHANGE_ME_RABBITMQ_PASSWORD` | RabbitMQ 密码 | **是** |

### 4.2 应用密钥（必须修改）

| 变量 | 默认值 | 说明 | 是否必须修改 |
|---|---|---|---|
| `INTERNAL_SERVICE_TOKEN` | `CHANGE_ME_INTERNAL_SERVICE_TOKEN` | 后端服务间 HTTP 调用的鉴权 Token，所有服务必须一致。建议 32 位随机字符串 | **是** |
| `SHARE_COOKIE_SECRET` | `CHANGE_ME_SHARE_COOKIE_SECRET` | 分享链接 Cookie 签名密钥，必须与 `INTERNAL_SERVICE_TOKEN` 不同 | **是** |
| `JASYPT_PASSWORD` | `CHANGE_ME_JASYPT_PASSWORD` | Jasypt 加密密钥，用于解密 Nacos 配置中 `ENC(...)` 格式的敏感值。所有服务共享，建议 32 位随机字符串（`openssl rand -base64 32`） | **是** |

### 4.3 阿里云 OSS（文件功能必须）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OSS_REGION` | `cn-shenzhen` | OSS 区域 |
| `OSS_BUCKET` | `your-bucket-name` | OSS Bucket 名称 |
| `OSS_ENDPOINT` | `https://oss-cn-shenzhen.aliyuncs.com` | OSS Endpoint URL |
| `OSS_PUBLIC_BASE_URL` | `https://your-bucket.oss-cn-shenzhen.aliyuncs.com` | OSS 公共访问基础 URL |
| `OSS_ACCESS_KEY_ID` | `CHANGE_ME_OSS_ACCESS_KEY` | 阿里云 AccessKey ID |
| `OSS_ACCESS_KEY_SECRET` | `CHANGE_ME_OSS_ACCESS_SECRET` | 阿里云 AccessKey Secret |

### 4.4 邮件服务

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMAIL_ENABLED` | `true` | 是否启用邮件功能 |
| `EMAIL_ASYNC` | `true` | 是否异步发送邮件 |
| `EMAIL_HOST` | `smtp.qq.com` | SMTP 服务器地址 |
| `EMAIL_PORT` | `587` | SMTP 端口 |
| `EMAIL_USERNAME` | `CHANGE_ME_SMTP_ACCOUNT` | SMTP 用户名 |
| `EMAIL_PASSWORD` | `CHANGE_ME_SMTP_AUTH_CODE` | SMTP 授权码 |
| `EMAIL_FROM` | `CHANGE_ME_SENDER_EMAIL` | 发件人地址 |
| `EMAIL_CONFIG_SECRET` | `CHANGE_ME_RANDOM_EMAIL_CONFIG_SECRET` | 邮件配置加密密钥 |

### 4.5 服务配置

| 变量 | 默认值 | 说明 |
|---|---|---|
| `APP_IMAGE_TAG` | `latest` | 镜像标签，更新时可改为日期或版本号 |
| `HTTP_PORT` | `80` | 前端对外 HTTP 端口 |
| `REDIS_DATABASE` | `0` | Redis 数据库编号 |
| `RABBITMQ_MGMT_PORT` | `15672` | RabbitMQ 管理界面端口 |
| `FRONTEND_BASE_URL` | `http://YOUR_SERVER_IP` | 前端访问地址，用于生成分享链接 |
| `CORS_ALLOWED_ORIGINS` | `*` | 允许的跨域来源，生产环境建议改为实际域名 |
| `GATEWAY_TRUSTED_PROXIES` | （空） | 网关可信代理网段（逗号分隔的单 IP/CIDR，如 `172.18.0.0/16`；取值见 `.env.example` 注释）。**留空会导致限流退化为全局单桶**：网关便不解析 `X-Forwarded-For`，下游按真实 IP 的限流（登录/注册/分享提取码验证/邮箱验证码）全部塌成同一个桶，攻击者按阈值节奏请求即可让全平台用户一起失败。`scripts/validate-env.sh` 会就此告警 |
| `AUTH_COOKIE_SECURE` | `false` | Sa-Token Cookie secure 标志（生产环境改为 true） |
| `AUTH_COOKIE_DOMAIN` | （空） | Sa-Token Cookie 域名（跨子域共享 session 时设置） |
| `AUTH_TOKEN_TIMEOUT` | `43200` | 普通登录 Token 超时时间（秒），默认 12 小时 |
| `AUTH_LONG_LIVED_TIMEOUT` | `604800` | "记住我" Token 超时时间（秒），默认 7 天 |
| `DATABASE_MAINTENANCE_ENABLED` | `false` | 是否启用数据库导入功能（生产环境保持 false） |
| `TIME_ASPECT_ENABLED` | `false` | 是否启用性能切面日志（生产环境保持 false） |

### 4.6 Nacos 注册中心

| 变量 | 默认值 | 说明 |
|---|---|---|
| `NACOS_PORT` | `8848` | Nacos API 端口（绑定 127.0.0.1） |
| `NACOS_CONSOLE_PORT` | `8080` | Nacos 控制台端口（3.x React UI，绑定 127.0.0.1） |
| `NACOS_USERNAME` | `nacos` | Nacos 登录用户名（仅 nacos server 控制台与 import.sh 使用；应用服务客户端已去凭证，见下方说明） |
| `NACOS_PASSWORD` | `CHANGE_ME_NACOS_PASSWORD` | Nacos 控制台管理员密码（生产环境必须修改；应用服务客户端已去凭证） |
| `NACOS_AUTH_TOKEN` | `CHANGE_ME_NACOS_AUTH_TOKEN` | Nacos JWT 签名密钥（Base64 编码，解码后 >= 32 字节） |
| `NACOS_AUTH_IDENTITY_KEY` | `serverIdentity` | Nacos 身份验证 Key |
| `NACOS_AUTH_IDENTITY_VALUE` | `CHANGE_ME_NACOS_IDENTITY` | Nacos 身份验证 Value（生产环境必须修改） |
| `NACOS_NAMESPACE` | （空） | Nacos 命名空间 ID，多环境隔离时可设置为对应环境的命名空间 UUID |

#### 未来启用 Nacos 鉴权（迁移 checklist）

> ⚠️ **严禁在客户端为空凭证时开启 `nacos.core.auth.enabled=true`**：所有服务将无法注册/拉取配置，系统整体不可用。

当前应用客户端以**空凭证**运行（各服务 yml 的 `${NACOS_USERNAME:}` 默认为空，nacos-client 在 username 为空时跳过 HTTP login）。这是因为 server 3.2.1 的 `/v1/auth/login` 与 `/v3/auth/user/login` 端点均被 Spring 层 403 拦截（与凭证对错无关），客户端带凭证反而每 5 秒刷 `login failed: 403`。未来若需开启鉴权，按以下顺序：

1. **先解决 server login 端点 403**（升级 Nacos server 至修复版本，或排查 3.2.1 console 鉴权过滤器对 login 端点的拦截）——这是前置条件，升级客户端无法绕过（v3 login 同样被拦，且 403 不触发客户端的 v1 回退）。
2. 回填 11 处 yml 凭证（10 个服务 `application.yml` + `zxyz-common` 的 `application-common.yml`：`${NACOS_USERNAME:}` → 恢复 env 引用与默认值）。
3. 恢复 `docker-compose.yml` 中 10 个应用服务的 `NACOS_USERNAME`/`NACOS_PASSWORD` 注入。
4. 重建镜像并滚动部署，验证日志无 403。
5. 最后才设置 `nacos.core.auth.enabled=true` 并重启 nacos 容器。
6. 同步修复 `nacos-config/import.sh`（其 `/v1/auth/login` 获取 token 的逻辑同样受 403 影响；建议增加「鉴权关闭时跳过登录、直连 v3 admin」的守卫逻辑）。

### 4.7 Knife4j API 文档

| 变量 | 默认值 | 说明 |
|---|---|---|
| `KNIFE4J_BASIC_ENABLE` | `false` | 是否启用 Knife4j Basic 认证保护，生产环境建议开启 |
| `KNIFE4J_BASIC_USERNAME` | `admin` | Knife4j Basic 认证用户名 |
| `KNIFE4J_BASIC_PASSWORD` | （空） | Knife4j Basic 认证密码，启用时必须设置 |

> **注意**：`knife4j.enable` 本身必须保持 `false`（已在代码中硬编码），设为 `true` 会导致启动异常。以上变量仅控制 Basic 认证保护，不影响文档本身是否可用。

### 4.8 配置中心数据库与管理服务

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONFIG_DB_HOST` | `mysql` | 配置中心数据库主机（Docker 网络内服务名） |
| `CONFIG_DB_USERNAME` | `root` | 配置中心数据库用户名 |
| `CONFIG_DB_PASSWORD` | `CHANGE_ME_MYSQL_PASSWORD` | 配置中心数据库密码（通常与 `MYSQL_ROOT_PASSWORD` 一致） |
| `ADMIN_SERVICE_BASE_URL` | `http://admin-service:18088` | Admin 管理服务内部地址 |
| `ADMIN_SERVICE_PORT` | `18088` | Admin 管理服务端口 |
| `IMAGE_PREFIX` | （空） | 镜像前缀。本地构建留空；生产环境设为 registry 前缀（如 `registry.cn-shenzhen.aliyuncs.com/zxyz/`），必须以 `/` 结尾 |

### 4.9 告警投递（Alertmanager）

监控栈已内置 Prometheus 告警规则（`deploy/prometheus/rules/zxyz.yml`：实例不可达 / JVM 堆过高 / 进程重启 / 5xx 错误率），但默认 `receivers` 为无 `*_configs` 的占位 receiver，**告警不会真正送达**。通过 `.env` 配置投递渠道即可启用，二者至少配置其一：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ALERT_WEBHOOK_URL` | （空） | 通用 Webhook 地址，直接接收 Alertmanager JSON 的端点。企业微信/钉钉自定义机器人需经适配网关转发。非空即启用 `webhook_configs` |
| `ALERT_EMAIL_TO` | （空） | 邮件告警收件人。需 `EMAIL_ENABLED=true` 且 `EMAIL_*` SMTP 已配置，非空即启用 `email_configs`（复用 4.4 邮件配置） |

- **渲染机制**：部署时由 `scripts/render-alertmanager.sh` 读取 `.env`，把 `deploy/alertmanager/alertmanager.yml.tmpl` 渲染为 `deploy/alertmanager/alertmanager.yml`（容器挂载点）。该脚本在 CI 的 `deploy` 作业中于 `docker compose up` 前自动执行（`$REPO_DIR` 每次部署都会 `git pull`，渲染逻辑始终为最新）；本地手动部署可运行 `bash scripts/render-alertmanager.sh` 自行渲染。
- **优雅降级**：两个渠道变量皆为空时，渲染为「静默丢弃」的合法 no-op receiver，Alertmanager 仍正常启动，**监控不中断**。
- **不触碰密钥**：渲染结果写到部署目录（`$DEPLOY_DIR/deploy/...`），不写入 git 工作区，故不会触发仓库漂移检测；webhook 地址/密码只来自 `.env`，不进仓库。

---

## 5. 数据库初始化说明

### 5.1 初始化机制

MySQL 容器首次启动时（数据目录为空），Docker 入口脚本会自动执行 `/docker-entrypoint-initdb.d/` 目录下的脚本。项目将初始化脚本 `sql/00-init-zxyz.sh` 挂载到该目录。

### 5.2 初始化流程

`00-init-zxyz.sh` 执行以下操作：

1. **创建 10 个数据库**（9 个 `zxyz_*` 业务库 + `nacos`）：`zxyz_project`、`zxyz_im`、`zxyz_email`、`zxyz_share`、`zxyz_file`、`zxyz_team`、`zxyz_user`、`zxyz_audit`、`zxyz_config`、`nacos`

表结构由各服务的 Flyway 迁移脚本在运行时自动管理（见 `docs/claude-infra.md`）。

### 5.3 重新初始化

如果需要重建数据库，删除数据目录（会丢失所有数据）：

```bash
docker compose down
rm -rf data/mysql    # 绑定挂载目录
docker compose up -d
```

---

## 6. Dockerfile 解析

### 6.1 后端通用 Dockerfile

文件路径：`ZXYZdatabaseBack/Dockerfile`

该 Dockerfile 适用于全部 8 个后端服务，通过 `MODULE` 构建参数选择打包哪个 Maven 子模块。

**构建阶段**（`maven:3.9-eclipse-temurin-17`）：

1. **依赖缓存优化**：先复制所有模块的 `pom.xml`，执行 `dependency:go-offline` 预下载依赖。后续仅当 `pom.xml` 变化时才会重新下载依赖，利用 Docker layer 缓存。
2. **源码编译**：复制完整源码后执行 `mvn package`，使用 `-Dmaven.test.skip=true` 跳过测试（测试源码存在编译问题，不影响生产）。
3. **提取产物**：从 `target/` 目录中找到可执行 JAR（排除 `original-` 前缀的原始包），复制为 `/tmp/app.jar`。

**运行阶段**（`eclipse-temurin:17-jre`）：

1. 安装 `curl`（供健康检查使用）
2. 设置环境变量：`SPRING_PROFILES_ACTIVE=prod`、`JAVA_OPTS`（75% 内存限制、UTF-8 编码）、时区 `Asia/Shanghai`
3. 复制构建产物为 `/app/app.jar`
4. 通过 `ENTRYPOINT` 启动 Spring Boot 应用

**使用示例**（docker-compose.yml 中的配置）：

```yaml
build:
  context: .                    # 构建上下文为项目根目录
  dockerfile: ZXYZdatabaseBack/Dockerfile
  args:
    MODULE: zxyz-project-service   # 通过此参数选择模块
```

### 6.2 前端 Dockerfile

文件路径：`ZXYZdatabaseFront/Dockerfile`

**构建阶段**（`node:22-alpine`）：

1. 复制 `package.json` 和 `package-lock.json`，执行 `npm ci` 安装依赖
2. 复制前端源码，执行 `npm run build`（Vite 构建）生成 `dist/` 目录

**运行阶段**（`nginx:1.27-alpine`）：

1. 复制自定义 Nginx 配置 `deploy/nginx/default.conf` 到 `/etc/nginx/conf.d/`
2. 复制构建产物到 `/usr/share/nginx/html/`
3. 对外暴露 80 端口

---

## 7. docker-compose.yml 详解

### 7.1 启动顺序与健康检查

通过 `depends_on` + `condition: service_healthy` 控制启动顺序。**所有业务服务只依赖 4 个中间件，不互相依赖**，Nacos 服务发现 + Resilience4j 重试处理运行时调用。

**启动层次**：

```
第 1 层：mysql, redis, rabbitmq, nacos（中间件层，mysql 健康后 nacos 启动）
第 2 层：所有 8 个业务服务 + gateway（并行启动，依赖第 1 层全部 healthy）
第 3 层：frontend-nginx（依赖 gateway healthy）
```

**健康检查配置**：

| 服务 | 健康检查方式 | 检查间隔 | 超时 | 重试次数 | 启动等待 |
|---|---|---|---|---|---|
| mysql | `mysqladmin ping` | 10s | 5s | 20 | 30s |
| nacos | `curl /nacos/v1/console/health/readiness` | 15s | 5s | 10 | 60s |
| redis | `redis-cli ping` | 10s | 5s | 20 | - |
| rabbitmq | `rabbitmq-diagnostics ping` | 10s | 5s | 20 | - |
| 业务服务 | `curl /actuator/health` | 15s | 5s | 8 | 90s |
| gateway | `curl /actuator/health` | 15s | 5s | 6 | 60s |
| frontend-nginx | `curl http://localhost:80/` | 15s | 5s | 6 | 10s |

Java 服务设置 `start_period: 90s`，在启动后的 90 秒内不计入失败重试，给予充分的 JVM 初始化 + Flyway 迁移时间。

### 7.2 基础设施服务

**MySQL**：
- 使用 `mysql:8.4.0-oraclelinux8` 镜像
- 字符集 `utf8mb4`，排序规则 `utf8mb4_unicode_ci`，时区 `+08:00`
- 数据持久化到 `${DATA_DIR}/mysql` 目录
- 初始化脚本通过只读挂载

**Nacos**：
- 单机模式（`MODE: standalone`）
- 使用 MySQL 作为配置存储（共享 MySQL 实例，`nacos` 数据库）
- JVM 堆内存限制 512MB

**Redis**：
- 使用 Alpine 版镜像，开启 AOF 持久化
- 通过命令行参数设置密码

**RabbitMQ**：
- 带管理插件的 Alpine 版镜像
- 管理界面端口映射为 `${RABBITMQ_MGMT_PORT:-15672}`

### 7.3 业务服务配置模式

每个业务服务的配置遵循相同模式：

- **镜像构建**：使用后端通用 Dockerfile，通过 `MODULE` 参数区分
- **重启策略**：`unless-stopped`，`stop_grace_period: 30s`（优雅停机）
- **Spring Profile**：`prod`
- **依赖**：仅依赖 mysql、redis、rabbitmq、nacos（不依赖其他业务服务）
- **数据库连接**：各自独立的 MySQL 数据库（`zxyz_project`、`zxyz_im` 等）
- **Redis 连接**：共享同一个 Redis 实例，通过 `REDIS_DATABASE` 隔离
- **服务间调用**：运行时通过 Nacos 服务发现 + Resilience4j 重试（3 次，500ms 间隔）
- **Nacos 注册**：所有服务注册到同一个 Nacos 实例

### 7.4 Gateway 配置

Gateway 是所有外部请求的入口：
- 端口 18000（内部，不对外暴露）
- **仅依赖 nacos**（不依赖任何业务服务），通过 Nacos 服务发现路由
- WebSocket 路由使用环境变量 `IM_WEBSOCKET_URI` 直接指定地址
- `MAX_REQUEST_SIZE: 512MB`（控制请求体大小上限，支持大文件数据库导入）

### 7.5 前端 Nginx

- 唯一对外暴露端口的容器（`${HTTP_PORT:-80}:80`）
- 依赖 Gateway 健康后才启动
- 反向代理所有 API 和 WebSocket 请求到 Gateway

---

## 8. Nginx 反向代理配置说明

配置文件路径：`deploy/nginx/default.conf`

### 8.1 限流配置

```nginx
# API 限流：每个 IP 每秒 10 个请求
limit_req_zone $binary_remote_addr zone=api_per_ip:10m rate=10r/s;

# 登录/注册限流：每个 IP 每分钟 1 个请求
limit_req_zone $binary_remote_addr zone=auth_per_ip:10m rate=10r/m;
```

### 8.2 路由规则

| 路径 | 目标 | 说明 |
|---|---|---|
| `/api/users/login` | `gateway:18000` | 登录接口，严格限流（1r/m，突发 5） |
| `/api/users/register` | `gateway:18000` | 注册接口，严格限流 |
| `/api/` | `gateway:18000` | 所有 API 请求，标准限流（10r/s，突发 50） |
| `/im-api` | `gateway:18000` | IM HTTP API |
| `/ws` | `gateway:18000/ws` | WebSocket，超时 3600 秒 |
| `/` | 本地静态文件 | Vue history 模式兜底到 `index.html` |

### 8.3 安全头

Nginx 默认添加以下安全响应头：

- `X-Frame-Options: DENY` — 禁止 iframe 嵌入
- `X-Content-Type-Options: nosniff` — 禁止 MIME 嗅探
- `Referrer-Policy: strict-origin-when-cross-origin` — 严格来源策略
- `Content-Security-Policy` — 限制资源加载来源

### 8.4 请求体限制

`client_max_body_size 512m` — 允许最大 512 MB 的请求体，用于文件上传和数据库导入。

### 8.5 TLS 终止（容器内 nginx，opt-in）

默认部署仍是**纯 HTTP（IP 直连 80）**，本小节为可选项：让前端容器内的 nginx 直接监听 `443` 并终止 TLS，证书以挂载方式注入。**未启用时，默认 HTTP 路径与今天完全一致，不受影响。**

实现要点：

- HTTP 与 HTTPS 两套配置共用同一个 shared snippet（`deploy/nginx/snippets/proxy-locations.conf` 与 `http-common.conf`），通过 `include` 引入，避免两套 location/限流配置漂移。
- 启用时 `docker-compose.tls.yml` 覆盖挂载 `deploy/nginx/default-ssl.conf` 到 `/etc/nginx/conf.d/default.conf`，并把 `./deploy/nginx/certs` 只读挂载到 `/etc/nginx/certs`；`entrypoint.sh` 据此把 `default-ssl.conf` 就地 `envsubst`（解析 `${OSS_PUBLIC_BASE_URL}`）。
- `default-ssl.conf` 包含 **443（ssl）+ 80→443 重定向** 两个 server 块，均携带与 HTTP 一致的安全响应头（X-Frame-Options / X-Content-Type-Options / Referrer-Policy / CSP）。

#### 步骤 1：获取证书

申请与你的域名匹配的证书（任选其一）：

- **Let's Encrypt / certbot**：`certbot certonly --webroot -w /path/to/webroot -d your.domain.com`，证书位于 `/etc/letsencrypt/live/your.domain.com/`。
- **云厂商（阿里云/腾讯云等）** 或 **自有 CA** 签发。

把证书链与私钥放到**部署目录**的 `deploy/nginx/certs/`（即 `$DEPLOY_DIR/deploy/nginx/certs/`，`docker-compose.tls.yml` 相对当前目录挂载）——本地开发则在仓库根 `deploy/nginx/certs/`。**必须使用以下文件名**（与 `default-ssl.conf` 中的路径对应）：

| 文件 | 含义 |
|---|---|
| `fullchain.pem` | 证书链（含中间证书） |
| `privkey.pem`   | 私钥 |

> ⚠️ 证书/私钥**严禁提交进 git**：已在 `.gitignore` 中忽略 `deploy/nginx/certs/*.pem` `*.key` 等，仅保留 `.gitkeep` 占位。

#### 步骤 2：修改 `.env`

```dotenv
TLS_ENABLED=true            # 启用容器内 TLS（默认 false；false 时保持 HTTP-only）
AUTH_COOKIE_SECURE=true     # 配合 HTTPS 下发 Secure Cookie（见下方重要提醒）
```

> ⚠️ **重要**：`AUTH_COOKIE_SECURE` **在纯 HTTP（IP 直连）时必须保持 `false`**。若在没有 HTTPS 的情况下置 `true`，浏览器不会回传 Secure Cookie，导致登录失效。只有在真正启用 TLS（即 `TLS_ENABLED=true` 且证书已挂载）时才改为 `true`。`validate-env` 会校验二者一致性。

#### 步骤 3：启动（叠加 overlay）

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d
```

该命令在默认编排之上叠加 TLS 覆盖层：暴露 `443`（`${HTTPS_PORT:-443}:443`）并将 80 仅用于 301 重定向到 443；**其它服务/配置一律不变**。访问 `https://your.domain.com` 即可；访问 `http://your.domain.com` 会被自动跳转至 HTTPS。

#### 证书轮换

直接替换 `deploy/nginx/certs/` 下的 `fullchain.pem` / `privkey.pem` 后，重载 nginx：

```bash
docker compose exec frontend-nginx nginx -s reload
```

---

## 9. 常见问题排查

### 9.1 容器启动失败

**症状**：`docker compose ps` 显示容器状态为 `unhealthy` 或反复重启。

**排查步骤**：

```bash
# 查看容器日志
docker compose logs <服务名>

# 查看最近 50 行日志
docker compose logs --tail=50 <服务名>

# 进入容器排查
docker compose exec <服务名> sh
```

### 9.2 MySQL 启动慢导致下游服务超时

**症状**：业务服务日志显示数据库连接失败。

**原因**：MySQL 首次启动需要初始化数据目录和执行 schema 导入，耗时较长。

**解决**：健康检查配置了 `retries: 20`（最长等待约 200 秒），请耐心等待。如果仍然超时，检查服务器磁盘 IO 和内存。

### 9.3 Java 服务 OOM

**症状**：容器被 OOM Killer 终止。

**解决**：调整 `JAVA_OPTS` 中的 `-XX:MaxRAMPercentage`，或增加 Docker 容器内存限制：

```yaml
# 在 docker-compose.yml 对应服务中添加
deploy:
  resources:
    limits:
      memory: 1G
```

### 9.4 端口冲突

**症状**：启动时报错 `Bind for 0.0.0.0:80 failed: port is already allocated`。

**解决**：修改 `.env` 中的 `HTTP_PORT` 为其他端口（如 `8080`），或停止占用该端口的服务：

```bash
# Linux 查看端口占用
sudo lsof -i :80
# 或
sudo ss -tlnp | grep :80
```

### 9.5 前端能访问但 API 404

**症状**：页面加载正常但请求接口返回 404。

**排查**：
1. 确认 Gateway 容器健康：`docker compose ps gateway`
2. 确认 Nacos 控制台（`http://服务器IP:8848/nacos`）中各服务已注册
3. 检查 Nginx 日志：`docker compose logs frontend-nginx`

### 9.6 WebSocket 连接失败

**症状**：IM 功能无法使用，浏览器控制台显示 WebSocket 连接错误。

**排查**：
1. 确认 im-service 健康：`docker compose ps im-service`
2. 检查 im-service 日志中 Netty 是否启动在 19090 端口
3. 确认 Nginx 配置中 `/ws` location 正确代理到 Gateway

### 9.7 数据库初始化失败

**症状**：业务服务启动报 SQL 异常，表不存在。

**解决**：检查 `docker compose logs mysql` 中的初始化日志。如果初始化已执行但 schema 需要更新，可手动执行：

```bash
# 进入 MySQL 容器
docker compose exec mysql mysql -uroot -p

# 手动重建时，可重新执行初始化脚本
docker compose exec mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" < /docker-entrypoint-initdb.d/00-init-zxyz.sh
```

### 9.8 admin-service V2 迁移校验不匹配（Flyway Checksum Mismatch）

**症状**：admin-service 启动失败，日志显示 `Validate failed: Migration checksum mismatch` 针对 `V2__hot_config_keys.sql`。

**原因**：V2 迁移文件在发布后被修改（commit `9883823` 新增了 `app.email.verify-code-cooldown-seconds` 配置键），导致已运行原始 V2 的数据库出现校验不匹配。Flyway 检测到 V2 校验不匹配后会拒绝执行所有后续迁移（包括 V3 修复脚本），导致新配置键无法写入数据库。

**解决**：

```bash
# 方法一：使用 deploy-fast.sh 自动修复（推荐）
cd /www/zxyz
./scripts/deploy-fast.sh --repair-flyway admin-service

# 方法二：手动执行 flyway repair
docker run --rm \
  --network zxyz-net \
  -e "FLYWAY_URL=jdbc:mysql://mysql:3306/zxyz_config?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  -e "FLYWAY_USER=root" \
  -e "FLYWAY_PASSWORD=$(grep MYSQL_ROOT_PASSWORD .env | cut -d'=' -f2-)" \
  flyway/flyway:10.12 repair
```

修复后重启 admin-service，V3 迁移将正常执行，缺失的配置键会被自动创建。

> **注意**：此修复是一次性操作，每个部署环境只需执行一次。新数据库不受影响（V1→V2→V3 按序执行，数据正确）。

---

## 10. 生产环境部署建议

### 10.1 安全加固

1. **密码强度**：所有 `CHANGE_ME` 变量必须替换为高强度密码（至少 16 位，包含大小写字母、数字和特殊字符）
2. **端口暴露**：仅对外暴露必要的 `HTTP_PORT`，其余端口（MySQL、Redis、RabbitMQ、Nacos）不应映射到宿主机
3. **CORS 配置**：将 `CORS_ALLOWED_ORIGINS` 从 `*` 改为实际域名
4. **防火墙**：仅开放 80/443 端口
5. **HTTPS**：在前端 Nginx 前再加一层反向代理（如宿主机 Nginx 或 Caddy）处理 TLS

### 10.2 使用宿主机 Nginx 反代（推荐）

如果宿主机已安装 Nginx，建议将 `HTTP_PORT` 改为非 80 端口（如 `8080`），由宿主机 Nginx 处理 HTTPS 和域名绑定：

```nginx
# 宿主机 Nginx 配置示例
server {
    listen 443 ssl;
    server_name yourdomain.com;

    ssl_certificate     /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8080/ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

### 10.3 资源限制（已配置）

`docker-compose.yml` 中已为所有服务配置了 `deploy.resources.limits`，关键服务还配置了 `reservations`（最低资源保障）：

| 服务 | limits memory | reservations memory | reservations cpus |
|------|-------------|-------------------|------------------|
| mysql | 1G | 512M | 0.5 |
| nacos | 512M | 256M | 0.25 |
| redis | 256M | 128M | 0.25 |
| gateway | 512M | 256M | 0.25 |
| 业务服务 | 256-512M | 无 | 无 |

### 10.4 日志管理（已配置）

所有容器已配置 JSON-file 日志驱动，限制单文件大小和保留份数：

| 服务 | max-size | max-file |
|------|----------|----------|
| mysql | 100m | 3 |
| redis | 50m | 3 |
| nacos | 100m | 3 |
| rabbitmq | 50m | 3 |
| 业务服务 | 100m | 3 |
| gateway | 100m | 3 |
| frontend-nginx | 50m | 3 |

可额外配置宿主机 logrotate：

```bash
# /etc/logrotate.d/docker-containers
/var/lib/docker/containers/*/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
```

### 10.5 数据备份

项目提供 `scripts/backup.sh` 脚本，支持 MySQL + Redis 自动备份：

```bash
# 手动执行
./scripts/backup.sh

# 设置每日凌晨 3 点自动备份
crontab -e
# 添加：0 3 * * * /path/to/zxyz/scripts/backup.sh >> /var/log/zxyz-backup.log 2>&1
```

备份内容、保留策略等详见 [九、备份与恢复](#九备份与恢复)。

---

## 11. 维护与更新操作

### 11.1 更新代码并重新部署

```bash
# 拉取最新代码
git pull

# 重新构建变更的服务并重启
docker compose up -d --build

# 仅重建特定服务（如只改了 project-service）
docker compose up -d --build project-service
```

> **注意**：如果更新涉及 admin-service 且 V2 迁移文件被修改，需先执行 `--repair-flyway` 修复 Flyway 校验不匹配：
>
> ```bash
> ./scripts/deploy-fast.sh --repair-flyway admin-service
> ```
>
> 详见 [9.8 admin-service V2 迁移校验不匹配](#98-admin-service-v2-迁移校验不匹配flyway-checksum-mismatch)。

### 11.2 查看服务日志

```bash
# 实时跟踪所有服务
docker compose logs -f

# 查看特定服务最近 100 行
docker compose logs --tail=100 gateway

# 查看特定时间段日志
docker compose logs --since="2026-05-24T10:00:00" im-service
```

### 11.3 重启单个服务

```bash
# 重启服务（不重新构建）
docker compose restart project-service

# 重建并重启
docker compose up -d --build project-service
```

### 11.4 清理构建缓存

```bash
# 清理悬空镜像
docker image prune

# 清理未使用的镜像、容器、网络
docker system prune

# 清理所有构建缓存（慎用）
docker builder prune -a
```

### 11.5 镜像标签管理

更新发布时，建议在 `.env` 中使用日期或版本号作为镜像标签：

```bash
APP_IMAGE_TAG=20260524
```

这样可以保留旧版本镜像，便于回滚：

```bash
# 一键回滚到上一版本（推荐，依赖 .env.previous）
./scripts/rollback.sh

# 手动指定 tag 回滚（无 .env.previous 时的备选方案）
APP_IMAGE_TAG=20260523 docker compose up -d
```

### 11.6 一键回滚（scripts/rollback.sh）

项目提供自动化回滚脚本 `scripts/rollback.sh`，依赖 CI/CD 部署时自动生成的 `.env.previous` 文件记录上一版本镜像 tag。

**用法**：

```bash
# 回滚所有服务到上一个版本
./scripts/rollback.sh

# 跳过镜像拉取（本地已有旧镜像时加速）
./scripts/rollback.sh --no-pull

# 仅回滚指定服务
./scripts/rollback.sh gateway project-service

# 验证 .env.previous 是否存在（不执行回滚）
./scripts/rollback.sh --validate
```

**前置条件**：
- 服务器上存在 `$DEPLOY_DIR/.env.previous`（由 CI/CD 部署流程自动生成）
- `DEPLOY_DIR` 默认为 `/www/zxyz`，可通过环境变量覆盖
- 若 `.env.previous` 不存在，脚本会报错退出并提示

**回滚流程**：
1. 读取 `.env.previous` 中的 `APP_IMAGE_TAG`
2. 更新当前 `.env` 的镜像 tag 为上一版本
3. 拉取对应版本镜像（`--no-pull` 可跳过）
4. 重启相关容器并等待 running 状态（最长 60 秒）

**默认回滚的服务**（未指定服务名时）：
`project-service` `im-service` `email-service` `user-service` `share-service` `file-service` `team-service` `audit-service` `admin-service` `gateway` `frontend-nginx`

### 11.7 Nacos 配置管理

Nacos 控制台地址：`http://127.0.0.1:${NACOS_CONSOLE_PORT:-8080}/next/`（Nacos 3.x React UI，仅本机可访问，远程需 SSH 隧道）

默认凭据：用户名 `nacos`，密码为 `.env` 中的 `NACOS_PASSWORD`。

各服务的运行时配置可通过 Nacos 控制台动态调整（需应用支持 Nacos 配置热更新）。

**配置导入**：

项目所有 Nacos 配置模板存放在 `nacos-config/` 目录，首次部署或配置变更后使用导入脚本批量推送：

```bash
cd nacos-config

# 导入所有配置到默认命名空间（public）
./import.sh "" localhost:8848 nacos <NACOS_PASSWORD>

# 导入到指定命名空间
./import.sh <namespace-id> localhost:8848 nacos <NACOS_PASSWORD>
```

配置文件清单（group=`ZXYZ`）：

| dataId | 用途 |
|---|---|
| `zxyz-static.yml` | 共享静态配置（连接池、Redis、Sa-Token、服务间地址、Resilience4j） |
| `zxyz-dynamic.yml` | 共享动态配置（CORS、认证 Cookie/Token 超时、验证码冷却） |
| `zxyz-gateway.yml` | Gateway 路由规则 |
| `zxyz-user-service.yml` | 用户服务专属配置 |
| 其他 `zxyz-*.yml` | 各服务专属配置 |

> **注意**：配置中的敏感值支持 `ENC(...)` 格式（Jasypt 加密），启动时通过 `JASYPT_PASSWORD` 环境变量自动解密。详见 `docs/jasypt-key-management.md`。

远程访问示例：

```bash
ssh -L 8080:localhost:8080 user@your-server
# 本地浏览器访问 http://localhost:8080/next/
```

---

## 附录 A：优雅停机

所有后端服务已配置优雅停机（`application-common.yml`）：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

`docker-compose.yml` 中为所有后端服务配置了 `stop_grace_period: 30s`。

**效果**：`docker compose restart` 或 `docker compose down` 时，Spring Boot 会先停止接收新请求，等待正在处理的请求完成（最多 20s），然后才关闭 JVM。Docker 层面额外等待 30s 后才发送 SIGKILL。

---

## 附录 B：服务器迁移

### 打包数据

```bash
tar czf zxyz-data.tar.gz \
  data/ backups/ .env sql/ \
  docker-compose.yml \
  ZXYZdatabaseBack/Dockerfile \
  ZXYZdatabaseFront/Dockerfile \
  deploy/ scripts/
```

### 目标服务器恢复

```bash
# 1. 安装 Docker + Docker Compose
# 2. 解压数据
tar xzf zxyz-data.tar.gz

# 3. 构建并启动
docker compose up -d --build

# 4. 验证
docker compose ps
```

---

## 附录 C：备份与恢复

### 自动备份

```bash
# 手动执行
./scripts/backup.sh

# 定时任务（每日凌晨 3 点）
crontab -e
# 添加：0 3 * * * /path/to/zxyz/scripts/backup.sh >> /var/log/zxyz-backup.log 2>&1
```

### 恢复 MySQL

```bash
# 1. 停止业务服务
docker compose stop project-service im-service email-service \
  share-service file-service team-service audit-service user-service

# 2. 恢复
gunzip -c backups/mysql_20260608_030000.sql.gz | \
  docker exec -i zxyz-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"

# 3. 重启
docker compose start
```

### 恢复 Redis

```bash
# 1. 停止所有服务
docker compose stop

# 2. 替换 RDB 文件
cp backups/redis_20260608_030000.rdb data/redis/dump.rdb

# 3. 启动
docker compose start
```

---

## 12. 阿里云 ACR 镜像仓库配置

### 12.1 注册阿里云容器镜像服务

1. 登录 [阿里云容器镜像服务控制台](https://cr.console.aliyun.com)
2. 创建命名空间（如 `zxyz`）
3. 在命名空间下创建以下仓库：
   - `zxyz-project-service`
   - `zxyz-im-service`
   - `zxyz-email-service`
   - `zxyz-user-service`
   - `zxyz-share-service`
   - `zxyz-file-service`
   - `zxyz-team-service`
   - `zxyz-audit-service`
   - `zxyz-gateway`
   - `zxyz-frontend-nginx`
4. 在仓库的「镜像加速器」或「访问凭证」中获取用户名和密码

### 12.2 GitHub Secrets 配置

在 GitHub 仓库 Settings → Secrets and variables → Actions 中添加：

| Secret 名称 | 说明 |
|---|---|
| `ACR_USERNAME` | 阿里云 ACR 用户名（通常是阿里云账号名或 RAM 子账号） |
| `ACR_PASSWORD` | 阿里云 ACR 密码或 AccessKey |

### 12.3 CI/CD 配置变更

修改 `.github/workflows/ci-cd.yml`：

1. 镜像前缀改为 ACR 地址：
```yaml
env:
  IMAGE_PREFIX: registry.cn-shenzhen.aliyuncs.com/zxyz/
```

2. 登录步骤改为 ACR：
```yaml
- name: Login to ACR
  uses: docker/login-action@v3
  with:
    registry: registry.cn-shenzhen.aliyuncs.com
    username: ${{ secrets.ACR_USERNAME }}
    password: ${{ secrets.ACR_PASSWORD }}
```

3. 镜像标签改为 ACR 地址：
```yaml
tags: |
  registry.cn-shenzhen.aliyuncs.com/zxyz/${{ matrix.name }}:${{ tag }}
```

### 12.4 服务器配置

服务器 `/www/zxyz/.env` 更新：

```bash
IMAGE_PREFIX=registry.cn-shenzhen.aliyuncs.com/zxyz/
```

服务器 Docker 登录 ACR：

```bash
docker login registry.cn-shenzhen.aliyuncs.com -u <ACR_USERNAME> -p <ACR_PASSWORD>
```

若服务器在国内网络，建议配置 Docker 镜像加速器（`/etc/docker/daemon.json`）：

```json
{
  "registry-mirrors": [
    "https://registry.cn-shenzhen.aliyuncs.com"
  ]
}
```

修改后重启 Docker：

```bash
sudo systemctl restart docker
```

### 12.5 快速切换脚本

使用 `scripts/setup-acr.sh` 一键切换 CI/CD 和本地配置到 ACR：

```bash
# 切换镜像源到 ACR
./scripts/setup-acr.sh enable

# 切换回 GHCR
./scripts/setup-acr.sh disable
```
