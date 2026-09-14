# Jasypt 密钥管理文档

本文档说明 Jasypt 在项目中的密钥管理策略、加密操作流程和密钥轮换方案。

> ## 0. 本轮修订摘要（2026-09-13）
>
> 修订前本文档有三处会直接导致故障的错误，均已按实测结果订正：
>
> | # | 原文 | 实际情况 |
> |---|---|---|
> | 1 | 算法为 `AES/GCM/NoPadding` | **该值在 jasypt 1.9.3 上初始化即抛异常**（`SecretKeyFactory not available`）。一旦写入任何 `ENC(...)`，服务会启动失败。已改为 `PBEWITHHMACSHA512ANDAES_256` |
> | 2 | CLI 输出形如 `ENC(encrypted_value_here)` | **CLI 输出的是裸 Base64，不带 `ENC()` 包装**，必须自己加；且输出在 `----OUTPUT---` 下一空行之后 |
> | 3 | §6.3 建议用 `jasypt.encryptor.password-list` 做零停机轮换 | **该配置项在 jasypt-spring-boot 3.0.5 中不存在**（已 `javap` 列出属性类全部字段核实）。写进 YAML 会被静默忽略，让人误以为做了零停机轮换 |
>
> 另：CLI 命令原文缺 `ivGeneratorClassName`，按它加密出的密文在运行时配置下**解不开**。
>
> ## 0.1 现状（先读这段，它决定本机制值不值得投入）
>
> - 依赖：`zxyz-common/pom.xml` 已引入 `jasypt-spring-boot-starter` **3.0.5**（内核 jasypt **1.9.3**）。
> - 主密钥注入：`docker-compose.yml` 已为**全部 10 个后端服务**注入 `JASYPT_PASSWORD`（project / im / email / share / file / team / audit / admin / user / gateway）。
> - 变量名是 **`JASYPT_PASSWORD`**，不是 jasypt 默认探测的 `JASYPT_ENCRYPTOR_PASSWORD`。这是有意的：`application-common.yml` 里显式写了 `password: ${JASYPT_PASSWORD}`，比依赖框架的环境变量自动探测更清晰，也不必多维护一个名字。
> - **当前仓库与 Nacos 配置里 `ENC()` 密文数量为 0** —— 所有敏感值仍是 `${ENV}` 透传。也就是说：**这套加密机制从未真正被启用过**，上面那个算法缺陷也从未被任何一次启动验证暴露。
>
> ## 0.2 一句判断：ENC() 的价值边界
>
> 对**已经由 `.env` 经环境变量注入**的值（如 `${TEAM_DATASOURCE_PASSWORD}`），把它改成 `ENC(...)` **不带来实质安全提升**：密文仍存在 Nacos 里，而解它的主密钥就在同一台机器的 `.env` 里。**真正的安全边界是主机访问权限，不是「配置容器里存明文还是密文」。**
>
> `ENC()` 真正有收益的场景是：
> 1. 值**必须**落在配置文件中（无法走环境变量），而该文件会被**导出、备份或分发**；
> 2. 需要把配置模板（含密文）交给第三方，或提交到版本库的非密钥位置。
>
> 因此本项的正确定位是**纵深防御的一层**，而非替代 `.env`。**若做不到「主密钥与密文分离存储」，ENC 化的收益有限** —— 投入前请先确认这一点。
>
> ## 0.3 时机提示：改主密钥的最佳窗口就是现在
>
> 轮换主密钥必须重加密**全部** `ENC()` 值。当前 `ENC()` 数量为 0 ⇒ **现在轮换主密钥零成本**（只需改 `.env` 里的一个值）。
> 一旦开始 ENC 化，每次轮换都要重加密并重新发布全部相关配置。
> ⇒ 如果对当前 `.env` 里的 `JASYPT_PASSWORD` 有疑虑（它是否曾进入过版本库/日志/共享文档），**请在开始 ENC 化之前先轮换它**。

## 1. 概述

Jasypt (Java Simplified Encryption) 是本项目用于加密配置文件中敏感信息的方案。通过 `jasypt-spring-boot-starter`，项目可以透明地加密和解密数据库密码、Redis 密码、API 密钥等敏感配置。

