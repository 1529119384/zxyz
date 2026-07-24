# 未完成项目核对报告（2026-07-24 第四轮）

> 核对原则：**以代码为准**。所有结论基于对源码、配置、迁移脚本的逐行核对。
> 本文档只列出**未完成**或**部分完成**的项目；已完全落地的项目不在本文范围内。
>
> 本轮相比第三轮的变化：用户已修复 6 项（11-D、11-E、13-A、13-BUG-1、13-BUG-2、CODEX-B），但核实过程中**新发现 2 个致命缺陷**（directUpload/directDownload 布尔值反转），导致默认部署的上传与本地存储的下载两条链路失效。

**第五轮（2026-07-24 晚）：全部 8 项剩余任务已完成。**

| ISSUE | 完成度 | 完全完成 | 部分完成 | 完全未做 | 本轮变化 |
|---|---|---|---|---|---|
| #11 多存储方案 | **~100%** | 24 | 0 | 0 | +致命#1+#2 修复 + 11-F JSDoc 完成；11-A/B/C 因修复后端布尔值自动恢复 |
| #12 CI/CD 优化 | **70%** | 7 | 0 | 3 | +12-C docker-compose.dev.yml + dev-up.sh 完成；12-A/12-B 仍需外部资源 |
| #13 热配置迁移 | **100%** | 18 消费方 + 3 基础 | 0 | 0 | +13-BUG-3 键名修复 + 13-B/C 死键/死代码清理完成 |
| CODEX 审核未完项 | **100%** | 9 | 0 | 0 | +CODEX-A 枚举迁移 87 处完成 |

---

## 一、整体状态总表

| ISSUE | 完成度 | 完全完成 | 部分完成 | 完全未做 | 本轮变化 |
|---|---|---|---|---|---|
| #11 多存储方案 | **~90%** | 23 | 3 | 1 | +11-D +11-E 完成；3 项被新发现#1 击穿降为部分完成 |
| #12 CI/CD 优化 | **70%** | 7 | 0 | 3 | 无变化（均需外部资源） |
| #13 热配置迁移 | **~95%** | 18 消费方 + 3 基础 | 1 | 1（死键） | +13-A +13-BUG-1 +13-BUG-2 完成；13-BUG-3 仍残留 |
| CODEX 审核未完项 | **~95%** | 8 | 0 | 1 | +CODEX-B 完成；CODEX-A 仍未迁移 |

**第五轮新完成：8 项** ｜ **剩余未完成：2 项**（12-A 自托管 Runner、12-B 阿里云 ACR，均需外部资源）

---

## 二、本轮新完成项（6 项 — 确认已落地）

| 编号 | 项 | 证据 |
|---|---|---|
| ~~11-D~~ | LocalDisk `directUpload` 已改为 `true` | `LocalDiskStorageProvider.java:71` 第 8 参数 `true`；前端 `upload.js:45` `directUpload===true → uploadToBackend` POST → 后端 `FileUploadService:128` `!supportsPresignedUpload()` → `receiveUpload` 写本地磁盘 ✅ |
| ~~11-E~~ | Gateway 路由顺序已调整 | `zxyz-gateway/application.yml:126-129` storage-providers 路由在 `admin-service` catch-all（L132-135）**之前** ✅ |
| ~~13-A~~ | GetSignUrl 已移除 BLOCKED_EXTENSIONS | `GetSignUrl.java` 全 219 行无 BLOCKED_EXTENSIONS；拦截已迁至 `FileUploadService.java:108` + `AliyunOssStorageProvider.java:56`，均接 `configGetter.getJsonSet("app.file.upload.blocked-extensions", ...)` ✅ |
| ~~13-BUG-1~~ | FileCopyService 键名已修复 | `FileCopyService.java:59` 读 `app.file.copy.max-nodes-per-tx` ↔ `V2__hot_config_keys.sql:64` 一致 ✅ |
| ~~13-BUG-2~~ | StorageQuotaCacheService 键名已修复 | `StorageQuotaCacheService.java:61/64` 读 `app.cache.storage-usage-ttl-seconds` + `Duration.ofSeconds` ↔ `V2:35` 一致 ✅ |
| ~~CODEX-B~~ | 前端文件列表分页已全链路接线 | `useSpaceFileList.js:35-37` 状态 + L79-80 传参；`files.js:30/34-35` 透传；`FileExplorer.vue:98-108` `el-pagination` 双向绑定 + `@current-change`/`@size-change`→refresh；L376-378 切目录 reset ✅ |

---

## 三、第五轮完成项（8 项 — 全部落地）

