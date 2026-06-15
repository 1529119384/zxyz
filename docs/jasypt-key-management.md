# Jasypt 密钥管理文档

本文档说明 Jasypt 在项目中的密钥管理策略、加密操作流程和密钥轮换方案。

## 1. 概述

Jasypt (Java Simplified Encryption) 是本项目用于加密配置文件中敏感信息的统一方案。通过 `jasypt-spring-boot-starter`，项目可以透明地加密和解密数据库密码、Redis 密码、API 密钥等敏感配置。

**核心特性**：
- 加密后的值格式为 `ENC(ciphertext)`
- Spring Boot 启动时自动解密，业务代码无需改动
- 支持 Nacos 配置中心的敏感值加密

## 2. 加密算法

项目使用 **AES/GCM/NoPadding** 算法：

| 参数 | 值 | 说明 |
|---|---|---|
| 算法 | AES/GCM/NoPadding | AES 加密 + GCM 认证模式，无填充 |
| IV 生成器 | RandomIvGenerator | 每次加密生成随机初始化向量 |
| 密钥 | JASYPT_PASSWORD | 通过环境变量注入 |

**配置位置**：`ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml`

```yaml
jasypt:
  encryptor:
    algorithm: AES/GCM/NoPadding
    iv-generator-classname: org.jasypt.iv.RandomIvGenerator
    password: ${JASYPT_PASSWORD}
```

**算法说明**：
- **AES**：高级加密标准，对称加密算法
- **GCM**：Galois/Counter Mode，提供认证加密（AEAD），同时保证数据机密性和完整性
- **NoPadding**：GCM 模式不需要填充，由算法本身处理

## 3. 密钥管理

### 3.1 JASYPT_PASSWORD 环境变量

**生产环境必须通过环境变量注入，严禁硬编码在代码或配置文件中。**

**Docker Compose 配置**（所有服务共享）：
```yaml
environment:
  JASYPT_PASSWORD: ${JASYPT_PASSWORD}
```

**服务器 .env 文件**（`/www/zxyz/.env`）：
```bash
# Jasypt 加密密钥。必须使用强密码，建议 32 位随机字符串。
# 生成方式: openssl rand -base64 32
JASYPT_PASSWORD=your-strong-password-here
```

### 3.2 密钥生成建议

使用以下命令生成 32 字节随机密钥：

```bash
# 方式一：OpenSSL（推荐）
openssl rand -base64 32

# 方式二：Python
python -c "import secrets; print(secrets.token_urlsafe(32))"

# 方式三：Java
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="dummy" password="dummy" verbose=false 2>&1 | head -1
```

**密钥要求**：
- 长度：至少 32 字节（256 位）
- 复杂度：包含大小写字母、数字、特殊字符
- 唯一性：不同环境（开发、测试、生产）使用不同密钥

### 3.3 密钥存储位置

| 环境 | 存储位置 | 说明 |
|---|---|---|
| 开发环境 | `.env` 文件或 IDE 环境变量 | 可使用简单密钥，方便调试 |
| 测试环境 | 服务器 `.env` 文件 | 与生产环境隔离 |
| 生产环境 | 服务器 `.env` 文件 + 密钥管理服务 | 不纳入版本控制 |

**安全要求**：
- `.env` 文件已加入 `.gitignore`，禁止提交到 Git
- 生产环境建议使用密钥管理服务（如 AWS Secrets Manager、阿里云 KMS、HashiCorp Vault）

## 4. 加密操作

### 4.1 使用 JasyptEncryptor 工具类

项目提供 `uno.acloud.common.util.JasyptEncryptor` 工具类：

```java
import uno.acloud.common.util.JasyptEncryptor;

@Service
public class ConfigEncryptionService {

    private final JasyptEncryptor jasyptEncryptor;

    public ConfigEncryptionService(JasyptEncryptor jasyptEncryptor) {
        this.jasyptEncryptor = jasyptEncryptor;
    }

    public void encryptSensitiveValues() {
        // 加密数据库密码
        String encryptedDbPassword = jasyptEncryptor.encrypt("my-db-password");
        System.out.println("DB Password: " + encryptedDbPassword);
        // 输出: ENC(base64_encrypted_value)

        // 解密
        String decrypted = jasyptEncryptor.decrypt(encryptedDbPassword);
        System.out.println("Decrypted: " + decrypted);
        // 输出: my-db-password

        // 检查是否已加密
        boolean isEncrypted = jasyptEncryptor.isEncrypted(encryptedDbPassword);
        System.out.println("Is Encrypted: " + isEncrypted);
        // 输出: true
    }
}
```

**使用场景**：
- 在应用启动后，通过单元测试或临时接口生成加密值
- 适用于开发和测试环境