**核心特性**：
- 加密后的值格式为 `ENC(ciphertext)`
- Spring Boot 启动时自动解密，业务代码无需改动
- 可与 Nacos 配置中心配合（敏感值加密后写入 Nacos）

## 2. 加密算法

**配置位置**：`ZXYZdatabaseBack/zxyz-common/src/main/resources/application-common.yml`

```yaml
jasypt:
  encryptor:
    algorithm: PBEWITHHMACSHA512ANDAES_256
    iv-generator-classname: org.jasypt.iv.RandomIvGenerator
    password: ${JASYPT_PASSWORD}
```

| 参数 | 值 | 说明 |
|---|---|---|
| 算法 | `PBEWITHHMACSHA512ANDAES_256` | PBE：HMAC-SHA512 派生密钥 + AES-256 加密。jasypt-spring-boot 官方推荐值 |
| IV 生成器 | `org.jasypt.iv.RandomIvGenerator` | 每次加密生成随机 IV，**必需**（不能省，否则解密失败） |
| 迭代次数 | 1000（默认） | key 派生迭代次数；提高会增加暴力破解成本，也会略增启动耗时 |
| 输出编码 | base64（默认） | `ENC()` 括号内的形式 |
| 密钥 | `JASYPT_PASSWORD` | 通过环境变量注入，见 §3 |

### 2.1 ⚠️ 为什么 `algorithm` 必须是 PBE 算法名

jasypt 的 `StandardPBEByteEncryptor` 用 **`SecretKeyFactory.getInstance(algorithm)`** 获取算法 —— 它把 `algorithm` 当 **PBE 算法名**，而**不是** `Cipher` 的转换名。

原配置写的 `AES/GCM/NoPadding` 是 `Cipher` 的转换名，`SecretKeyFactory` 里没有这个名字，于是直接抛：

```
org.jasypt.exceptions.EncryptionInitializationException:
  java.security.NoSuchAlgorithmException: AES/GCM/NoPadding SecretKeyFactory not available
    at org.jasypt.encryption.pbe.StandardPBEByteEncryptor.initialize(...)
```

**影响**：只要任一配置项是 `ENC(...)`，该服务就**启动失败**。由于此前零 `ENC()` 值，这个缺陷一直潜伏；它会在「开始 ENC 化」的那一刻才炸，而且错误信息看起来像「主密钥配错了」，很容易查错方向。

### 2.2 候选算法实测结果（2026-09-13，jasypt 1.9.3 / Eclipse Temurin JDK 17.0.20）

运行时基础镜像为 `eclipse-temurin:17.0.14_7-jre-alpine`，Java 9+ 默认启用无限强度策略（实测 `Cipher.getMaxAllowedKeyLength("AES")` = 2147483647），AES-256 可用。

| algorithm | 加解密往返 | 同明文两次密文不同 | 错误主密钥被拒 | 结论 |
|---|---|---|---|---|
| `PBEWITHHMACSHA512ANDAES_256` | OK | OK | OK | **采用** |
| `PBEWITHHMACSHA256ANDAES_256` | OK | OK | OK | 可用 |
| `PBEWITHHMACSHA512ANDAES_128` | OK | OK | OK | 可用（强度更低） |
| `PBEWITHMD5ANDDES` | OK | OK | OK | 可用但**弱**（MD5+DES），不要用 |
| `AES/GCM/NoPadding` | — | — | — | **不可用**（见 §2.1） |

复验方式：用容器跑一段最小程序，逐项打印上表四个指标（`StandardPBEStringEncryptor` + `SimpleStringPBEConfig`，参数与 `application-common.yml` 逐项对齐）。

## 3. 密钥管理

### 3.1 主密钥的托管位置

**主密钥只存于服务器 `.env`**，由 compose 注入容器环境变量：

```yaml
# docker-compose.yml（10 个后端服务每个都有这一段）
environment:
  JASYPT_PASSWORD: ${JASYPT_PASSWORD}
```

```bash
# /www/zxyz/.env（未纳入版本控制）
JASYPT_PASSWORD=<强随机串>
```