| 编号 | 项 | 证据 |
|---|---|---|
| **致命#2** | OSS `directUpload` `true` → `false` | `AliyunOssStorageProvider.java:115` 第 4 参数 `false`；前端 `directUpload=false → uploadToOss` 预签名 PUT ✅ |
| **致命#1** | LocalDisk `directDownload` `true` → `false` | `LocalDiskStorageProvider.java:81` 第 4 参数 `false`；前端三个下载入口 `directDownload===false → /stream` 端点 ✅ |
| **附带** | `UploadInfo.java` @Schema 描述修正 | L36：`"是否直传。true=前端传到后端 multipart 端点，false=前端直传存储（预签名 PUT）"` ✅ |
| **13-BUG-3** | V2 热配置键名修正 | `V2__hot_config_keys.sql:61` `app.email.verify-code.cooldown-seconds`（3 点）与代码 L54、V1:53 一致 ✅ |
| **CODEX-A** | 3 个 ErrorCode 枚举迁移 | TeamErrorCode 58 处 + ShareErrorCode 24 处 + ProjectErrorCode 5 处 = 87 处；20 个文件；`mvn test` 通过 ✅ |
| **13-B/C** | 7 个死键清理 | 删除 `V2__hot_config_keys.sql` L38-39（`app.rest-client.*` 2 个）+ L42-46（`app.resilience.*` 5 个） ✅ |
| **11-F** | 前端 StorageProviderVO JSDoc | `api/storage.js` 补 `@typedef StorageProviderVO` + `HealthCheckResult`，三个 API 函数均加 `@param`/`@returns` 类型声明 ✅ |
| **12-C** | 本地开发 compose.dev + dev-up.sh | 新建 `docker-compose.dev.yml`（4 中间件 + 端口暴露）+ `scripts/dev-up.sh`（up/down/reset/logs） ✅ |

---

## 四、遗留项（需外部资源，非代码工作）

> 这 2 项不在原审计清单中，但直接决定 #11 多存储能否端到端工作。**优先级高于所有剩余项**。

### 致命缺陷 #1：LocalDisk `directDownload=true` 应为 `false` — 本地存储下载全链路失效

**根因**：前端三个下载入口的语义统一为「`directDownload === false` 时走 stream 端点」，但 `LocalDiskStorageProvider.generateDownloadInfo` 返回 `directDownload=true` + `downloadUrl=null`，导致前端走直下分支 → `downloadBlobByUrl(null, ...)` → 失败。

**证据**：
- `LocalDiskStorageProvider.java:76-83`：
  ```java
  return new DownloadInfo(
      providerId(),
      null,    // downloadUrl = null（本地存储无预签名 URL）
      originalName,
      true     // directDownload = true  ← 应为 false
  );
  ```
- 前端三个下载入口（均已正确实现分支，但被此值击穿）：
  - `useFileDownload.js:40` — `if (directDownload !== false)` → `true !== false` 为真 → 走直下 → `downloadBlobByUrl(null, fileName)` → 失败
  - `useShareFileDownload.js:20` — `if (directDownload === false)` → `true === false` 为假 → 跳过 stream → L27 `if (!downloadUrl) throw` → 抛错
  - `backendArchive.js:68-71` — `directDownload === false ? /stream : downloadUrl` → 取 `downloadUrl=null` → 打包失败
- 后端无反转：`FileQueryService.java:114-116` 直接把 `DownloadInfo.directDownload` 原样传给响应 VO

**修复**：`LocalDiskStorageProvider.java:81` 第 4 参数 `true` → `false`

**影响**：本地存储做默认 provider 时，单文件下载、分享下载、打包下载**全部失效**。

### 致命缺陷 #2：OSS `directUpload=true` 应为 `false` — 默认部署上传全链路失效

**根因**：OSS 是默认 provider（`AliyunOssStorageProvider.java:31` `@ConditionalOnProperty(matchIfMissing=true)`，LocalDisk 默认不启用）。OSS 返回 `directUpload=true`，前端走 `uploadToBackend` POST 到 `/api/files/uploads/direct`，但后端 `FileUploadService.directUpload` 对 OSS（`supportsPresignedUpload()=true`）抛 `BusinessException("当前存储提供者不支持直传上传")`。

**证据**：
- `AliyunOssStorageProvider.java:107-116`：`generateUploadInfo` 返回 `directUpload=true`（L115）
- 前端 `services/upload.js:45-47`：`if (directUpload)` → `uploadToBackend` → `oss.js:23` POST `/api/files/uploads/direct`
- 后端 `FileUploadService.java:127-136`：
  ```java
  StorageProvider provider = registry.getDefaultProvider();
  if (!provider.supportsPresignedUpload()) {
      provider.receiveUpload(...);      // LocalDisk 走这里
  } else {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前存储提供者不支持直传上传");  // OSS 走这里 ← 抛异常
  }
  ```
  OSS `supportsPresignedUpload()` 返回 `true`（`AliyunOssStorageProvider.java:91-93`）→ 进入 else 抛异常
