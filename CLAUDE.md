# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

指绣云章 (ZXYZ) — 云端文件管理平台（团队协作 + IM）。详细文档见 `docs/architecture.md`、`docs/commands.md`、`docs/infrastructure.md`。

## Git

根目录 `zxyz/` **不是** git 仓库。两个独立仓库：

| 目录 | 分支 |
|---|---|
| `ZXYZdatabaseBack/` | master |
| `ZXYZdatabaseFront/` | main |

WHEN 执行 git 操作, DO cd 到对应子目录再执行。
WHEN 修改前后端, DO 分别提交到各自仓库。
WHEN 文件在根目录（`ISSUE/`、`sql/`、`docker-compose.yml`）, DO 不提交到任何仓库。

## Build & Test Commands

### Backend (run in `ZXYZdatabaseBack/`)

```bash
mvn clean -DskipTests compile                   # baseline compile check
mvn test                                         # all tests (~65 test classes)
mvn test -pl zxyz-team-service                   # single module tests
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # single test class
mvn clean package -DskipTests                    # package for Docker build
mvn -pl zxyz-project-service spring-boot:run     # run single service
```

### Frontend (run in `ZXYZdatabaseFront/`)

```bash
npm run dev       # dev server, port 5173
npm run build     # production build to dist/
npm run lint      # ESLint check
npm run lint:fix  # ESLint auto-fix
npm run format    # Prettier format
npm run test      # Vitest single run
npm run test:watch # Vitest watch mode
```

## Architecture

**Backend**: Java 17, Spring Boot 3.5.7, Spring Cloud 2025.0.0, Maven multi-module. Group: `uno.acloud`, base package: `uno.acloud.{service}`.

**Frontend**: Vue 3.5 (Composition API + `<script setup>`), Vite 7.3, Element Plus 2.11 (auto-import), Pinia 3.0, Axios 1.13, Vitest 4.1.

### Backend: 10 Maven Modules

| Module | Port | Database | Architecture |
|---|---|---|---|
| `zxyz-common` | — | — | Shared: error codes, Result, permissions, OSS client, service clients, audit, MQ constants |
| `zxyz-gateway` | 18000 | — | Spring Cloud Gateway (WebFlux), Sa-Token auth, Redis rate limiting |
| `zxyz-project-service` | 18080 | zxyz_project | Traditional layering |
| `zxyz-im-service` | 18081/19090 | zxyz_im | **DDD** (interfaces → application → domain) + Netty WebSocket |
| `zxyz-email-service` | 18082 | zxyz_email | **DDD** (interfaces → application → domain) |
| `zxyz-user-service` | 18083 | zxyz_user | Traditional layering |
| `zxyz-share-service` | 18084 | zxyz_share | Traditional layering |
| `zxyz-file-service` | 18085 | zxyz_file | Traditional layering |
| `zxyz-team-service` | 18086 | zxyz_team | Traditional layering |
| `zxyz-audit-service` | 18087 | — | RabbitMQ consumer for operation logs |

**Two patterns coexist**:
- **Traditional layering** (most services): `controller/` → `service/` + `impl/` → `mapper/` → `entity/`, with `dto/`, `vo/`, `config/`, `satoken/`, `infrastructure/`
- **DDD** (email-service, im-service): `interfaces/` → `application/` → `domain/` + `infrastructure/` + `config/`

### Inter-service Communication

- **Synchronous**: `*ServiceClient` classes extending `AbstractServiceClient` (in zxyz-common), authenticated with `X-Internal-Service-Token` header, Resilience4j retry (3×500ms) + circuit breaker (50%)
- **Asynchronous**: RabbitMQ Topic Exchange `zxyz.topic` via `*EventPublisher` classes

### Frontend: Vue 3 SPA

- Vue 3 (Composition API + `<script setup>`), Vite 7, Element Plus 2.11 (auto-import), Pinia 3, Axios
- Three HTTP clients: `request.js` (auth), `imRequest.js` (IM), `publicRequest.js` (public)
- API modules strictly domain-separated in `api/` — cross-domain imports forbidden
- ~40 composables in `composables/` drive file explorer behavior
- IM WebSocket via `imWebSocket.js`, state synced via Pinia `im/` stores

