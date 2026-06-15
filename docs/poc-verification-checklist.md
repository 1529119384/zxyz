# Phase 1 PoC 验证清单

## 概述

4 项 PoC 验证任务，全部通过后方可全面铺开 Nacos Config。每项 PoC 需在独立的最小项目或现有服务中验证，确保 Spring Cloud Alibaba 2025.0.0.0 + Nacos 3.2.1 + Jasypt 3.0.5 的技术栈组合可靠运行。

**技术栈版本**：
- Spring Boot 3.5.7
- Spring Cloud Alibaba 2025.0.0.0
- Nacos Server 3.2.1（`/next/` 控制台路径）
- Jasypt 3.0.5（`jasypt-spring-boot-starter`）

**验证环境**：本地开发环境（localhost），Nacos 单机模式。

---

## PoC-1: `nacos:` 协议兼容性

- **目标**：验证 `spring.config.import: nacos:` 协议在 Spring Cloud Alibaba 2025.0.0.0 中是否正常工作
- **环境**：本地 Nacos 单机（localhost:8848），最小 Spring Boot 3.5.7 项目或 zxyz-project-service
- **步骤**：
  1. 确认 `pom.xml` 中包含 `spring-cloud-starter-alibaba-nacos-config` 依赖
  2. 在 `application.yml` 中配置：
     ```yaml
     spring:
       config:
         import:
           - nacos:test-poc.yml?group=TEST&refreshEnabled=true
     ```
  3. 在 Nacos 控制台发布 `test-poc.yml`（Group: TEST），内容：
     ```yaml
     poc:
       test-value: hello-from-nacos
       refresh-value: initial
     ```
  4. 启动应用，验证 `@Value("${poc.test-value}")` 注入为 `hello-from-nacos`
  5. 验证 `refreshEnabled=true`：在 Nacos 控制台修改 `refresh-value` 为 `updated`，等待 30 秒，检查应用是否读取到新值
  6. 验证 `refreshEnabled=false`：添加另一个导入 `nacos:test-static.yml?group=TEST&refreshEnabled=false`，修改其值后确认应用**不**刷新
  7. 验证导入顺序：确认 `classpath:application-common.yml` 在前、`nacos:` 在后时，Nacos 配置正确覆盖本地默认值
- **通过标准**：
  - [ ] `nacos:` 协议被 Spring Boot 正确识别，不抛出 `UnsupportedConfigDataLocationException`
  - [ ] 配置从 Nacos 成功加载，`@Value` 注入值正确
  - [ ] `refreshEnabled=true` 时，Nacos 控制台修改配置后应用自动刷新（30 秒内生效）
  - [ ] `refreshEnabled=false` 时，Nacos 控制台修改配置后应用**不**刷新
  - [ ] 导入顺序正确：Nacos 配置覆盖本地 `application-common.yml` 中的同名键
- **验证结果**: [ ] 通过 / [ ] 未通过
- **失败回退方案**：切换到 bootstrap 模式——添加 `spring-cloud-starter-bootstrap` 依赖，创建 `bootstrap.yml` 放置 Nacos Config 连接配置
- **备注**：当前项目已使用 `nacos:` 协议（见各服务 `application.yml` 的 `spring.config.import` 配置），此 PoC 重点验证协议在全新/干净环境下的可靠性和刷新行为

---

## PoC-2: `@ConfigurationProperties` 刷新行为

- **目标**：验证 `@ConfigurationProperties` 绑定的 Bean 在 Nacos 配置变更后是否自动刷新，确定热更新策略
- **环境**：本地 Nacos 单机 + zxyz-project-service（或最小测试项目）
- **步骤**：
  1. 创建一个测试用 `@ConfigurationProperties` 类：
     ```java
     @ConfigurationProperties(prefix = "poc")
     public class PocProperties {
         private String dynamicValue = "default";
         // getter/setter
     }
     ```
  2. 在 Nacos 的 `test-poc.yml` 中配置 `poc.dynamic-value: initial`
  3. 启动应用，注入 `PocProperties` Bean，确认 `dynamicValue` 为 `initial`
  4. **场景 A（无 @RefreshScope）**：在 Nacos 控制台修改 `poc.dynamic-value` 为 `updated-v1`，等待 30 秒，检查 Bean 属性是否自动更新
  5. **场景 B（加 @RefreshScope）**：如果场景 A 不刷新，给 `PocProperties` 加 `@RefreshScope` 注解，重复修改 Nacos 配置，检查是否刷新
  6. **场景 B 副作用检查**：如果 `@RefreshScope` 生效，验证以下副作用：
     - `instanceof PocProperties` 检查是否正常（CGLIB 代理可能导致失败）
     - JSON 序列化（Jackson）是否包含代理元数据
     - 构造函数注入是否正常工作
  7. **场景 C（@Value 刷新）**：同时测试 `@Value("${poc.dynamic-value}")` + `@RefreshScope` 的组合，确认 `@Value` 注入是否刷新
