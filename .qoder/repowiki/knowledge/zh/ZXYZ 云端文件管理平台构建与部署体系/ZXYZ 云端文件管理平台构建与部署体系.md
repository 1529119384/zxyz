---
kind: build_system
name: ZXYZ 云端文件管理平台构建与部署体系
category: build_system
scope:
    - '**'
source_files:
    - docker-compose.yml
    - docker-compose.dev.yml
    - ZXYZdatabaseBack/pom.xml
    - ZXYZdatabaseBack/Dockerfile
    - ZXYZdatabaseFront/Dockerfile
    - .github/workflows/ci-cd.yml
    - scripts/deploy-fast.sh
    - scripts/dev-up.sh
    - scripts/validate-env.sh
    - scripts/backup.sh
    - scripts/rollback.sh
    - ZXYZdatabaseFront/package.json
---

## 构建系统概览

ZXYZ 项目采用 **Docker Compose 统一编排 + GitHub Actions CI/CD** 的现代化构建部署体系，支持前后端微服务并行构建、选择性镜像推送和服务器自动化部署。

### 核心构建工具链
- **后端**: Maven 多模块聚合工程（Spring Boot 3.5.7），11个微服务模块通过 `ZXYZdatabaseBack/pom.xml` 统一管理
- **前端**: Vite 7 + Vue 3 单页应用，Node.js 22 环境构建
- **容器化**: Docker Buildx + GHCR（GitHub Container Registry）镜像仓库
- **CI/CD**: GitHub Actions 工作流，支持分支触发、标签发布和手动触发

### 多模块 Maven 架构
后端采用分层模块化设计：
- `zxyz-common`: 公共依赖和工具类
- `zxyz-gateway`: Spring Cloud Gateway 统一入口
- 业务微服务: project-service, im-service, email-service, user-service, share-service, file-service, team-service, audit-service, admin-service
- 每个服务独立打包为可执行 JAR，通过 Dockerfile 参数化构建

### 容器化策略
- **后端镜像**: 基于 `eclipse-temurin:17-jre-alpine`，使用多阶段构建优化镜像大小
- **前端镜像**: Node.js 22 构建静态资源，Nginx 1.27 提供服务和反向代理
- **基础设施**: MySQL 8.4, Redis 7.4, RabbitMQ 3.13, Nacos 3.2.1, Loki 3.0, Promtail 3.0
- **网络隔离**: 所有服务通过 `zxyz-net` 桥接网络通信

### CI/CD 流水线设计
GitHub Actions 工作流包含四个核心阶段：
1. **变更检测**: 使用 `dorny/paths-filter` 精确识别变更的服务模块
2. **质量检查**: 前端 ESLint + Vitest 测试，后端 Maven 编译 + 单元测试
3. **选择性构建**: 仅构建变更的微服务，支持 backend-common 变更时重建所有服务
4. **智能部署**: 根据变更范围动态选择需要更新的服务，支持快速部署和健康检查

### 环境变量管理
- `.env.example` 提供完整配置模板，`validate-env.sh` 验证必需变量
- 支持占位符检测和自动补全缺失配置项
- 生产环境强制修改默认密码和安全配置

### 运维脚本体系
- `dev-up.sh`: 本地开发环境启动（仅基础设施）
- `deploy-fast.sh`: 快速部署单个/多个服务，支持跳过健康检查
- `backup.sh`: MySQL + Redis 数据备份，支持异地同步
- `rollback.sh`: 一键回滚到上一个部署版本
- `health-check.sh`: 服务健康状态检查

### 数据库迁移
- Flyway 10.22.0 管理数据库版本迁移
- 每个服务独立的 `db/migration` 目录
- 初始化脚本通过 Docker 挂载自动执行

### 监控与日志
- Loki + Promtail 收集容器日志
- Nacos 作为配置中心和服务注册发现
- 各服务暴露 `/actuator/health` 健康检查端点

该构建系统实现了从代码提交到生产部署的全自动化流程，支持灰度发布、快速回滚和完整的审计追踪。