链路：`.env` → compose 插值 → 容器环境变量 `JASYPT_PASSWORD` → `application-common.yml` 的 `jasypt.encryptor.password`。

**为什么不放 Nacos**：Nacos 里存主密钥，等于把「锁」和「钥匙」放在同一个被广泛读取的地方，ENC 化立刻失去意义。

**关于 `.env` 的安全性**：`.env` 已在 `.dockerignore` 与 Gitleaks 视野内；仓库里被追踪的只有 `.env.example`（占位符 `CHANGE_ME_JASYPT_PASSWORD`）。真实值只在服务器与 CI Secret 中。

**托管位置已定（2026-09-14）：继续留在服务端 `.env`（权限 `600`），不引入云 KMS / Vault。**

理由是**让保护强度与实际资产匹配**：
- 当前 `ENC()` 密文为 **0 处**（§0.1）⇒ 主密钥的实际保护对象只有「验证码摘要 pepper」一项；
- 引入 KMS / Vault 会带来一整套**新依赖、新网络打通、新故障面**（以及可选云成本），而收益趋近于零；
- 「上最贵的方案」不等于「有远见」。**判据**：等 `ENC()` 化真正堆积了密文（见 §5.0），
  且这些密文**会离开本机**（导出/备份/交给第三方）时，再重新评估 KMS。

### 3.2 密钥生成

```bash
# 推荐：OpenSSL（32 字节随机）
openssl rand -base64 32

# 备选：Python
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

**要求**：≥32 字节；不同环境（dev / prod）使用不同密钥；不要复用其它系统的密钥。

### 3.3 环境隔离

| 环境 | 存储位置 | 说明 |
|---|---|---|
| 开发 | IDE 环境变量或本地 `.env` | 可用简单值，方便调试 |
| 生产 | 服务器 `.env` | 不与开发共用；不入版本控制 |

## 4. 加密操作

### 4.1 用官方 CLI 加密（推荐，已实测）

```bash
JASYPT_JAR=/path/to/jasypt-1.9.3.jar

java -cp "$JASYPT_JAR" org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="$PLAINTEXT" \
  password="$JASYPT_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator \
  stringOutputType=base64
```

输出形如（**注意 `----OUTPUT---` 之后先是空行，才是密文**）：

```
----ENVIRONMENT-----------------

Runtime: Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20+8

----ARGUMENTS-------------------

input: my-secret-password
password: ...
stringOutputType: base64
ivGeneratorClassName: org.jasypt.iv.RandomIvGenerator
algorithm: PBEWITHHMACSHA512ANDAES_256

----OUTPUT----------------------

