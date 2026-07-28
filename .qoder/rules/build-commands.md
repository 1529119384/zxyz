# 构建与测试命令

## Backend (run in `ZXYZdatabaseBack/`)

```bash
mvn clean -DskipTests compile                   # baseline compile check
mvn test                                         # all tests (~83 test classes)
mvn test -pl zxyz-team-service                   # single module tests
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # single test class
mvn clean package -DskipTests                    # package for Docker build
mvn -pl zxyz-project-service spring-boot:run     # run single service
```

各服务可单独启动：`mvn -pl zxyz-{service}-service spring-boot:run`（audit 服务通常不单独 run）。

## Frontend (run in `ZXYZdatabaseFront/`)

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
```

## 架构概览

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
| `zxyz-audit-service` | 18087 | zxyz_audit | RabbitMQ consumer for operation logs |
| `zxyz-admin-service` | 18088 | zxyz_config | Config management: ConfigService + Jasypt + Caffeine cache + Redis Pub/Sub |