- `AliyunOssStorageProvider.java:135` 注释自认"OSS 使用预签名直传，不支持后端接收上传"

**修复**：`AliyunOssStorageProvider.java:115` `true` → `false`（→ 前端走 `uploadToOss` 预签名 PUT）

**影响**：**默认部署（OSS）上传完全不可用**。这是当前最严重的问题。

### 附带文档错位

`UploadInfo.java:36` 的 `@Schema` 描述「true=前端直传存储，false=前端传到后端」与前端实际行为**正好相反**（前端 `true→后端 POST`、`false→存储 PUT`）。建议同步修正此注释，或在 `docs/api-contract.md` 中明确真值定义。

---

## 四、ISSUE #11 多存储方案

> **全部完成**。致命缺陷#1/#2 修复后，11-A/B/C 前端代码自动恢复端到端可用；11-F JSDoc 已在第五轮完成。

---

## 五、ISSUE #12 CI/CD

> 3 项中 1 项已落地（12-C），12-A/12-B 仍需外部资源。

| 编号 | 项 | 状态 | 严重度 | 证据 |
|---|---|---|---|---|
| **12-A** | 自托管 Runner | ❌ 未完成 | 中 | `ci-cd.yml` 5 个 job（L55/163/192/217/338）全部 `runs-on: ubuntu-latest`；`self-hosted` 仅见于 `DEPLOYMENT.md:214/219` 文档示例 |
| **12-B** | 阿里云 ACR | ❌ 未完成 | 中 | `ci-cd.yml:47` `IMAGE_PREFIX: ghcr.io/...`、L315 `registry: ghcr.io`；`scripts/setup-acr.sh` 存在但从未执行（双向切换脚本，enable 模式未运行） |
| **12-C** | 本地开发环境 compose.dev | ✅ 已完成 | — | 新建 `docker-compose.dev.yml`（4 中间件 + 端口暴露）+ `scripts/dev-up.sh`（up/down/reset/logs） |

---

## 六、ISSUE #13 热配置

> **全部完成**。13-BUG-3 键名已修正；13-B/C 死键和死代码/测试 mock 已清理。

| 编号 | 项 | 状态 | 严重度 | 证据 |
|---|---|---|---|---|
| ~~13-BUG-3~~ | V2 键名修正 | ✅ 已完成 | — | `V2__hot_config_keys.sql:61` `app.email.verify-code.cooldown-seconds`（3 点）与代码 `ContactVerificationService.java:54`、V1:53 一致 |
| ~~13-B/C~~ | 死键 + 死代码清理 | ✅ 已完成 | — | 见下方 6.3 清理明细 |

### 6.1 已清理明细（第五轮）

| 清理项 | 文件 | 变更 |
|---|---|---|
| 7 个死键删除 | `V2__hot_config_keys.sql` | 删除 L38-39（`app.rest-client.*` 2 个）+ L42-46（`app.resilience.*` 5 个） |
| 死导入删除 | `FileUploadService.java:20` | 删除 `import static ...BLOCKED_EXTENSIONS` |
| 死常量删除 | `FileNameUtil.java:10` | 删除 `BLOCKED_EXTENSIONS` + 移除 `import java.util.Set` |
| Javadoc 修正 | `FileCopyService.java:29` | `max-nodes-per-transaction` → `max-nodes-per-tx` |
| 测试 mock 修正 | `FileCopyServiceTest.java:59` | `max-nodes-per-transaction` → `max-nodes-per-tx` |
| 冗余 mock 删除 | `StorageQuotaCacheServiceTest.java:43` | 删除冗余 `storage-usage-ttl-minutes` mock |

---

## 七、CODEX 审核未完成项

> **全部完成**。CODEX-A 枚举迁移 87 处，20 文件，`mvn test` 通过。

---

## 八、解决方案

### 8.1 P0：修复 2 个致命缺陷（最高优先级）✅ 已完成

| 编号 | 修复 | 文件:行号 | 状态 |
|---|---|---|---|
| **致命#2** | OSS `directUpload` `true` → `false` | `AliyunOssStorageProvider.java:115` | ✅ |
| **致命#1** | LocalDisk `directDownload` `true` → `false` | `LocalDiskStorageProvider.java:81` | ✅ |
| 附带 | 修正 `UploadInfo.java:36` @Schema 描述 | `UploadInfo.java:36` | ✅ |

> 修复后 11-A/B/C 三项自动恢复端到端可用，#11 完成度跃升至 ~100%。