### 4.2 使用命令行工具加密

**注意**：Jasypt 1.9.3 CLI 使用 PBE 算法，与 starter 默认的 AES/GCM 不同。需要确保算法配置一致。

```bash
# 下载 Jasypt CLI 工具
wget https://github.com/jasypt/jasypt/releases/download/jasypt-1.9.3/jasypt-1.9.3.jar

# 加密（需要指定算法和 IV 生成器）
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="my-secret-password" \
  password="${JASYPT_PASSWORD}" \
  algorithm=AES/GCM/NoPadding \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator

# 输出类似：
# ----ENVIRONMENT-----------------
# Runtime: ...
# ----ARGUMENTS-------------------
# algorithm: AES/GCM/NoPadding
# input: my-secret-password
# ivGeneratorClassName: org.jasypt.iv.RandomIvGenerator
# password: ...
# ----OUTPUT----------------------
# ENC(encrypted_value_here)
```

**使用场景**：
- 在 CI/CD 流程中批量加密配置
- 在没有应用运行环境时加密敏感值

### 4.3 加密后的格式

加密后的值格式为 `ENC(ciphertext)`，其中 `ciphertext` 是 Base64 编码的加密数据。

**示例**：
```yaml
# 原始配置（明文）
spring:
  datasource:
    password: my-secret-password

# 加密后配置
spring:
  datasource:
    password: ENC(dBwMkHhF5Q2V3w8j9K0L1M2N3O4P5Q6R7S8T9U0V1W2X3Y4Z5)
```

**格式要求**：
- 必须以 `ENC(` 开头，以 `)` 结尾
- 括号内为 Base64 编码的密文
- 不要在 `ENC(...)` 前后添加空格
- 不要手动修改括号内的内容

## 5. 在 Nacos 配置中使用

### 5.1 配置模板示例

在 Nacos 配置模板中使用 `ENC()` 值：

```yaml
# 数据库配置
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/zxyz_user?useSSL=false&serverTimezone=Asia/Shanghai
    username: zxyz_user
    password: ENC(encrypted_db_password_here)

  # Redis 配置
  data:
    redis:
      host: localhost
      port: 6379
      password: ENC(encrypted_redis_password_here)

  # RabbitMQ 配置
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: ENC(encrypted_rabbitmq_password_here)

# OSS 配置
app:
  oss:
    access-key-id: your-access-key-id
    access-key-secret: ENC(encrypted_oss_secret_here)
```

### 5.2 自动解密原理

`jasypt-spring-boot-starter` 的工作流程：

1. **注册 EnvironmentPostProcessor**：在 Spring Boot 启动早期阶段注册
2. **拦截属性源**：包装所有 `PropertySource`，使其支持透明解密
3. **自动识别 ENC()**：读取配置时，检测 `ENC(...)` 格式
4. **解密并返回明文**：使用 `JASYPT_PASSWORD` 解密，返回明文给应用

**执行顺序**：
```
Spring Boot 启动
  → EnvironmentPostProcessor 执行（Jasypt 在此阶段注册 EncryptablePropertySource）
  → Nacos 配置加载（Nacos 的 EnvironmentPostProcessor）
  → Jasypt 包装 Nacos 属性源（确保 ENC() 值被解密）
  → @ConfigurationProperties 和 @Value 注入（已经是明文）
```

**重要**：Jasypt 和 Nacos 都通过 `EnvironmentPostProcessor` 实现，执行顺序由 `@AutoConfiguration` 的 `before`/`after` 声明决定。必须在 PoC 中验证顺序是否正确。

### 5.3 编辑加密配置注意事项

1. **不要手动编辑加密值**
   - `ENC(...)` 中的内容是加密后的密文，手动修改会导致解密失败
   - 如果需要修改加密值，必须重新加密

2. **使用工具类加密**
   - 启动应用后，通过 `JasyptEncryptor` 工具类加密新值
   - 将加密后的 `ENC(...)` 值粘贴到 Nacos 配置

3. **配置格式**
   - 确保 `ENC(...)` 格式正确，括号完整
   - 不要在 `ENC(...)` 前后添加空格

4. **环境变量优先级**
   - 如果同时存在环境变量和 Nacos 配置，环境变量优先
   - 建议敏感值通过环境变量注入，而非 Nacos 配置

## 6. 密钥轮换流程

### 6.1 轮换场景

- 定期轮换（建议每 90 天一次）
- 密钥泄露
- 人员变动
- 安全审计要求

### 6.2 轮换步骤

**步骤 1：生成新密钥**

```bash
# 生成新密钥
NEW_JASYPT_PASSWORD=$(openssl rand -base64 32)
echo "新密钥: $NEW_JASYPT_PASSWORD"
```

