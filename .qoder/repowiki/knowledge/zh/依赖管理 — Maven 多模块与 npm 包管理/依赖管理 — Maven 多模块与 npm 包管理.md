---
kind: dependency_management
name: 依赖管理 — Maven 多模块与 npm 包管理
category: dependency_management
scope:
    - '**'
source_files:
    - ZXYZdatabaseBack/pom.xml
    - ZXYZdatabaseBack/zxyz-common/pom.xml
    - ZXYZdatabaseBack/zxyz-file-service/pom.xml
    - ZXYZdatabaseFront/package.json
    - ZXYZdatabaseFront/package-lock.json
    - ZXYZdatabaseBack/.mvn/jvm.config
---

## 1. 使用的系统与工具
- 后端：Maven（Spring Boot 3.5.7 父工程）+ Spring Cloud 2025.0.0 + Spring Cloud Alibaba 2025.0.0.0，采用多模块聚合构建。
- 前端：npm（package-lock.json v3），Vite 7.x 作为构建工具，Husky + Commitlint + ESLint + Prettier 作为开发期依赖。
- 无 vendoring 策略，不提交 node_modules 或 .m2/repository；依赖通过远程仓库解析。

## 2. 关键文件与位置
- 后端聚合 POM：`ZXYZdatabaseBack/pom.xml`（定义 modules、properties、dependencyManagement、build/pluginManagement）
- 公共模块 POM：`ZXYZdatabaseBack/zxyz-common/pom.xml`（被各服务依赖的共享依赖声明）
- 示例服务 POM：`ZXYZdatabaseBack/zxyz-file-service/pom.xml`（展示服务层如何引入 common 与第三方库）
- 前端包清单：`ZXYZdatabaseFront/package.json`（dependencies/devDependencies/scripts）
- 前端锁定文件：`ZXYZdatabaseFront/package-lock.json`（精确版本锁定）
- Maven JVM 配置：`ZXYZdatabaseBack/.mvn/jvm.config`（UTF-8 编码）

## 3. 架构与约定
- 统一版本管理：所有第三方库版本集中在父 POM 的 `<properties>` 中（如 sa-token.version、redisson.version、mybatis-plus.version、springdoc.version、knife4j.version、aliyun-oss-v2.version、commons-lang3.version、lombok.version、jaxb-runtime.version、simple-java-mail.version、mapstruct.version、flyway.version、testcontainers.version、spring-cloud.version、spring-cloud-alibaba.version、resilience4j.version、jasypt.version），子模块仅引用 artifactId，不写 version。
- dependencyManagement 集中管控：父 POM 通过 `<dependencyManagement>` 导入 spring-cloud-dependencies 与 spring-cloud-alibaba-dependencies，并统一管理 zxyz-common、MyBatis-Plus、SpringDoc/Knife4j、Sa-Token、Redisson、Resilience4j、Commons Lang3、JAXB、Simple Java Mail、Flyway、Testcontainers、Jasypt 等依赖的版本与 scope。
- 模块边界清晰：zxyz-common 使用 `provided`/`compile`/`test` 等 scope 控制传递性依赖，避免下游重复打包；服务模块仅声明自身运行时所需 starter，其余由父 POM 提供版本。
- 注解处理器统一配置：父 POM 的 maven-compiler-plugin 中集中配置 Lombok 与 MapStruct 注解处理器路径，保证编译一致性。
- 测试依赖隔离：Testcontainers、JUnit Jupiter、spring-boot-starter-test 均在父 POM 中以 test scope 声明，子模块按需引入。
- 前端依赖按运行期与开发期严格拆分：dependencies 仅包含运行时库（vue、pinia、axios、element-plus 等），devDependencies 包含构建、测试、代码质量工具（vite、vitest、eslint、prettier、husky 等）。
- 锁文件策略：前端使用 package-lock.json 锁定精确版本，确保 CI/本地一致；后端未使用 lockfile，依赖版本由 Maven properties 约束。

## 4. 约定与约束
- 新增第三方依赖必须先在父 POM 的 `<properties>` 中声明版本号，并在 `<dependencyManagement>` 中注册 artifact，子模块再直接引用 artifactId。
- 公共能力下沉到 zxyz-common，并通过 `provided` scope 避免重复依赖；仅在确实需要传递到运行时才使用 compile scope。
- 所有服务模块继承父 POM 的 build 配置（jacoco、spring-boot-maven-plugin、maven-compiler-plugin），不得在子模块重复声明相同 plugin 配置。
- 前端禁止将 node_modules 提交到版本库，依赖安装由 package-lock.json 保障可重现。
- 未发现私有 Maven/NPM 仓库或镜像配置（pom.xml 中无 repository/mirror 片段），默认使用中央仓库及 npm 默认源（lock 文件中出现 npmmirror.com 记录，表明可能通过 npm 配置使用国内镜像）。
- 无 GOPATH/go.mod 等 Go 依赖管理痕迹，本仓库不涉及 Go 生态依赖管理。