### 8.2 P1：修复 13-BUG-3 ✅ 已完成

`V2__hot_config_keys.sql:61` 键名 `app.email.verify-code-cooldown-seconds` → `app.email.verify-code.cooldown-seconds`（与代码 L54、V1:53 一致）。

### 8.3 P2：CODEX-A 枚举迁移 ✅ 已完成

三域迁移总计 87 处，20 文件：
- `TeamErrorCode`：58 处替换（19 个文件：13 源 + 6 测试）
- `ShareErrorCode`：24 处替换（6 个文件：4 源 + 2 测试）
- `ProjectErrorCode`：5 处替换（4 个文件：2 源 + 2 测试）

模式：`ErrorCode.XXX` → `XxxErrorCode.XXX.getCode()`，测试文件用静态导入。`mvn test` 通过。`ErrorCode.java` 原 int 常量保留（向后兼容）。

### 8.4 P2：清理死键与死代码 ✅ 已完成

- 删除 `V2__hot_config_keys.sql` L38-39、L42-46（7 个死键）
- 删除 `FileUploadService.java:20` 死导入
- 删除 `FileNameUtil.java:10` 死常量 + 移除 `import java.util.Set`
- 修正 `FileCopyService.java:29` javadoc 键名
- 修正 `FileCopyServiceTest.java:59` mock 键名
- 删除 `StorageQuotaCacheServiceTest.java:43` 冗余 mock

### 8.5 P3：已完成（原需外部资源 / 无依赖）

| 编号 | 任务 | 状态 | 说明 |
|---|---|---|---|
| ~~12-C~~ | 本地开发 compose | ✅ | 新建 `docker-compose.dev.yml`（4 中间件 + 端口暴露）+ `scripts/dev-up.sh`（up/down/reset/logs） |
| ~~11-F~~ | 前端 VO JSDoc | ✅ | `api/storage.js` 补 `@typedef StorageProviderVO` + `HealthCheckResult`，三个 API 函数均加类型声明 |

### 8.6 P3：仍需外部资源

| 编号 | 任务 | 前置条件 |
|---|---|---|
| 12-A | 安装 GitHub Actions Runner → `ci-cd.yml` 5 个 job `runs-on: self-hosted` | 服务器 |
| 12-B | 注册阿里云 ACR → 配 Secrets → 运行 `scripts/setup-acr.sh enable` | 阿里云账号 |

---

## 九、推荐执行顺序

| 优先级 | 编号 | 任务 | 预估 | 依赖 | 状态 |
|---|---|---|---|---|---|
| **P0** | 致命#2 | OSS `directUpload` → `false` | 5 分钟 | 无 | ✅ |
| **P0** | 致命#1 | LocalDisk `directDownload` → `false` | 5 分钟 | 无 | ✅ |
| **P0** | 附带 | 修正 `UploadInfo.java` @Schema 描述 | 5 分钟 | 无 | ✅ |
| **P1** | 13-BUG-3 | 修正 V2 SQL 键名 | 10 分钟 | 无 | ✅ |
| **P2** | CODEX-A | 迁移 3 个 ErrorCode 枚举调用点 | 2 天 | 无 | ✅ |
| **P2** | 13-B/C + 清理 | 清理 7 死键 + 6 死代码/测试 | 0.5 天 | 无 | ✅ |
| **P3** | 12-C | 本地开发 compose | 1 天 | 无 | ✅ |
| **P3** | 11-F | 前端 VO JSDoc | 0.5 天 | 无 | ✅ |
| **P3** | 12-A | 自托管 Runner | 0.5 天 + 服务器 | 需服务器 | ❌ |
| **P3** | 12-B | 阿里云 ACR | 0.5 天 + 账号 | 需账号 | ❌ |

**总预估**：P0 ~15 分钟 + P1 ~10 分钟 + P2 ~2.5 天 + P3 ~1.5 天 = **全部代码工作已完成**，剩余 2 项需外部资源。

---

## 十、附：核对元数据

- 核对方式：4 个并行 explore 子代理 + 3 个并行实施子代理（枚举迁移）
- 核对批次：2026-07-24 第五轮（全部 8 项落地）
- 关键变更：
  - 致命缺陷修复：OSS `directUpload=false`、LocalDisk `directDownload=false`，恢复默认上传 + 本地下载链路
  - 枚举迁移：TeamErrorCode/ShareErrorCode/ProjectErrorCode 共 87 处调用点
  - 死键/死代码清理：7 死键 + 死导入 + 死常量 + 3 处测试 mock 修正
  - 本地开发环境：`docker-compose.dev.yml` + `scripts/dev-up.sh`
  - 前端类型注解：`api/storage.js` JSDoc `@typedef`
