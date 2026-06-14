# Jasypt 配置加密指南

本文档说明如何使用 Jasypt 加密敏感配置值，以及在 Nacos 控制台编辑加密配置时的注意事项。

## 概述

Jasypt (Java Simplified Encryption) 是一个 Java 库，用于加密配置文件中的敏感信息，如数据库密码、API 密钥等。加密后的值格式为 `ENC(ciphertext)`，Spring Boot 启动时会自动解密。

## 配置说明

### 1. Jasypt 依赖

已在 `zxyz-common/pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.github.ulisesbocchio</groupId>
    <artifactId>jasypt-spring-boot-starter</artifactId>
</dependency>
```

### 2. 加密配置

在 `application-common.yml` 中配置：

```yaml
jasypt:
  encryptor:
    algorithm: AES/GCM/NoPadding
    iv-generator-classname: org.jasypt.iv.RandomIvGenerator
    password: ${JASYPT_PASSWORD}
```

**重要**：`JASYPT_PASSWORD` 必须通过环境变量注入，不能存入 Nacos 或配置文件。

### 3. 环境变量配置

在 `docker-compose.yml` 中，每个服务都已配置 `JASYPT_PASSWORD` 环境变量：

```yaml
environment:
  JASYPT_PASSWORD: ${JASYPT_PASSWORD}
```

在 `.env` 文件中添加：

```bash
# Jasypt 加密密钥。必须使用强密码，建议 32 位随机字符串。
# 生成方式: openssl rand -base64 32
JASYPT_PASSWORD=CHANGE_ME_JASYPT_PASSWORD
```

## 使用方法

### 1. 使用 JasyptEncryptor 工具类

项目提供了 `uno.acloud.common.util.JasyptEncryptor` 工具类：

```java
import uno.acloud.common.util.JasyptEncryptor;

@Service
public class MyService {

    private final JasyptEncryptor jasyptEncryptor;

    public MyService(JasyptEncryptor jasyptEncryptor) {
        this.jasyptEncryptor = jasyptEncryptor;
    }

    public void encryptConfig() {
        // 加密
        String encrypted = jasyptEncryptor.encrypt("my-secret-password");
        // 输出: ENC(encrypted_value)

        // 解密
        String decrypted = jasyptEncryptor.decrypt("ENC(encrypted_value)");
        // 输出: my-secret-password

        // 检查是否已加密
        boolean isEncrypted = jasyptEncryptor.isEncrypted("ENC(encrypted_value)");
        // 输出: true
    }
}
```

### 2. 加密敏感配置值

#### 示例：加密数据库密码

**原始配置**（明文）：
```yaml
spring:
  datasource:
    password: my-secret-password
```

**加密后配置**：
```yaml
spring:
  datasource:
    password: ENC(encrypted_value_here)
```

#### 示例：加密 Redis 密码

**原始配置**：
```yaml
spring:
  data:
    redis:
      password: my-redis-password
```

**加密后配置**：
```yaml
spring:
  data:
    redis:
      password: ENC(encrypted_redis_password)
```

### 3. 在 Nacos 控制台编辑加密配置

#### 注意事项

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

#### 编辑流程

1. **获取加密值**
   ```java
   // 在应用中注入 JasyptEncryptor
   String encrypted = jasyptEncryptor.encrypt("new-password");
   System.out.println(encrypted);
   // 输出: ENC(new_encrypted_value)
   ```

2. **更新 Nacos 配置**
   - 登录 Nacos 控制台
   - 找到对应的配置文件
   - 将 `password` 字段替换为 `ENC(new_encrypted_value)`
   - 发布配置

3. **验证配置**
   - 重启服务或等待配置刷新
   - 检查服务是否正常启动
   - 验证功能是否正常

## 最佳实践

### 1. 密钥管理

- **JASYPT_PASSWORD** 必须使用强密码（建议 32 位随机字符串）
- 不同环境（开发、测试、生产）使用不同的密钥
- 密钥必须安全存储，不能提交到代码仓库
- 定期轮换密钥（需要重新加密所有配置）

### 2. 生成密钥

```bash
# 生成 32 位随机密钥
openssl rand -base64 32

# 或使用 Python
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### 3. 配置建议

**开发环境**：
- 可以使用简单的密钥，方便调试
- 建议通过环境变量注入，避免硬编码

**生产环境**：
- 必须使用强密码
- 通过密钥管理服务（如 AWS Secrets Manager、阿里云 KMS）注入
- 定期轮换密钥

### 4. 敏感配置清单

以下配置建议加密：

- 数据库密码：`spring.datasource.password`
- Redis 密码：`spring.data.redis.password`
- RabbitMQ 密码：`spring.rabbitmq.password`
- OSS AccessKey：`app.oss.access-key-secret`
- SMTP 密码：`email.password`
- Nacos 密码：`spring.cloud.nacos.password`
- Sa-Token 密钥：`sa-token.secret-key`（如果有）

## 故障排查

### 1. 解密失败

**错误信息**：
```
DecryptionException: Unable to decrypt: ENC(encrypted_value)
```

**可能原因**：
- JASYPT_PASSWORD 环境变量未设置或错误
- 加密时使用的密钥与当前不一致
- 加密值被手动修改

**解决方案**：
1. 检查环境变量是否正确设置
2. 确认加密时使用的密钥
3. 重新加密配置值

### 2. 配置格式错误

**错误信息**：
```
IllegalArgumentException: Invalid ENC format
```

**可能原因**：
- `ENC(...)` 格式不正确
- 括号不匹配
- 包含非法字符

**解决方案**：
1. 检查 `ENC(...)` 格式
2. 重新加密配置值

### 3. 性能问题

**现象**：
- 应用启动缓慢
- 配置加载超时

**可能原因**：
- 加密算法过于复杂
- 加密值过多

**解决方案**：
1. 只加密真正敏感的配置
2. 考虑使用更简单的算法（如 PBEWithMD5AndDES）

## 示例

### 完整示例：加密数据库密码

1. **设置环境变量**：
   ```bash
   export JASYPT_PASSWORD="your-strong-password-here"
   ```

2. **获取加密值**：
   ```java
   // 在 Spring Boot 应用中
   @Autowired
   private JasyptEncryptor jasyptEncryptor;

   public void printEncryptedPassword() {
       String encrypted = jasyptEncryptor.encrypt("my-database-password");
       System.out.println("加密后的密码: " + encrypted);
   }
   ```

3. **更新配置**：
   ```yaml
   spring:
     datasource:
       password: ENC(生成的加密值)
   ```

4. **验证**：
   - 重启服务
   - 检查数据库连接是否正常

### 批量加密示例

```java
public void encryptAllSensitiveConfigs() {
    // 数据库密码
    String dbPassword = jasyptEncryptor.encrypt("db-password");
    System.out.println("DB Password: " + dbPassword);

    // Redis 密码
    String redisPassword = jasyptEncryptor.encrypt("redis-password");
    System.out.println("Redis Password: " + redisPassword);

    // OSS AccessKey
    String ossSecret = jasyptEncryptor.encrypt("oss-access-key-secret");
    System.out.println("OSS Secret: " + ossSecret);
}
```

## 参考资料

- [Jasypt 官方文档](http://www.jasypt.org/)
- [jasypt-spring-boot GitHub](https://github.com/ulisesbocchio/jasypt-spring-boot)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)

## 更新日志

- **2026-06-15**: 初始版本，配置 Jasypt 加密器
