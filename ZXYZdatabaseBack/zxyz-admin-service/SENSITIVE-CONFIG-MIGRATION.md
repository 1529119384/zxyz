# sys_config 敏感键加密存储迁移说明（ISSUE/54 R2）

> 落点：`zxyz-admin-service`，改动文件 `ConfigService.java` / `ConfigAdminController.java` / `SysConfigMapper.java`（参数名自文档化，SQL 未变）。
> 性质：**代码层读写行为变更，无需数据库迁移脚本、无停机要求**——本说明面向运维核对与存量数据认领。

## 一、变更前后的行为对比

| 场景 | 变更前 | 变更后 |
|---|---|---|
| `PUT /configs/{敏感键}`（如 `smtp.password`） | 明文原样落库 | 强制加密为 `ENC(ciphertext)` 落库（Jasypt，与配置面同密钥） |
| `GET /configs/{敏感键}` | 库里存什么回什么（明文可被原样读回） | 恒回掩码 `ENC:****` |
| `GET /configs`（listAll） | 敏感键明文回 `configValue` | 敏感键 `configValue` 恒回 `ENC:****` |
| `GET /configs/{非敏感键}` / listAll 非敏感行 | 原值 | **行为不变** |
| 审计表 `sys_config_audit` | 敏感值掩码 `ENC:****`（P3-3 已有） | 不变（掩码口径与读写侧同源） |
| 再次 update 同一敏感键 | 覆盖明文 | 识别已有 `ENC(...)` 前缀则原样落库，**不双重加密**；明文则先加密 |

## 二、敏感键判定清单（isSensitive）

子串匹配（大小写不敏感），键名含以下任一子串即判定为敏感：

```
secret / password / pwd / token / accesskey / access-key
secretkey / secret-key / oss / smtp / jasypt / encrypt
```

该清单同时服务三处，**单一来源**（`ConfigService.SENSITIVE_MARKERS`）：落库加密、读侧掩码（GET/listAll）、审计掩码。
配置化预留：当前为代码内置；若需按环境增删，应提为 `@ConfigurationProperties` 并保持子串匹配语义与默认值兼容，三处自动同步生效。

## 三、存量数据的迁移语义（无需执行任何 SQL）

- **读取侧不依赖存储形态**：`get()` 对敏感键无条件掩码，因此存量明文敏感值同样被掩住，不会经 API 泄露。
- **写入侧自然迁移**：存量明文值在**下一次对该键执行 update** 时自动转为 `ENC(...)` 落库；不主动触碰则保持原样（读侧已掩码，无泄露面）。
- **幂等**：已是 `ENC(...)` 格式的值再次 update 不会被二次加密。

> 不提供批量迁移脚本的原因：sys_config 当前种子数据（V1/V2/V3）无敏感键、无 `ENC()` 密文（54 号审计实证「当前 ENC() 密文数 0」），
> 需要迁移的只有运维历史上手工写入的 SMTP/OSS 类键，逐键 update 一次即可，批量脚本收益为负。

## 四、消费方影响与验证

- **本服务内消费**：`ConfigService.get()` 此前只被 `ConfigAdminController.getByKey` 消费（admin UI 展示），掩码即正确行为；**sys_config 不是密钥消费源**——真正的密钥（SMTP/OSS/内部 token）一律走 Nacos（`zxyz-static.yml`）或 compose 环境变量，Jasypt 配置面解密链路不受影响。
- **跨服务 HTTP 消费**：`ConfigGetter`（`zxyz-common`）已废弃且其自动配置默认关闭（`app.config-client.enabled` 未开启），无生产调用方；若将来启用，读到的敏感键值将是掩码 `ENC:****`——属预期防护行为，不是缺陷。
- **前端 ConfigAdmin.vue**：已按 `row.isEncrypted` 展示 `******`，掩码值回填编辑框后若原样提交会被拒绝（提示输入新明文），不会把掩码存成真值。
- **验证方法**：部署后对任一敏感键执行 `GET /api/admin/configs/{key}`（SYSTEM_ADMIN 会话），响应应为 `ENC:****`；对它 update 一次新明文后查库（`SELECT config_value FROM zxyz_config.sys_config WHERE config_key='...'`），应为 `ENC(...)` 前缀。

## 五、回滚

回滚本次代码即恢复旧行为（明文透传/明文落库）。已加密的存量值在旧代码下 `get()` 会走既有 `decrypt` 分支正常解密（与 Nacos 配置面同一 Jasypt 设施），不会损坏数据。
