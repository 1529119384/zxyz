# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

指绣云章 (ZXYZ) — 云端文件管理平台（团队协作 + IM）。详细文档见 `docs/architecture.md`、`docs/infrastructure.md`。

## Git

### 仓库架构：根仓库为唯一提交与 CI 真相源

本项目采用 **monorepo + 嵌套独立仓库** 模式（非 git submodule）：

| 仓库 | 远程 | 分支 | 角色 |
|---|---|---|---|
| `zxyz/`（根） | `github.com/1529119384/zxyz.git` | dev | **唯一 CI/CD 真相源**：直接跟踪所有文件（含子目录源码），`.github/workflows/ci-cd.yml` 仅由根仓库 push 触发 |
| `ZXYZdatabaseBack/` | `github.com/1529119384/ZXYZdatabaseBack.git` | dev | 后端子仓库：独立开发历史，不触发 CI |
| `ZXYZdatabaseFront/` | `github.com/1529119384/ZXYZdatabaseFront.git` | main | 前端子仓库：独立开发历史，不触发 CI |

根仓库通过 `git ls-files` 直接管理 `ZXYZdatabaseBack/**` 和 `ZXYZdatabaseFront/**` 下的源码文件。子目录内的 `.git/` 被根 `.gitignore` 排除（`ZXYZdatabaseBack/.git/`、`ZXYZdatabaseFront/.git/`），两个仓库树互不嵌套引用。

### CI 触发面（与 `.github/workflows/ci-cd.yml` paths-filter 一致）

根仓库 `on.push` / `on.pull_request` 的 paths 白名单：

```
ZXYZdatabaseBack/**
ZXYZdatabaseFront/**
deploy/**
docker-compose.yml
.env.example
.github/workflows/**
```

不在此白名单内的文件变更（如 `CLAUDE.md`、`docs/**`、`nacos-config/**`、`scripts/**`、`sql/**`）**不触发** workflow。`dorny/paths-filter` 进一步按服务目录判断哪些镜像需要重建。

### 提交规范与同步顺序

WHEN 修改后端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseBack && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseBack/ && git commit` — **根仓库为 CI 真相源，遗漏此步则不触发构建**

WHEN 修改前端代码, DO 按以下顺序双提交：
1. `cd ZXYZdatabaseFront && git add -A && git commit` — 子仓库保留开发历史
2. `cd <根目录> && git add ZXYZdatabaseFront/ && git commit` — 根仓库触发 CI

WHEN 修改根目录文件（`docker-compose.yml`、`deploy/`、`.env.example`、`.github/`）, DO 仅在根目录提交（无需同步子仓库）。

WHEN 执行 git 操作, DO cd 到对应目录再执行，避免跨仓库误操作。

### 同步遗漏检查

提交后执行以下检查确认根仓库与子仓库一致：

```bash
# 在根目录执行：检查根仓库是否有未同步的子仓库变更
git status ZXYZdatabaseBack/ ZXYZdatabaseFront/