AdB5pVL+bnSoWvoGiGBWJ93Z9MZj+r3AAIkyIfnvPtxiANmXmT+lxrOLhDS086LBRa9y3x4v94yACFnvj0DCig==
```

⚠️ **三个容易踩的点**：
1. 输出是**裸 Base64**，**不带 `ENC()` 包装** —— 写进配置时要自己加：`ENC(<那一行>)`。
2. 上面这条命令**必须带 `ivGeneratorClassName`**。少了它，jasypt 会退回 `NoIvGenerator`，加密时与运行时配置不一致 ⇒ 密文**解不开**。
3. `input=` 的值用双引号时，`$` 与反引号**仍会被 shell 展开**。口令含这类字符请改用单引号：`input='p@$$w0rd'`。

**一行取出密文的写法**（避免手抄出错）：

```bash
CIPHER=$(java -cp "$JASYPT_JAR" org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="$PLAINTEXT" password="$JASYPT_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator stringOutputType=base64 2>/dev/null \
  | grep -E '^[A-Za-z0-9+/=]{20,}$' | tail -1)
echo "ENC($CIPHER)"
```

**生成后必须自检**（这一步能挡住「密钥写错」「算法不一致」「手抄错字符」三类事故）：

```bash
java -cp "$JASYPT_JAR" org.jasypt.intf.cli.JasyptPBEStringDecryptionCLI \
  input="$CIPHER" password="$JASYPT_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator stringOutputType=base64
# 输出的明文必须与 input 完全一致
```

**服务器上没有 JDK 时**，用容器跑（jar 在 Maven 卷里）：

```bash
docker run --rm \
  -e JASYPT_PASSWORD \
  -v zxyz-m2:/root/.m2:ro \
  maven:3.9-eclipse-temurin-17 \
  java -cp /root/.m2/repository/org/jasypt/jasypt/1.9.3/jasypt-1.9.3.jar \
  org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="$PLAINTEXT" password="$JASYPT_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator stringOutputType=base64
```

⚠️ 主密钥通过 `-e JASYPT_PASSWORD`（从环境带入）传给容器，**不要**写成 `-e JASYPT_PASSWORD=真实值` —— 那会进 shell 历史。

### 4.2 用 `JasyptEncryptor` 工具类（应用内）

`uno.acloud.common.util.JasyptEncryptor`（`zxyz-common`，`@Component`）封装了 starter 自动配置的 `StringEncryptor`：

```java
private final JasyptEncryptor jasyptEncryptor;

String encrypted = jasyptEncryptor.encrypt("my-secret");  // → "ENC(base64...)"（已带包装）
String plain     = jasyptEncryptor.decrypt(encrypted);    // → "my-secret"
boolean yes      = jasyptEncryptor.isEncrypted(encrypted); // → true
```

与 CLI 的差别：**`encrypt()` 的返回值已自带 `ENC()` 包装**，CLI 需要自己加。

⚠️ **不要为生成密文而临时暴露 HTTP 接口**：那等于在业务进程里对外提供了「用主密钥加密任意内容」的能力。生成密文是**运维动作**，应在独立的 CLI/容器环境执行。

### 4.3 加密后的格式

格式为 `ENC(ciphertext)`，`ciphertext` 是 Base64。

**要求**：以 `ENC(` 开头、`)` 结尾；括号内不改动；前后不加空格；`ENC(` 与 `)` 之间不能换行。

## 5. 在 Nacos 配置中使用

### 5.0 约定：新增机密值一律 ENC 化（「向前 ENC 化」，2026-09-14 定）

本项目**不做存量追改**（当前 `ENC()` 数为 0，无存量可改、追改零收益），
但把下面这条定为**新增机密值的默认写法**：

> **任何新引入的、必须落在配置文件里的机密值，一律以 `ENC(...)` 形式写入，不允许写明文。**

| 场景 | 是否 ENC 化 | 原因 |
|---|---|---|
| 写进 `nacos-config/*.yml`、`application*.yml` 等**会被导出 / 备份 / 分发**的配置文件 | ✅ **必须** | 这正是 `ENC()` 有收益的场景（§0.2）——防止明文密钥堆积到被广泛读取的位置 |
| 经 `.env` → compose 环境变量注入（如 `${TEAM_DATASOURCE_PASSWORD}`） | ❌ 不需要 | 密文与主密钥在同一台机器上，**改成 ENC 不带来实质提升**，只增加一次重加密与发布成本 |
| `JASYPT_PASSWORD` 本身 | 🚫 **永远不得放进 Nacos** | 锁与钥匙必须分离（§3.1） |

**为什么是「向前」而不是「全量」**：`ENC()` 的价值是**防止未来的明文堆积**；
存量追改不产生安全增量，只产生重加密与发布成本。把写法固定下来，就能保证新增项不再堆积明文。

> 配套护栏：`nacos-config/import.sh` 已内置「字面量机密」拦截（值必须以 `${` 或 `ENC(` 开头，
> 否则中止发布）⇒ 这条约定**不是靠自觉**，而是有自动门禁兜底（见 `nacos-config/README.md` §5）。

### 5.1 迁移方式（保留 `${ENV}` 回退一版）

**采用逐个配置项迁移**，而不是一次性全改：每项先改成 `ENC(...)`，保留原 `${ENV}` 形式在注释里一版，观察一次发布周期后再删。

```yaml
spring:
  datasource:
    # 迁移前: password: ${TEAM_DATASOURCE_PASSWORD}
    password: ENC(xxxxxxxx)
```

`nacos-config/import.sh` 会拦截「新增的明文机密键」：值必须以 `${`（环境变量引用）或 `ENC(`（Jasypt 密文）开头，否则**中止发布**。所以迁移过程不会因手误把明文推到 Nacos。

⚠️ **生效时机取决于 `refreshEnabled`**：`zxyz-dynamic.yml` 入库即热更新；其余 11 份（`refreshEnabled=false`）需**重启对应服务**才接管。
导入**不必手工**：改 `nacos-config/**` 并 push 后，CI 作业 `nacos-import` 会自动导入并逐份回读校验（详见 `nacos-config/README.md`）。应急时可在服务器手工执行：
`cd /www/zxyz-repo/nacos-config && bash ./import.sh "" 127.0.0.1:8848 127.0.0.1:18081`。

### 5.2 自动解密原理

`jasypt-spring-boot-starter` 通过 `EnvironmentPostProcessor` 在启动早期包装所有 `PropertySource`，读取配置时识别 `ENC(...)` 并解密。Nacos 配置的加载同样基于 `EnvironmentPostProcessor`，两者的执行顺序由 `@AutoConfiguration` 的 `before`/`after` 决定。

⚠️ **这是一个理论上的风险点**：若 Nacos 属性源在 Jasypt 包装**之后**才注册，Nacos 里的 `ENC()` 值可能不被解密。**首次把某个 `ENC()` 值写入 Nacos 时，务必确认对应服务真的解开了**（看启动日志有无 `EncryptionOperationNotPossibleException` / 配置注入是否拿到明文），不要假设顺序一定正确。

### 5.3 编辑注意事项

1. **不要手改 `ENC(...)` 内容** —— 密文任何一位变化都会解密失败（GCM/AES 有完整性校验）。
2. **换主密钥 = 全部重加密**（见 §6）。
3. **环境变量优先于 Nacos**：同一键同时在 `.env` 与 Nacos 中时以环境变量为准。若某个键想用 Nacos 的 `ENC()` 值，就要先把它从 `.env` / compose 的 `environment:` 中移除，否则你改 Nacos 不会有任何效果。

## 6. 密钥轮换流程

### 6.1 触发场景（口径：**必要时轮换，不设固定周期**）

**本项目的口径是「必要时轮换」**（2026-09-14 定）。触发条件：

1. **密钥泄露** —— 进入过版本库 / 日志 / 共享文档 / 第三方环境；
2. **人员变动** —— 曾接触过服务器 `.env` 的人离开；
3. **重大合规要求** —— 安全审计明确要求；
4. **主机被入侵或疑似被入侵**。

> **为什么不设「每 90 天」的固定周期**：当前 `ENC()` 数为 0（§0.1），主密钥的实际保护对象只有
> 「验证码摘要 pepper」一项，而它就在**同一台机器**的 `.env` 里 ——
> 定期轮换**不改变任何攻击面的可达性**（能读 `.env` 的人本来就能拿到钥匙与锁），
> 却每次都会**作废全部在途验证码**（见下方警告）并需要重建全部 10 个后端服务。
> ⇒ 固定周期在这里是**「有成本、无收益」**的动作；把**触发条件写清楚**才是真正缺失的那一块。
> ⚠️ 该判据会随 `ENC()` 化推进而改变 —— 一旦密文数量上升（§5.0），就应重新评估是否恢复固定周期。

> ⚠️ **轮换前必读：主密钥不只是 jasypt 的钥匙，它还是验证码摘要的 pepper 回退源。**
> `nacos-config/zxyz-user-service.yml` 与 `nacos-config/zxyz-email-service.yml` 都写了
> `verify-code-pepper: ${VERIFY_CODE_PEPPER:${JASYPT_PASSWORD:}}`，而生产**未注入** `VERIFY_CODE_PEPPER`
> （compose 用显式 `environment:` 而非 `env_file`，该键根本不进容器）⇒ 实际 pepper 就是主密钥。
> 后果：轮换主密钥会**同时换掉验证码摘要口径** ——
> ① 已发出的验证码**立即全部失效**，处于登录/注册流程中的用户须重新获取；
> ② `macKey` 在 `VerifyCodeHasher` 构造器里固化、未加 `@RefreshScope`，必须**重启服务**才生效（本流程本来就要重启）。
> ⇒ 轮换应安排在低峰/维护窗口，并提前知会「验证码可能需重新获取」。

> **当前线上实况（2026-09-14 已轮换，全程未打印明文）**：`JASYPT_PASSWORD` 现为 **44 字符**（`openssl rand -base64 32` 的
> 全量输出），满足 §3.2 的「≥32 字节」口径。轮换前的旧值是 **24 字符** —— 那是 `init-secrets.sh` 的 `gen_val`
> 「通用强密码」分支产物（`${raw//[+/=]/}` 后截 24 字符）⇒ **自动生成的默认值偏短，在意强度时应显式覆盖**。
> 历次密钥均在 `/www/zxyz/init-secrets.log`（权限 `600`）留痕：首次自动生成为 `GENERATED ...`，人工轮换写 `ROTATED ...`。
> 轮换同时落 `.env.bak-<日期>-jasypt`（权限 `600`）作回滚点；但本项目 `ENC()` 数为 0、**全库 245 个文本列扫描 0 命中**
> ⇒ 该备份**没有数据层回滚价值**，确认无异常后可直接删。
> ⚠️ **一次轮换必须重建「全部」消费主密钥的服务**：验证码摘要由 user-service 与 email-service **各自**计算
> （两处 pepper 都回退到主密钥），只重启一半会让两端口径不一致 ⇒ 所有验证码都校验不过。

### 6.2 步骤

**步骤 0：评估影响面（当前 = 零，见 §0.3）**

```bash
# 统计仓库里有多少 ENC() 值（决定重加密工作量）
grep -rn "ENC(" nacos-config/ ZXYZdatabaseBack/*/src/main/resources/ | wc -l
```

**步骤 1：生成新主密钥并备份现状**

```bash
NEW_KEY=$(openssl rand -base64 32)
# 备份当前 .env 与 Nacos 配置（回滚用）
cp /www/zxyz/.env /www/zxyz/.env.bak-$(date +%F)
```

**步骤 2：用新密钥重新加密所有 `ENC()` 值**

对每个 `ENC()` 值执行 §4.1 的加密 + **解密自检**（自检不可省 —— 它是唯一能在改 `.env` 之前发现「密钥/算法不一致」的手段）。建议写成循环脚本，不要手抄单个值。

**步骤 3：更新 Nacos 配置**

替换所有 `ENC(...)` 为新值 → 发布 → 在服务器执行 `import.sh`。

**步骤 4：更新 `.env` 的主密钥**

```bash
vi /www/zxyz/.env     # JASYPT_PASSWORD=<新密钥>
```

⚠️ **顺序很重要**：必须**先**把 Nacos 里的密文换成新密钥加密的、**再**改 `.env`。反过来会让服务在中间态用新密钥解旧密文 ⇒ 启动失败。

**步骤 5：重建并重启服务**

```bash
cd /www/zxyz && docker compose up -d <受影响的 10 个后端服务>
```
⚠️ 注意用 `docker compose up -d`（会重新读取 `.env` 并重建容器），**不是** `docker compose restart` —— `restart` 复用已有容器的环境变量，**主密钥不会更新**。

**步骤 6：验证**

```bash
docker compose ps
docker compose logs --tail=100 zxyz-user-service | grep -i "encrypt\|decrypt\|password"
curl -fsS http://localhost:18083/actuator/health
```

**回滚**：恢复 `.env.bak-*` 与 Nacos 配置 → `docker compose up -d` 相关服务。

### 6.3 关于「零停机轮换」

> ⚠️ **本文档旧版建议的 `jasypt.encryptor.password-list` 配置项不存在。**
> 已用 `javap` 列出 jasypt-spring-boot 3.0.5 的 `JasyptEncryptorConfigurationProperties` 全部字段核实，可用的只有：
> `password`、`algorithm`、`keyObtentionIterations`、`poolSize`、`providerName`、`providerClassName`、`saltGeneratorClassname`、`ivGeneratorClassname`、`stringOutputType`、`privateKeyString/Location/Format`、`publicKeyString/Location/Format`、`gcmSecretKeyString/Location/Password/Salt/Algorithm`、`property.*`、`bean`、`proxyPropertySources`、`skipPropertySources`、`refreshedEventClasses`。
> 写进 YAML 会被 Spring Boot **静默忽略** —— 不会报错，只会让人误以为已经实现零停机轮换，而在真正轮换时因旧密文解不开而全站起不来。

**本项目的做法**：低峰期一次性重加密 + 重建重启（§6.2）。

之所以不需要零停机方案，是因为本项目的形态很特殊：**10 个后端服务共用同一个主密钥、同一批 Nacos 配置**，轮换天然是一次整体动作，不存在「部分服务已换密钥、部分还没换」的长期共存期。真正需要「新旧密钥并存」的场景（多集群分批滚动升级）在本项目不成立。

**如果将来确实需要**：要在**代码层**实现 —— 自定义 `EncryptablePropertyResolver`（先试新密钥、失败再试旧密钥）+ 同时注册两个 `StringEncryptor` bean，并明确旧密钥的淘汰期限。这不在当前方案内。

## 7. 注意事项与故障排查

### 7.1 安全要求

- 不在代码中硬编码密钥
- 不把 `.env` 提交到 Git
- 不在日志中打印明文密钥
- **不在 Nacos 中存放 `JASYPT_PASSWORD`**（锁与钥匙分离）
- 不在命令行参数里写密钥明文（用环境变量带入）

### 7.2 故障排查

**问题 1：`EncryptionInitializationException: ... SecretKeyFactory not available`**

- 原因：`algorithm` 不是 PBE 算法名（例如写成了 `AES/GCM/NoPadding` 这类 Cipher 转换名）
- 解决：见 §2.1 / §2.2，改回 `PBEWITHHMACSHA512ANDAES_256`

**问题 2：`EncryptionOperationNotPossibleException`**

- 原因（按概率）：① `JASYPT_PASSWORD` 未设置或与加密时不一致；② 密文被手工改动；③ 加密时**没带 `ivGeneratorClassName`**（§4.1 第 2 点）；④ 加密与运行时的 `algorithm` 不一致
- 解决：用 §4.1 的解密自检命令，在当前主密钥下复现；能解开说明是部署侧取值问题，解不开说明密钥或算法不匹配

**问题 3：服务启动后某个配置项仍是 `ENC(...)` 字面值**

- 原因：该属性源在 Jasypt 包装之后才注册（见 §5.2），或该值来自 `@Value` 之外的直接读取（如 `System.getenv`）
- 解决：核对属性的来源；确认它确实经过了 Spring 的 `Environment` 取值路径

**问题 4：改了 Nacos 里的 `ENC()` 值但服务行为没变**

- 原因：同一键在 `.env` / compose 的 `environment:` 里也有值，**环境变量优先级更高**（见 §5.3 第 3 条）
- 解决：先移除环境变量侧的该键，或改环境变量侧

## 8. 参考资料

- [Jasypt 官方文档](http://www.jasypt.org/)
- [jasypt-spring-boot GitHub](https://github.com/ulisesbocchio/jasypt-spring-boot)
- [Java 标准算法名（SecretKeyFactory 与 Cipher 是两套名字）](https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)

## 更新日志

- **2026-09-14**: 新增 §5.0「向前 ENC 化」约定（新增机密值一律 `ENC()`，存量不追改）；§3.1 补「托管位置已定」
  （留在服务端 `.env`，不引入 KMS，并写明判据）；§6.1 由「建议每 90 天」改为「**必要时轮换**」并列出 4 条触发条件与不设固定周期的理由。
- **2026-09-13**: 订正三处会导致故障的错误 —— 算法名（`AES/GCM/NoPadding` → `PBEWITHHMACSHA512ANDAES_256`，原值在 jasypt 1.9.3 上初始化即失败）、CLI 输出格式（裸 Base64，不含 `ENC()` 包装）、以及在 jasypt-spring-boot 3.0.5 中不存在的 `password-list` 配置项；补充实测证据（4 个候选算法 × 4 项指标）、可复制的生成/自检命令、容器内执行方式、`ENC()` 的价值边界（§0.2）与「现在就是改主密钥的最佳窗口」（§0.3）；新增 3 条故障排查。同步修正 11 个 `nacos-config/*.yml` 头部的加密命令注释。
- **2026-06-15**: 初始版本。