- **通过标准**：
  - [ ] 场景 A：`@ConfigurationProperties` 自动刷新 → 结论 A，零成本直接使用
  - [ ] 场景 B：需 `@RefreshScope` 但生效 → 结论 B，评估代理副作用后决定
  - [ ] 场景 C：`@Value` + `@RefreshScope` 可用 → 结论 C 的备选方案
  - [ ] 记录实际刷新延迟（从 Nacos 修改到 Bean 属性更新的秒数）
- **验证结果**: [ ] 通过 / [ ] 未通过
- **决策矩阵**：

  | 结果 | 条件 | 影响 | 行动 |
  |---|---|---|---|
  | **A：自动刷新可用** | SCA 2025.0.0.0 原生支持 | 19 个 Properties 类无需改动 | 零成本，直接使用 |
  | **B：需 @RefreshScope** | 不支持自动刷新，但 `@RefreshScope` 生效 | 19 个 Properties 类需加 `@RefreshScope`，需评估 CGLIB 代理副作用 | 评估后决定 |
  | **C：均不支持** | 自动刷新和 `@RefreshScope` 都不工作 | 仅 `@Value` + `@RefreshScope` 或 `ConfigService.get()` 可热更新 | 缩小热更新范围 |

- **备注**：当前代码库零使用 `@RefreshScope`，所有 19 个 `@ConfigurationProperties` 类和 8 处 `@Value` 注入在启动后均为静态值。此 PoC 结果直接决定 Phase 1 的热更新策略

---

## PoC-3: Nacos 3.x 服务端兼容性

- **目标**：验证 Nacos 3.2.1 服务端与 Spring Cloud Alibaba 2025.0.0.0 客户端的兼容性
- **环境**：Docker Compose 中的 `nacos-server:v3.2.1`（单机模式）
- **步骤**：
  1. 启动 Nacos 3.2.1：`docker-compose up -d nacos`
  2. 验证控制台可访问：浏览器打开 `http://localhost:8848/nacos/`，确认重定向到 `/next/` 路径
  3. **配置读取**：通过 Nacos 控制台或 API 发布配置，验证 SCA 客户端能读取：
     ```bash
     curl -s "http://localhost:8848/nacos/v1/cs/configs?dataId=test-poc.yml&group=TEST"
     ```
  4. **配置写入**：通过 API 写入配置，验证 HTTP 200 响应：
     ```bash
     curl -s -X POST "http://localhost:8848/nacos/v1/cs/configs" \
       -d "dataId=test-poc.yml&group=TEST&content=poc.value=hello&type=yaml"
     ```
  5. **服务注册发现**：启动任意一个 zxyz 服务（如 project-service），在 Nacos 控制台确认服务列表中出现该服务
  6. **配置监听**：启动应用后修改 Nacos 配置，确认客户端收到配置变更推送（观察日志中的 `config changed` 相关输出）
  7. **鉴权**：如果 Nacos 启用了鉴权（`NACOS_AUTH_ENABLE=true`），验证 `username`/`password` 参数能正常通过鉴权
- **通过标准**：
  - [ ] Nacos 3.2.1 控制台可访问（`/next/` 路径正常加载）
  - [ ] 配置读取 API（`/nacos/v1/cs/configs`）返回正确内容
  - [ ] 配置写入 API 返回 200
  - [ ] 服务注册发现正常（Nacos 控制台可见已注册服务）
  - [ ] 配置变更推送到客户端正常工作
  - [ ] 鉴权通过（如启用）