# 对比子仓库 HEAD 与根仓库跟踪内容（无输出 = 一致）
cd ZXYZdatabaseBack && git diff HEAD --stat && cd ..
cd ZXYZdatabaseFront && git diff HEAD --stat && cd ..
```

若 `git status` 显示子目录有 `modified`/`new file` 未暂存，说明子仓库已提交但根仓库遗漏同步，需补提交到根仓库。

**pre-push 自动校验**: 根仓库 `.husky/pre-push` 在每次 `git push` 前自动运行 `scripts/check-subrepo-sync.sh`（安装钩子：`scripts/install-hooks.sh`；跳过校验：`git push --no-verify`）。若双提交漏掉根仓库这一步，push 会被钩子拦截并提示补提交——不要用 `--force` 绕过，先补提交。

## Build & Test Commands

### Backend (run in `ZXYZdatabaseBack/`)

```bash
mvn clean -DskipTests compile                   # baseline compile check
mvn test                                         # all tests (~83 test classes)
mvn test -pl zxyz-team-service                   # single module tests
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # single test class
mvn clean package -DskipTests                    # package for Docker build
mvn -pl zxyz-project-service spring-boot:run     # run single service
```

**本地热调试（spring-boot-devtools，父 pom 已统一引入）**：
- 前置：IDEA Settings→Compiler 勾选 **Build project automatically** + Registry（Ctrl+Shift+Alt+/）勾选 `compiler.automake.allow.when.app.running`
- **必须 Debug 模式启动**才生效（Run 模式不触发热部署）；`mvn spring-boot:run` 同样支持 classpath 变更自动重启
- dev profile 已开 `lazy-initialization: true`，DevTools 重启比冷启动快
- 生产镜像不受影响：spring-boot-maven-plugin repackage 默认排除 devtools
- 本地全栈跑通：各服务 `application-dev.yml` 的 `app.*.base-url` 默认值已指向 `localhost:1808x`（环境变量仍可覆盖），先用 `scripts/dev-up.ps1`（或 dev-up.sh）启动 MySQL/Nacos/Redis/RabbitMQ，再本地起需要调试的服务

**本地一键启动（免 IDE 环境变量配置）**：`bash scripts/run-local.sh <service>`（如 `team-service`/`gateway`）——自动读根目录 `.env` 注入 Nacos(18048)/Redis/RabbitMQ/Jasypt/内部 Token，并按服务把 `XXX_DATASOURCE_PASSWORD` 统一指向 `MYSQL_ROOT_PASSWORD`（dev yml 默认 123456 与 Docker MySQL 不一致），再 `mvn spring-boot:run` 启动，DevTools 改代码自动重启；换机器/换目录零配置。`DRY_RUN=1` 可只打印注入变量不启动。需要断点调试时再用 IDEA 运行配置（同一套环境变量）。

### Frontend (run in `ZXYZdatabaseFront/`)

```bash
npm run dev          # dev server, port 5173
npm run build        # production build to dist/
npm run preview      # preview build, port 4173
npm run lint         # ESLint check
npm run lint:fix     # ESLint auto-fix
npm run format       # Prettier format
npm run test         # Vitest single run
npm run test:watch   # Vitest watch mode
npm run test:coverage # Vitest + @vitest/coverage-v8 coverage

各服务可单独启动(端口见上表 Backend Modules)：`mvn -pl zxyz-{service}-service spring-boot:run`（audit 服务通常不单独 run）。
```

## Architecture

**Backend**: Java 17, Spring Boot 3.5.7, Spring Cloud 2025.0.0, Maven multi-module. Group: `uno.acloud`, base package: `uno.acloud.{service}`.

**Frontend**: Vue 3.5 (Composition API + `<script setup>`), Vite 7.3, Element Plus 2.11 (auto-import), Pinia 3.0, Axios 1.13, Vitest 4.1.

### Backend: 11 Maven Modules

| Module | Port | Database | Architecture |
|---|---|---|---|
| `zxyz-common` | — | — | Shared: error codes, Result, permissions, OSS client, service clients, ConfigServiceClient, audit, MQ constants |
| `zxyz-gateway` | 18000 | — | Spring Cloud Gateway (WebFlux), Sa-Token auth, Redis rate limiting |
| `zxyz-project-service` | 18080 | zxyz_project | Traditional layering |
| `zxyz-im-service` | 18081/19090 | zxyz_im | **DDD** (interfaces → application → domain) + Netty WebSocket |
| `zxyz-email-service` | 18082 | zxyz_email | **DDD** (interfaces → application → domain) |
| `zxyz-user-service` | 18083 | zxyz_user | Traditional layering |
| `zxyz-share-service` | 18084 | zxyz_share | Traditional layering |
| `zxyz-file-service` | 18085 | zxyz_file | Traditional layering |
| `zxyz-team-service` | 18086 | zxyz_team | Traditional layering |
| `zxyz-audit-service` | 18087 | zxyz_audit | RabbitMQ consumer for operation logs + 持久化到 zxyz_audit 库 |
| `zxyz-admin-service` | 18088 | zxyz_config | Config management: ConfigService + Jasypt + Caffeine cache + Redis Pub/Sub |

详细架构说明：[后端](docs/claude-backend.md) · [前端](docs/claude-frontend.md) · [基础设施](docs/claude-infra.md)

## Backend Conventions

WHEN 编写后端代码, DO 在 `ZXYZdatabaseBack/` 目录执行 Maven 命令。
WHEN 新增服务, DO 使用 base package `uno.acloud.{service}`（如 `uno.acloud.project`）。
WHEN 测试命名, DO 使用 `*Test.java`（非 `*Tests.java`）。
WHEN 编写 MapStruct 转换器, DO 使用 `*Converter`/`*Assembler` 类名，与 Lombok 兼容。
WHEN email-service 或 im-service 需要新功能, DO 使用 DDD 风格；其他服务使用传统分层。
WHEN 服务间调用, DO 通过 `*ServiceClient` + `X-Internal-Service-Token` 鉴权；异步用 RabbitMQ Topic Exchange `zxyz.topic`。
WHEN 新增 Maven 模块, DO 同时加入根 `pom.xml` 的 `<modules>` 列表（参考 `zxyz-web-tools/` 反例：未注册构建、包名 `uno.acloud.monitor.platform.web.tools.*` 违反 `uno.acloud.{service}` 约定，应清理）。
WHEN 修改 Gateway 路由, DO 同步更新 `docs/infrastructure.md` 中的路由表。

**安全强制**：
- 敏感字段（密码/token/密文）加 `@JsonProperty(access = WRITE_ONLY)` 或 `@JsonIgnore`，实体 `@ToString(exclude = {...})`，不序列化的配置类用 `@JsonIgnore`
- `INTERNAL_SERVICE_TOKEN` 在 YAML 中**禁止默认值**（勿用 `${INTERNAL_SERVICE_TOKEN:dev-internal-token}`），含 `application-dev.yml`
- 用户文件/文件夹名必须过 `FileDomainValidator.validateInputName()` / `FileRenameService.validateRenameName()`（拒绝 `< > " ' &`）
- 上传白名单：`.js` 在 `BLOCKED_EXTENSIONS`（浏览器 XSS 风险）勿加入 `ALLOWED`；`GetSignUrl.java` 有重复 BLOCKED 集合需同步