## Local Development

`start/` directory has Windows `.bat` scripts to start middleware (MySQL, Redis, Nacos, RabbitMQ) locally.

## Backend Conventions

WHEN 编写后端代码, DO 在 `ZXYZdatabaseBack/` 目录执行 Maven 命令。
WHEN 新增服务, DO 使用 base package `uno.acloud.{service}`（如 `uno.acloud.project`）。
WHEN 测试命名, DO 使用 `*Test.java`（非 `*Tests.java`）。
WHEN 编写 MapStruct 转换器, DO 使用 `*Converter`/`*Assembler` 类名，与 Lombok 兼容。
WHEN email-service 或 im-service 需要新功能, DO 使用 DDD 风格。
WHEN 其他服务需要新功能, DO 使用传统分层。
WHEN 服务间调用, DO 通过 `*ServiceClient` + `X-Internal-Service-Token` 鉴权。
WHEN 需要异步通信, DO 使用 RabbitMQ Topic Exchange `zxyz.topic`。
WHEN 修改 Gateway 路由, DO 同步更新 `docs/infrastructure.md` 中的路由表。

**Config binding pitfall**: All services use flat `app.internal-service-token` in YAML with `@Value` or `@ConfigurationProperties(prefix="app")`. Do NOT nest it under `app.internal.service-token` — Spring Boot cannot bind nested YAML to flat fields. If you see empty token values at runtime, check the YAML structure.

**MyBatis + MapStruct conflict**: MyBatis `@MapperScan` can hijack MapStruct `@Mapper` interfaces in the same package. Keep MapStruct mappers in a separate package (e.g., `uno.acloud.{service}.convert`).

## Frontend Conventions

WHEN 编写前端代码, DO 在 `ZXYZdatabaseFront/` 目录执行 npm 命令。
WHEN 添加 API 接口, DO 按领域放入对应 `api/` 文件，禁止跨领域引用。
WHEN 选择 HTTP 客户端, DO 按场景选：`request.js`（需登录）、`imRequest.js`（IM）、`publicRequest.js`（公开）。
WHEN 处理认证, DO 依赖 HttpOnly Cookie（`withCredentials: true`），不手动注入 Authorization Header。
WHEN 处理文件操作, DO 使用 `composables/` 中的组合函数，不直接操作 store。
WHEN 提交代码, DO 使用 conventional commits 格式（Husky + commitlint 强制）。
WHEN 处理错误, DO 使用 `BusinessException` → `ErrorCode` → `Result` 模式。
WHEN 添加 API 接口, DO 参考 `src/api/README.md` 中的模块规范。

## Infrastructure

- **MySQL 8.4**: 8 independent databases, schemas in `sql/` directory, Flyway migrations per service at `src/main/resources/db/migration/`. DB init script: `sql/00-init-zxyz.sh`
- **Redis**: localhost:6379, Sa-Token sessions (shared) + Redisson distributed locks
- **Nacos**: localhost:8848, service registry
- **RabbitMQ**: localhost:5672, Topic Exchange `zxyz.topic`
- **Auth**: Sa-Token 1.43.0 (UUID token, Redis session store, HttpOnly cookie)
- **API Docs**: Knife4j 4.5.0 + springdoc 2.8.9 (available at each service's doc endpoint)
- **Docker**: `docker-compose.yml` orchestrates all services; unified `Dockerfile` with `MODULE` build arg

Gateway routing table and inter-service call map: `docs/infrastructure.md`
Build/run commands: `docs/commands.md`
Tech stack details: `docs/architecture.md`
Deployment guide: `DEPLOYMENT.md`

## Work Principles

WHEN 不确定, DO 提问，不猜测。
WHEN 设计方案, DO 先理解"为什么这样设计"再决定"怎么改"。
WHEN 问题复杂, DO 拆解，能简单解决就不要复杂化（KISS）。
WHEN 实现方案, DO 保持现有风格，优先最小改动，避免重复代码。
WHEN 关注架构, DO 检查单一职责（SRP）、耦合度、可扩展性和回归风险。