- **验证结果**: [ ] 通过 / [ ] 未通过
- **备注**：Nacos 3.x 使用 `/next/` 控制台路径（替代 2.x 的默认路径）。如果 v1 API 不兼容，需检查 SCA 2025.0.0.0 的 nacos-client 版本是否支持 Nacos 3.x 的 API 路径

---

## PoC-4: Jasypt 3.0.5 兼容性

- **目标**：验证 `jasypt-spring-boot-starter` 3.0.5 与 Spring Boot 3.5.7 + Nacos Config 的兼容性
- **环境**：本地开发环境，`zxyz-project-service`（已有 Jasypt 配置）
- **步骤**：
  1. 确认 `pom.xml` 中包含 `jasypt-spring-boot-starter` 3.0.5 依赖
  2. 确认 `application-common.yml` 中已配置 Jasypt：
     ```yaml
     jasypt:
       encryptor:
         algorithm: AES/GCM/NoPadding
         iv-generator-classname: org.jasypt.iv.RandomIvGenerator
         password: ${JASYPT_PASSWORD:test-password}
     ```
  3. **本地 YAML 解密**：在 `application-dev.yml` 中添加一个测试值 `poc.secret: ENC(使用JasyptEncryptor加密的密文)`，启动应用，验证 `@Value("${poc.secret}")` 注入为明文
  4. **Nacos 配置解密**：在 Nacos 的 `test-poc.yml` 中配置 `poc.nacos-secret: ENC(密文)`，启动应用，验证 `@Value("${poc.nacos-secret}")` 注入为明文
  5. **EnvironmentPostProcessor 顺序**：检查启动日志，确认 Jasypt 的 `EncryptablePropertySource` 在 Nacos 配置加载**之后**执行（否则 `ENC()` 值无法被 Nacos 属性源捕获并解密）
  6. **JasyptEncryptor 工具类**：调用 `JasyptEncryptor`（项目中的加密工具类）的 `encrypt()` 和 `decrypt()` 方法，验证加解密功能正常：
     ```java
     String encrypted = jasyptEncryptor.encrypt("test-plaintext");
     String decrypted = jasyptEncryptor.decrypt(encrypted);
     assert "test-plaintext".equals(decrypted);
     ```
  7. **Spring Framework 6.x 兼容性**：确认无 `NoSuchMethodError`、`ClassNotFoundException` 等运行时兼容性错误
- **通过标准**：
  - [ ] `ENC()` 格式在本地 YAML 中被自动解密
  - [ ] `ENC()` 格式在 Nacos 配置中被自动解密（关键：Jasypt 能正确包装 Nacos 属性源）
  - [ ] `EnvironmentPostProcessor` 执行顺序正确：Jasypt 在 Nacos 之后运行
  - [ ] `JasyptEncryptor.encrypt()` / `decrypt()` 正常工作
  - [ ] 无 Spring Framework 6.x / Spring Boot 3.5.7 运行时兼容性错误
- **验证结果**: [ ] 通过 / [ ] 未通过
- **备注**：Jasypt 3.0.5 发布于 2023 年，需确认其 `EnvironmentPostProcessor` SPI 与 Spring Boot 3.5.7 兼容。如果执行顺序不对，可能需要通过 `spring.factories` 或 `@AutoConfiguration(before/after)` 手动调整。当前项目已在 `application-common.yml` 中配置了 Jasypt，此 PoC 重点验证与 Nacos Config 的集成

---

## 验证总结

| PoC | 状态 | 验证日期 | 验证人 | 备注 |
|-----|------|---------|--------|------|
| PoC-1: `nacos:` 协议兼容性 | [ ] 待验证 | | | |
| PoC-2: `@ConfigurationProperties` 刷新行为 | [ ] 待验证 | | | 决定热更新策略 |
| PoC-3: Nacos 3.x 服务端兼容性 | [ ] 待验证 | | | |
| PoC-4: Jasypt 3.0.5 兼容性 | [ ] 待验证 | | | |

**全部通过后**：进入 Phase 1 全面铺开，各服务添加 `spring-cloud-starter-alibaba-nacos-config` 依赖并配置 `spring.config.import`。

**任一失败**：根据各项的失败回退方案处理，不影响整体架构设计。