**关键坑位**：
- Config 绑定用平铺 `app.internal-service-token`，勿嵌套 `app.internal.service-token`
- `@ConfigurationProperties` 的 YAML key 必须与 prefix 精确匹配（如 `TeamServiceProperties(prefix="app.team-service")`）
- MapStruct `@Mapper` 勿与 MyBatis `@MapperScan` 同包（会被劫持），放独立包如 `uno.acloud.{service}.convert`
- `@RequiresTeamPermission` 支持个人空间（teamId=null）的端点须显式 `skipWhenTeamIdMissing = true`，否则报 "teamId 不能为空"
- HTTP/MQ 远程调用勿放 `@Transactional` 内（占用 DB 连接），用 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 延迟并 try-catch
- MQ 反序列化失败抛 `AmqpRejectAndDontRequeueException` 进 DLQ，勿 log-and-return（会静默 ACK 毒消息）
- 缓存清理勿用 `@CacheEvict(allEntries = true)`，用 `StringRedisTemplate.scan` 精确删对应 team 的 key

> 其余后端约定与坑位（服务 URL 配置、自动配置条件、GlobalExceptionHandler、config 加密/admin 数据源、config 管理 API、Gateway 重写、RestClient 超时、AbstractServiceClient、API 契约、ServiceClient 包位置、投影规范与内部端点清单）详见 [docs/claude-backend.md](docs/claude-backend.md)。

## Frontend Conventions

WHEN 编写前端代码, DO 在 `ZXYZdatabaseFront/` 目录执行 npm 命令。
WHEN 添加 API 接口, DO 按领域放入对应 `api/` 文件，禁止跨领域引用。
WHEN 选择 HTTP 客户端, DO 按场景选：`request.js`（已认证，默认）、`publicRequest.js`（公开/分享）、`imRequest.js`（IM）。所有客户端均 `withCredentials: true`。
WHEN 处理认证, DO 依赖 HttpOnly Cookie（`withCredentials: true`），不手动注入 Authorization Header。
WHEN 显示时间戳, DO 使用 `fmtTime()` 函数（`utils/format.js`），不要直接 `|| '-'` 显示原始值。
WHEN 设置时间戳默认值, DO 使用 `?? null`（而非 `|| null`），`||` 会将空字符串 `""` 和 `0` 误转为 `null`。
WHEN 处理文件操作, DO 使用 `composables/` 中的组合函数，不直接操作 store。
WHEN 提交代码, DO 使用 conventional commits 格式（Husky + commitlint 强制）。
WHEN 处理错误, DO 使用 `BusinessException` → `ErrorCode` → `Result` 模式。
WHEN 添加 API 接口, DO 参考 `src/api/README.md` 中的模块规范。
WHEN 添加 admin 页面, DO 放在 `src/views/setting/` 下，路由在 `src/router/index.js` 中配置。测试页面可不加 `beforeEnter` 权限守卫。
WHEN 添加 setting 子路由, DO 确保 `route.name` 在 Setting 组件 watcher 的 `{ immediate: true }` 执行前已就绪，否则会被重定向到第一个可见 tab。

## Infrastructure & CI/CD