**步骤 2：使用新密钥重新加密所有敏感值**

```bash
# 设置新密钥环境变量
export JASYPT_PASSWORD="$NEW_JASYPT_PASSWORD"

# 使用 Jasypt CLI 加密所有敏感值
# 数据库密码
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="db-password" password="$NEW_JASYPT_PASSWORD" \
  algorithm=AES/GCM/NoPadding ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator

# Redis 密码
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="redis-password" password="$NEW_JASYPT_PASSWORD" \
  algorithm=AES/GCM/NoPadding ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator

# RabbitMQ 密码
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="rabbitmq-password" password="$NEW_JASYPT_PASSWORD" \
  algorithm=AES/GCM/NoPadding ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator

# OSS Secret
java -cp jasypt-1.9.3.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="oss-secret" password="$NEW_JASYPT_PASSWORD" \
  algorithm=AES/GCM/NoPadding ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator
```

**步骤 3：更新 Nacos 配置**

1. 登录 Nacos 控制台
2. 找到所有包含 `ENC(...)` 的配置
3. 将旧的 `ENC(...)` 值替换为新生成的值
4. 发布配置

**步骤 4：更新服务器 .env 文件**

```bash
# 在服务器上更新 .env 文件
vi /www/zxyz/.env

# 修改 JASYPT_PASSWORD 为新密钥
JASYPT_PASSWORD=新密钥
```

**步骤 5：重启所有服务**

```bash
# 使用 Docker Compose 重启所有服务
cd /www/zxyz
docker-compose down
docker-compose up -d

# 或者只重启需要的服务
docker-compose restart zxyz-user-service zxyz-project-service ...
```

**步骤 6：验证服务正常**

```bash
# 检查服务状态
docker-compose ps

# 检查服务日志
docker-compose logs -f zxyz-user-service

# 测试服务功能
curl http://localhost:18083/actuator/health
```

### 6.3 零停机轮换（高级）

如果需要零停机轮换，可以使用 Jasypt 的多密钥支持：

```yaml
jasypt:
  encryptor:
    password: ${JASYPT_PASSWORD}
    password-list: ${JASYPT_PASSWORD_OLD},${JASYPT_PASSWORD}
```

**流程**：
1. 生成新密钥，添加到 `password-list`
2. 使用新密钥加密所有敏感值
3. 更新 Nacos 配置
4. 将旧密钥移到 `password-list` 末尾
5. 最终移除旧密钥

## 7. 注意事项

### 7.1 安全要求

- **不要在代码中硬编码密钥**
- **不要将 .env 文件提交到 Git**
- **不要在日志中打印明文密码**
- **不要在 Nacos 中存储 JASYPT_PASSWORD**

### 7.2 环境隔离

| 环境 | 密钥要求 | 存储方式 |
|---|---|---|
| 开发环境 | 可使用简单密钥 | IDE 环境变量或 `.env` |
| 测试环境 | 与生产环境隔离 | 服务器 `.env` |
| 生产环境 | 必须使用强密码 | 密钥管理服务 + 服务器 `.env` |

### 7.3 敏感配置清单

以下配置建议加密：

- 数据库密码：`spring.datasource.password`
- Redis 密码：`spring.data.redis.password`
- RabbitMQ 密码：`spring.rabbitmq.password`
- OSS AccessKey Secret：`app.oss.access-key-secret`
- SMTP 密码：`email.password`
- Nacos 密码：`spring.cloud.nacos.password`
- 内部服务 Token：`app.internal-service-token`（可选）

### 7.4 故障排查

**问题 1：解密失败**
```
DecryptionException: Unable to decrypt: ENC(encrypted_value)
```

**原因**：
- `JASYPT_PASSWORD` 环境变量未设置或错误
- 加密时使用的密钥与当前不一致
- 加密值被手动修改

**解决方案**：
1. 检查环境变量是否正确设置
2. 确认加密时使用的密钥
3. 重新加密配置值

**问题 2：配置格式错误**
```
IllegalArgumentException: Invalid ENC format
```

**原因**：
- `ENC(...)` 格式不正确
- 括号不匹配
- 包含非法字符

**解决方案**：
1. 检查 `ENC(...)` 格式
2. 重新加密配置值

## 8. 参考资料

- [Jasypt 官方文档](http://www.jasypt.org/)
- [jasypt-spring-boot GitHub](https://github.com/ulisesbocchio/jasypt-spring-boot)
- [AES/GCM/NoPadding 算法说明](https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#cipher-algorithm-names)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)

## 更新日志

- **2026-06-15**: 初始版本，Jasypt 密钥管理文档
