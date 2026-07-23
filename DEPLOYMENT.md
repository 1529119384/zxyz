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
| `DATABASE_MAINTENANCE_ENABLED` | `false` | 是否启用数据库导入功能（生产环境保持 false） |
| `TIME_ASPECT_ENABLED` | `false` | 是否启用性能切面日志（生产环境保持 false） |

### 4.6 Nacos 注册中心

| 变量 | 默认值 | 说明 |
|---|---|---|
| `NACOS_PORT` | `8848` | Nacos 控制台端口 |
| `NACOS_PASSWORD` | `nacos` | Nacos 登录密码 |
| `NACOS_AUTH_TOKEN` | 预置值 | Nacos JWT 签名密钥（Base64 编码，解码后 >= 32 字节） |
| `NACOS_AUTH_IDENTITY_KEY` | `serverIdentity` | Nacos 身份验证 Key |
| `NACOS_AUTH_IDENTITY_VALUE` | `security` | Nacos 身份验证 Value |
| `NACOS_NAMESPACE` | （空） | Nacos 命名空间 ID，多环境隔离时可设置为对应环境的命名空间 UUID |
| `NACOS_GROUP` | `DEFAULT_GROUP` | Nacos 服务分组，多环境隔离时可改为 `dev`、`staging` 等 |

### 4.7 Knife4j API 文档

| 变量 | 默认值 | 说明 |
|---|---|---|
| `KNIFE4J_BASIC_ENABLE` | `false` | 是否启用 Knife4j Basic 认证保护，生产环境建议开启 |
| `KNIFE4J_BASIC_USERNAME` | `admin` | Knife4j Basic 认证用户名 |
| `KNIFE4J_BASIC_PASSWORD` | （空） | Knife4j Basic 认证密码，启用时必须设置 |

> **注意**：`knife4j.enable` 本身必须保持 `false`（已在代码中硬编码），设为 `true` 会导致启动异常。以上变量仅控制 Basic 认证保护，不影响文档本身是否可用。

---

## 5. 数据库初始化说明

### 5.1 初始化机制

MySQL 容器首次启动时（数据目录为空），Docker 入口脚本会自动执行 `/docker-entrypoint-initdb.d/` 目录下的脚本。项目将初始化脚本 `sql/00-init-zxyz.sh` 挂载到该目录。

### 5.2 初始化流程

`00-init-zxyz.sh` 执行以下操作：

1. **创建 8 个数据库**：`zxyz_project`、`zxyz_im`、`zxyz_email`、`zxyz_share`、`zxyz_file`、`zxyz_team`、`zxyz_user`、`nacos`
2. **导入各库 Schema**：依次执行 8 个 `schema_*.sql` 文件

SQL Schema 文件通过只读挂载映射到容器内的 `/docker-entrypoint-sql/` 目录，不会被 MySQL 入口脚本自动重复执行。

### 5.3 Schema 文件列表

| 文件 | 目标数据库 |
|---|---|
| `sql/schema_project.sql` | `zxyz_project` |
| `sql/schema_im.sql` | `zxyz_im` |
| `sql/schema_email.sql` | `zxyz_email` |
| `sql/schema_share.sql` | `zxyz_share` |
| `sql/schema_file.sql` | `zxyz_file` |
| `sql/schema_team.sql` | `zxyz_team` |
| `sql/schema_user.sql` | `zxyz_user` |
| `sql/schema_nacos.sql` | `nacos` |

### 5.4 重新初始化

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

`client_max_body_size 1024m` — 允许最大 1 GB 的请求体，用于文件上传和数据库导入。

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

# 手动执行 SQL 文件
docker compose exec mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" < /docker-entrypoint-sql/schema_xxx.sql
```

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
# 回滚到旧版本
APP_IMAGE_TAG=20260523 docker compose up -d
```

### 11.6 Nacos 配置管理

Nacos 控制台地址：`http://127.0.0.1:${NACOS_PORT:-8848}/nacos`（仅本机可访问，远程需 SSH 隧道）

默认凭据：用户名 `nacos`，密码为 `.env` 中的 `NACOS_PASSWORD`。

各服务的运行时配置可通过 Nacos 控制台动态调整（需应用支持 Nacos 配置热更新）。

远程访问示例：

```bash
ssh -L 8848:localhost:8848 user@your-server
# 本地浏览器访问 http://localhost:8848/nacos
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