- **MySQL 8.4**: 10 个独立库（含 zxyz_config），表结构**仅由 Flyway 管理**（勿维护 `sql/schema_*.sql`）；DB init: `sql/00-init-zxyz.sh`
- **Redis**: localhost:6379（Sa-Token sessions + Redisson 锁）；**Nacos**: localhost:8848 注册中心 + Config（`spring.config.import:nacos:`，10 服务接入，模板在 `nacos-config/`）；**RabbitMQ**: localhost:5672（Topic `zxyz.topic`）
- **Auth**: Sa-Token 1.45.0（UUID token，Redis session，HttpOnly cookie）；API Docs: Knife4j 4.5.0 + springdoc 2.8.9
- **Docker**: `docker-compose.yml` 编排 18 服务（5 基础设施 + 10 后端 + frontend-nginx + loki/promtail），统一 `Dockerfile` + `Dockerfile.base`（builder 缓存镜像）with `MODULE` build arg，镜像推 GHCR
- **Nginx CSP**: `deploy/nginx/default.conf` 用 `envsubst` 模板化，`OSS_PUBLIC_BASE_URL` 启动时注入，勿硬编码 OSS 域名

**强制部署项**：
- 所有服务（含 gateway、admin-service）docker-compose environment 必须传 `INTERNAL_SERVICE_TOKEN`（无默认值）与 `RABBITMQ_HOST: rabbitmq`，否则健康检查连 localhost 失败
- `.env` 的 `CHANGE_ME_*` 首部署必须替换；改 `.env` 后须 `docker compose up -d` 重建（restart 不重载 env）；首次部署前跑 `./scripts/validate-env.sh .env`
- 服务器 `.env` 在 `/www/zxyz/.env`，独立于仓库维护，CI/CD 不同步
- 镜像标签：dev → `dev`，main → `latest`，tag → 版本号；每镜像双 tag（`${tag}` + `${git_sha}` 供精确回滚）；本地改 `.env` 的 `APP_IMAGE_TAG`/`IMAGE_PREFIX` 控制部署目标

**前端测试**: 26 个测试文件，278 个用例（`npm run test`）。命名 `*.spec.js` 放对应目录 `__tests__/` 下，`vi.mock()` 外部依赖，测试名中文。import 顺序：vitest/vue 最前 → `vi.mock()` 紧跟 → 再 `@/` 与第三方（`element-plus` import 须在 `vi.mock()` 后，否则 `import-x/order` 报错）。详见 [docs/testing.md](docs/testing.md)。

**CI/CD**: `.github/workflows/ci-cd.yml` 按路径变更选择性构建部署。push 到 dev/main、`v*` tag、PR、手动 dispatch；`dorny/paths-filter` 按服务目录判断重建；backend-common 变更触发全部后端重建；docker-compose.yml 变更不触发重建；workflow_dispatch 输入 `tag`（必填）/`skip_quality`/`fast_deploy`。

> Gateway 路由表与服务间调用图：`docs/infrastructure.md`；技术栈：`docs/architecture.md`；部署指南：`DEPLOYMENT.md`。项目历史审查报告见 `ISSUE/`（`PROJECT-REVIEW-2026-07-27.md` 全面审查、`PROJECT-DEEP-REVIEW-2026-07-28.md` 深度审查；目录已 gitignore，仅本机保留）。
> docker-compose 服务编排详情、deploy-fast/rollback/backup/dev-up 脚本参数、部署注意事项与运维提示详见 [docs/claude-infra.md](docs/claude-infra.md)。

## 服务间接口设计规范

服务间调用采用**窄端点优先 + 调用方投影**：内部端点返回投影对象而非胖 DTO，ServiceClient 返回本服务 POJO/标量，JsonNode→投影用手动字段映射，提供方/调用方投影双类通过 JSON wire 解耦，禁止返回上游公共 DTO。

> 完整规范（9 条规则、投影模式说明、内部窄端点清单、已落地窄端点表）详见 [docs/claude-backend.md](docs/claude-backend.md) 的「服务间接口设计规范」章节。

## Work Principles

本文件是唯一权威的项目指令；`.qoder/`、`.trae/`、`.opencode/`、`.workbuddy/` 等目录是其他工具的配置/笔记（含可能过时的 spec 草案），不要当作项目规则来源。

WHEN 不确定, DO 提问，不猜测。
WHEN 设计方案, DO 先理解"为什么这样设计"再决定"怎么改"。
WHEN 问题复杂, DO 拆解，能简单解决就不要复杂化（KISS）。
WHEN 实现方案, DO 保持现有风格，优先最小改动，避免重复代码。
WHEN 关注架构, DO 检查单一职责（SRP）、耦合度、可扩展性和回归风险。
