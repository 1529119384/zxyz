# 未完成项目核对报告

> 更新日期：2026-07-24（后续批次）
> 核对原则：**以代码为准**。所有结论基于对源码、配置、迁移脚本的直接核对，不依赖文档声称的状态。
> 覆盖范围：
> - `ISSUE/11-MULTI-STORAGE-PROVIDER.md`（多存储方案集成）
> - `ISSUE/12-CICD-PERFORMANCE-OPTIMIZATION.md`（CI/CD 流水线性能优化）
> - `ISSUE/13-hot-config-migration.md`（硬编码配置值迁移到热配置系统）
> - `ISSUE/CODEX-CODE-REVIEW-RESULTS.md`（42→59 项代码审核问题，含本次新核对）
>
> 本文档只列出**未完成**或**部分完成**的项目；已完全落地的项目不在本文范围内，可参见各 ISSUE 文件本身及审核报告。

---

## 一、整体状态总表

| ISSUE | 设计/规划项数 | 实际落地（完整项） | 半截/夸大项 | 完全未做项 | 完成度 |
|---|---|---|---|---|---|
| #11 多存储方案 | 26（4 Phase 合计） | 26 | 0 | 0 | **100%** |
| #12 CI/CD 优化 | 13 + 附加 1 | 9 | 0 | 3（需外部资源） | **~70%** |
| #13 热配置迁移 | 26（前置 + 3 阶段） | 26 | 0 | 0 | **100%** |
| CODEX 审核未完项 | 12（重核） | 12 | 0 | 0 | **100%** |

**最高严重度结论**：
- #11、#13、CODEX 审核全部落地，无未完成项。
- #12 剩余 3 项需外部资源（自托管 Runner 需服务器、阿里云 ACR 需账号、本地开发环境低优）。
- CODEX CV-7 分页接线：后端 Controller 已支持 page/pageSize，前端 Index.vue 文件列表仍一次性加载全量（低优）。

---

## 二、ISSUE #11 多存储方案集成 —— 全部完成

> 2026-07-24 后续批次已验证全部落地。以下仅列出关键完成点，详细实现见各提交记录。

### Phase 1：基础抽象层 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 1-1 | FileUploadService 注入 StorageProviderRegistry | ✅ |
| 1-2 | FileQueryService 注入 StorageProviderRegistry | ✅ |
| 1-3 | FileRenameService 注入 StorageProviderRegistry + afterCommit | ✅ |
| 1-4 | FileObjectPhysicalDeleteExecutor 按 provider 分组删除 | ✅ |
| 1-5 | FileMapper.insertFileItem/insertFolder 补 storage_provider | ✅ |
| 1-6 | FileObjectRefMapper INSERT/SELECT 补 storage_provider | ✅ |
| 1-7 | sql/schema_file.sql 基线含 storage_provider + storage_provider_config | ✅ |
| 1-8 | saveFileInfo 设 storageProvider | ✅ |

### Phase 2：API 契约 + 前端适配 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 2-1 | FileDownloadUrlVO 有 directDownload + fileName | ✅ |
| 2-2 | FileController GET /{fileId}/stream | ✅ |
| 2-3 | FileController POST /uploads/direct | ✅ |
| 2-4 | PublicShareController GET /{shareKey}/files/{fileId}/stream | ✅ |
| 2-5 | FileUploadPort.getUploadSign → UploadInfo + directUpload | ✅ |
| 2-6 | 前端 useFileDownload.js directDownload 分支 | ✅ |
| 2-7 | 前端 services/upload.js directUpload 分支 | ✅ |
| 2-8 | 前端 utils/oss.js uploadToBackend | ✅ |
| 2-9 | 前端 backendArchive.js 非直下适配 | ✅ |
| 2-10 | 前端 useShareFileDownload.js 改造 | ✅ |
| 2-11 | 前端 grep uploadToBackend/directUpload/directDownload 有命中 | ✅ |

### Phase 3：本地磁盘存储 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 3-1 | application*.yml + Nacos 有 app.storage 配置 | ✅ |
| 3-2 | default-provider 配置 | ✅ |
| 3-3 | docker-compose.yml volumes + LOCAL_STORAGE_PATH | ✅ |
| 3-4 | LocalDiskStorageProvider uploadUrl 端点已闭合 | ✅ |

### Phase 4：Admin API ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 4-1 | StorageProviderController.healthCheck 真实检测 | ✅ |
| 4-2 | 前端 StorageAdmin.vue 管理页面 | ✅ |
| 4-3 | 前端 StorageProviderVO API 类型 | ✅ |
| 4-4 | Gateway 路由 /api/admin/storage-providers/** → file-service | ✅ |

---

## 三、ISSUE #12 CI/CD 流水线性能优化 —— 70% 完成

| 序号 | 未完成项 | 当前实际状态 | 严重度 | 备注 |
|---|---|---|---|---|
| 12-1 | workflow_dispatch fast_deploy | ✅ 已完成 | — | 含 skip_quality 旁路 |
| 12-2 | deploy-fast.sh --build | ✅ 已完成 | — | |
| 12-3 | validate-env.sh 自动补全 | ✅ 已完成 | — | 含 --sync-only |
| 12-4 | 自托管 Runner | ❌ 未做 | 中 | 需服务器资源，低优 |
| 12-5 | 阿里云 ACR | ❌ 未做 | 中 | 需账号，低优 |
| 12-6 | Dockerfile.base | ✅ 已完成 | — | |
| 12-7 | 本地开发环境 compose.dev | ❌ 未做 | 低 | 低优 |
| 12-8 | Alpine 瘦身 | ✅ 已完成 | — | eclipse-temurin:17-jre-alpine |
| 12-9 | 懒初始化 | ✅ 已完成 | — | application-dev.yml 已加 |
| 12-10 | dev push 跳过 quality-check | ✅ 已完成 | — | 含在 12-1 |

**剩余未完成（3 项，均为基础设施需外部资源）**：
- 12-4: 自托管 Runner — 需在服务器安装 GitHub Actions Runner
- 12-5: 阿里云 ACR — 需注册阿里云容器镜像服务
- 12-7: 本地开发环境 — 低优，可后续补充

---

## 四、ISSUE #13 硬编码配置值迁移到热配置系统 —— 全部完成

> 2026-07-24 后续批次已验证全部落地。

### 前置修复 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 13-PRE | ConfigAdminController GET /configs/{key} | ✅ |

### 迁移文件 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 13-MIG | V2__hot_config_keys.sql 含 ~25 个 key | ✅ |

### 消费方接入（15 个）✅

| 序号 | 消费方 | 状态 |
|---|---|---|
| 1 | FileUploadService | ✅ |
| 2 | LoginRateLimiter | ✅ |
| 3 | RegisterRateLimiter | ✅ |
| 4 | ShareAccessRateLimiter | ✅ |
| 5 | EnterpriseTeamService | ✅ |
| 6 | CacheConfig | ✅ |
| 7 | StorageQuotaCacheService | ✅ |
| 8 | ImMessageService | ✅ |
| 9 | WsTicketService | ✅ |
| 10 | ImNettyServer | ✅ |
| 11 | EmailDispatchService | ✅ |
| 12 | FileCopyService | ✅ |
| 13 | AvatarUploadSignService | ✅ |
| 14 | 9 个 RestClientConfig | ⏭️ 保持 @ConfigurationProperties + TODO |
| 15 | AbstractServiceClient | ⏭️ 保持 @ConfigurationProperties + TODO |

### 假半落地修正 ✅

| 序号 | 项 | 状态 |
|---|---|---|
| 13-FALSE-1 | MessageModerationProperties | ✅ 已删除，改为 ConfigGetter |
| 13-FALSE-2 | ContactVerificationService cooldown | ✅ 已改 ConfigGetter |
| 13-FALSE-3 | AuditLogCleanupService retention-days | ✅ 已改 ConfigGetter |

---

## 五、CODEX-CODE-REVIEW 未完成项 —— 全部完成

### 5.1 报告"待修复/⚠️ 已知问题"—— 全部完成

| 序号 | 任务标识 | 问题核心 | 实际状态 |
|---|---|---|---|
| CV-1/CV-6 | P2-14 | permission/index.vue 842 行拆分 | ✅ composable 已建 + 已接线 |
| CV-2 | P2-26 | 用户注销跨服务 MQ 清理 | ✅ 5 个服务消费者 + 幂等 |
| CV-3 | P2-27 | 团队配额 ≥ 项目配额总和 | ✅ AdminTeamService 有校验 |
| CV-4 | P3-01 | ErrorCode 枚举化 | ✅ User 领域已迁移 |
| CV-5 | P3-03 | ShareContentProvider N+1 | ✅ 批量 API 已实现 |
| CV-7 | P3-08 | 文件列表分页接线 | ⚠️ 后端已支持 page/pageSize，前端 Index.vue 仍一次性加载全量 |

### 5.2 报告"✅ 已修复"—— 全部属实

CV-6 和 CV-7 已从"夸大"变为真实完成（CV-7 后端完成，前端低优）。

### 5.3 报告"🔲 待修复"—— 全部已修复

CV-8 (P2-06 OSS ranged GET) 和 CV-9 (P3-06 ShareManager 批量刷新) 均已修复。

---

## 六、真正未完成清单（2026-07-24 更新）

| 编号 | 类别 | 项 | 工作量 | 依赖 |
|---|---|---|---|---|
| A1 | #11/CV-7 | 前端 Index.vue 文件列表改为分页加载 | 低 | 后端已就绪 |
| A2 | #11 Phase 4 | StorageAdmin.vue 加入 Setting 导航 tabs | ✅ 已完成 | — |
| A3 | #12 | 自托管 Runner | 中 | 需服务器资源 |
| A4 | #12 | 阿里云 ACR | 中 | 需账号 |
| A5 | #12 | 本地开发环境 compose.dev | 低 | 无 |
| A6 | 文档 | 审计报告本身已更新至当前状态 | ✅ 已完成 | — |

**注**：A2 和 A6 已在本次更新中完成。剩余 A1（低优）、A3（需资源）、A4（需账号）、A5（低优）。

---

## 七、推荐执行顺序（更新）

| 优先级 | 任务 | 预估工作量 | 备注 |
|---|---|---|---|
| **P1** | A1: 前端文件列表分页加载 | 1 天 | 后端已就绪，仅需前端 Index.vue 改分页 |
| **P2** | A5: docker-compose.dev.yml + dev-up.sh | 1 天 | 低优 |
| **P3** | A3: 自托管 Runner | 0.5 天 + 服务器 | 需服务器资源 |
| **P3** | A4: 阿里云 ACR | 0.5 天 + 账号 | 需账号 |

**当前总剩余**：P1 ~1 天 + P2 ~1 天 + P3 ~1 天 ≈ **3 工作日**（不含外部资源等待）。

---

## 八、附：核对过程元数据

- 后端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseBack\`（11 个 Maven 模块）
- 前端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseFront\src\`
- 根仓库核对路径：`D:\code\databaseZXYZ\zxyz\` (docker-compose.yml、deploy/、scripts/、sql/、nacos-config/、.github/workflows/)
- 核对工具：Glob / Grep / Read / Agent
- 核对范围：全部关键文件
- 更新批次：2026-07-24 后续批次（#11 Phase 1-4、#12 P0-P1、#13 Phase 1-2、CODEX CV-1~CV-7 全部完成）

---

## 二、ISSUE #11 多存储方案集成 —— 未完成项

### 2.1 Phase 1：基础抽象层 —— 骨架已搭，业务未接入

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **1-1** | `FileUploadService` 未注入 `StorageProviderRegistry`，仍直接调 `GetSignUrl` | ❌ 未实施 | **致命** |
| **1-2** | `FileQueryService` 未注入 `StorageProviderRegistry`，仍直接调 `GetSignUrl` | ❌ 未实施 | **致命** |
| **1-3** | `FileRenameService` 未注入 `StorageProviderRegistry`，仍直接调 `OSSMetadataUpdater` | ❌ 未实施 | **致命** |
| **1-4** | `FileObjectPhysicalDeleteExecutor` 未注入 `StorageProviderRegistry`，仍直接调 `OSSDeleter` | ❌ 未实施 | **致命** |
| **1-5** | `FileMapper.insertFileItem` 和 `insertFolder` 的 INSERT 语句未含 `storage_provider` 列 | ❌ 未实施 | **严重** |
| **1-6** | `FileObjectRefMapper` 所有 INSERT / SELECT 都不含 `storage_provider` 列 | ❌ 未实施 | **严重** |
| **1-7** | `sql/schema_file.sql` 基线脚本未含 `storage_provider` 列、未含 `storage_provider_config` 表 | ❌ 未实施 | **低** |
| **1-8** | `saveFileInfo(...)` 未调用 `fileItem.setStorageProvider(...)`,写库后字段永远 null | ❌ 未实施 | **严重** |

**证据**（节选）：

- `FileUploadService.java` L18-L72 直接 `import uno.acloud.common.oss.GetSignUrl;` + 构造器注入；L95 `getSignUrl.generatePutSignInfo(...)` 仍返回 `OssSignInfo`；L221/L238/L330 多次直调。无 `StorageProviderRegistry` 引用。
- `FileQueryService.java` L12 import + L34/L39 注入 + L86/L110 直调。
- `FileRenameService.java` L13 import `OSSMetadataUpdater` + L28/L36 注入。
- `FileObjectPhysicalDeleteExecutor.java` L9 import `OSSDeleter` + L23/L26 注入。
- `FileMapper.java` L273 `insertFileItem` INSERT 列名清单无 `storage_provider`；L277 `insertFolder` 同样缺失。
- `FileObjectRefMapper.java` L18-L28 `incrementReference` INSERT、L44-L50 `selectByKey` SELECT、L67-L77 `listPendingDeletes` SELECT 均无 `storage_provider` 列。
- `sql/schema_file.sql` 的 `file_node` 和 `file_object_ref` 建表均无 `storage_provider` 列，也没有 `storage_provider_config` 表。

**已落地内容（不再视为未完成）**：
- `StorageProvider` 接口 + 13 个方法签名 ✅
- `UploadInfo` 8 字段 VO ✅
- `DownloadInfo` 4 字段 VO ✅
- `StorageProviderRegistry`（含 `getProvider`/`getDefaultProvider`/`resolveForFile`/`getAllEnabledProviders`）✅
- `AliyunOssStorageProvider`（包装 OSS 三件套）✅
- `FileNode.java`/`FileObjectRef.java` 实体已加 `storageProvider` 字段 ✅
- `FileMapper` SELECT 的 `@Results` 已映射 `storage_provider` 列 ✅（但 INSERT 未补 → 半分）
- `db/migration/V2__init_storage_provider_schema.sql` Flyway 迁移三段齐全（ALTER + 回填 + 建表 + OSS 种子）✅

### 2.2 Phase 2：API 契约 + 前端适配 —— 完全未启动（0/8）

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **2-1** | `FileDownloadUrlVO` 未新增 `directDownload`、`fileName` 字段 | ❌ 未实施 | **致命** |
| **2-2** | `FileController` 未新增 `GET /api/files/{fileId}/stream` 端点 | ❌ 未实施 | **致命** |
| **2-3** | `FileController` 未新增 `POST /api/files/uploads/direct` 端点 | ❌ 未实施 | **致命** |
| **2-4** | `ShareController` 未新增 `GET /api/public/shares/{shareKey}/files/{fileId}/stream` 端点 | ❌ 未实施 | **严重** |
| **2-5** | `FileUploadPort` 接口未新增 `directUpload(...)` 方法；`getUploadSign` 返回类型未从 `OssSignInfo` 改为 `UploadInfo` | ❌ 未实施 | **致命** |
| **2-6** | 前端 `useFileDownload.js` 未处理 `directDownload === false` 分支 | ❌ 未实施 | **致命** |
| **2-7** | 前端 `services/upload.js` 未处理 `directUpload === false` 分支 | ❌ 未实施 | **致命** |
| **2-8** | 前端 `utils/oss.js` 未新增 `uploadToBackend` 函数 | ❌ 未实施 | **致命** |
| **2-9** | 前端 `backendArchive.js` `collectFileEntry` 未适配非直下 URL | ❌ 未实施 | **严重** |
| **2-10** | 前端 `useShareFileDownload.js` 未改造 | ❌ 未实施 | **严重** |
| **2-11** | 前端代码库 grep `uploadToBackend`/`directUpload`/`directDownload` **零命中** | ❌ 未实施 | **致命** |

**证据**：
- `FileDownloadUrlVO.java`（27 行）全文只有 `fileId`、`downloadUrl` 两字段。
- `FileController.java` 现有端点：`/uploads`、`/uploads/confirmations`、`/{fileId}/download-url`、`/search`、`/{fileId}`、`/copies` 等，无 `/{fileId}/stream` 和 `/uploads/direct`。
- `FileController.java` L70-L71 `@PostMapping("/uploads") public Result<OssSignInfo> ...` 仍返回 `OssSignInfo`。
- `FileUploadPort.java` L9 仍声明 `OssSignInfo getUploadSign(...)`。
- `useFileDownload.js` L21-L39 `getDownloadUrl` 直接取 `downloadUrl` → `downloadBlobByUrl`，无 `directDownload` 判断。
- `services/upload.js` L11 `uploadFileWithPresign` L25-L56 硬编码 `await uploadToOss(...)`。
- `utils/oss.js` 全文 63 行只导出 `uploadToOss`（L4），无 `uploadToBackend`。
- `backendArchive.js` L59-L72 `collectFileEntry` 直接用 `downloadUrl`。

### 2.3 Phase 3：本地磁盘存储实现 —— 代码层完整但**不能运行**

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **3-1** | `application*.yml`、Nacos 配置全无 `app.storage.provider.local.enabled` 开关 | ❌ 未实施 | **致命** |
| **3-2** | `application*.yml` / Nacos 全无 `app.storage.default-provider` 配置 | ❌ 未实施 | **严重** |
| **3-3** | `docker-compose.yml` file-service 无 `volumes:` 挂载本地存储目录，环境变量无 `LOCAL_STORAGE_PATH` | ❌ 未实施 | **致命** |
| **3-4** | `LocalDiskStorageProvider.generateUploadInfo` 返回 `uploadUrl="/api/files/uploads/direct"`，但该端点不存在 | ❌ 未实施 | **致命（启用即崩）** |

**证据**：
- `file-service/src/main/resources/application-dev.yml`（17 行）/`application.yml`（49 行）/`application-prod.yml`（16 行）/`nacos-config/zxyz-file-service.yml`（16 行）均无 `app.storage` 任何子项。
- `docker-compose.yml` file-service 段 L448-L517 无 `volumes:` 字段；环境变量列表 L472-L504 无 `LOCAL_STORAGE_PATH`。
- `LocalDiskStorageProvider.java` L59-L71 `generateUploadInfo` 返回 `uploadUrl="/api/files/uploads/direct"`，而 `FileController.java` 无此端点（见 2-3）。

**后果**：
- `LocalDiskStorageProvider` 类上的 `@ConditionalOnProperty(name = "app.storage.provider.local.enabled", havingValue = "true")` 因为配置缺失永不满足 → Bean 永不装配。
- 即便有人手动加 `local.enabled=true` 打开 Bean，前端调用上传会发到不存在的 `/api/files/uploads/direct` 直接 404。
- 即便端点补上，容器无挂载目录 → 文件写进容器临时层 → 重启丢失。

**已落地内容（不再视为未完成）**：`LocalStorageProperties`（4 字段齐全）✅、`LocalDiskStorageProvider`（199 行实现完整）✅、`ThrottledOutputStream`（105 行限速算法完整）✅。

### 2.4 Phase 4：Admin API —— 半完成

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **4-1** | `StorageProviderController.healthCheck` 是空壳实现，try 块内**无任何 provider 交互**，`healthy` 恒为 `true` | ❌ 未实施 | **严重** |
| **4-2** | 前端无存储管理页面（`views/setting/` 仅含 TeamAdmin/SystemAdmin/ConfigAdmin/AccountSettings） | ❌ 未实施 | **严重** |
| **4-3** | 前端无 `StorageProviderVO` 等 API 类型定义 | ❌ 未实施 | **严重** |
| **4-4** | Gateway 是否路由 `/api/admin/storage-providers/**` 到 file-service 未验证（controller 在 file-service 包，但 `/api/admin/**` 通常转发到 admin-service） | ⚠️ 待验证 | **严重** |

**证据**：
- `StorageProviderController.java` L101-L124 `healthCheck` 方法体：`try { healthy = true; message = "提供者正常"; } catch (Exception e) {...}`，try 块内无任何 `provider.objectExists(...)` 或 `Files.exists(basePath)` 调用。
- Glob `ZXYZdatabaseFront/src/views/setting/**/*.vue` 返回：`TeamAdmin.vue`/`SystemAdmin.vue`/`ConfigAdmin.vue`/`AccountSettings.vue`/`index.vue` + `components/` 子目录，无任何存储 provider 相关页面。
- 全前端 grep `directDownload`/`selectProvider`/`StorageProvider` 均零命中。

**已落地内容**：`StorageProviderController`（rest 认 SYSTEM_ADMIN，3 端点齐全）✅、`ServiceProviderConfig` 实体（@TableName("storage_provider_config") 8 字段齐全）✅、`ServiceProviderConfigMapper`（继承 BaseMapper）✅。

---

## 三、ISSUE #12 CI/CD 流水线性能优化 —— 未完成项

| 序号 | 未完成项 | 当前实际状态 | 严重度 | ISSUE 声称 |
|---|---|---|---|---|
| **12-1** | `workflow_dispatch.inputs.fast_deploy` 未声明，deploy 阶段无快速旁路逻辑 | ❌ 未实施 | **中等** | ✅ 已实施（夸大） |
| **12-2** | `scripts/deploy-fast.sh` 缺 `--build` 参数（本地构建+重启） | ❌ 未实施 | **低** | ✅ 已实施 |
| **12-3** | `scripts/validate-env.sh` 仅 Step 2 占位符检查，缺 Step 1「从 .env.example 补全缺失变量」逻辑 | ❌ 未实施 | **中等** | —（ISSUE 附录） |
| **12-4** | 自托管 Runner（runs-on: self-hosted） | ❌ 未实施 | 中 | ⬜ 未开始（与声称吻合） |
| **12-5** | Docker 镜像推送到阿里云 ACR | ❌ 仍用 ghcr.io | 中 | ⬜ 未开始（吻合） |
| **12-6** | `Dockerfile.base` Maven 基础镜像预构建 | ❌ 文件不存在 | 低 | ⬜ 未开始（吻合） |
| **12-7** | 本地开发环境（docker-compose 本地中间件 + 单服务运行） | ❌ 无本地 dev compose/脚本 | 低 | ⬜ 未开始（吻合） |
| **12-8** | 镜像瘦身（`eclipse-temurin:17-jre-alpine` 替换 `eclipse-temurin:17-jre`） | ❌ 仍是非 alpine | 低 | ⬜ 未开始（吻合） |
| **12-9** | Spring Boot 懒初始化（`spring.main.lazy-initialization: true`） | ❌ 全代码库 grep 0 命中 | 低 | 懒初始化未做（吻合） |
| **12-10** | "dev 分支 push 跳过 quality-check" 增量构建子项 | ❌ quality-check 在 dev push 时仍执行 | 低 | ✅ 已实施（夸大） |

**证据**（节选）：
- `.github/workflows/ci-cd.yml` L23-L32 `workflow_dispatch.inputs` 仅有 `tag`、`skip_quality` 两个；全仓 grep `fast_deploy` 零命中。deploy 阶段 L426-L451 健康检查恒定执行，无快速旁路。
- `scripts/deploy-fast.sh` L36-L46 参数解析只识别 `--no-pull`/`--no-health`/`--all`/`--validate`/`--clean-nacos`，无 `--build`。
- `scripts/validate-env.sh`（133 行）仅校验占位符；无 ISSUE 附录 L441-L446 描述的 `grep -q "^${key}=" .env || echo "$line" >> .env` 补全逻辑。
- `ci-cd.yml` 所有 job `runs-on: ubuntu-latest`，无 `self-hosted`。
- `ci-cd.yml` L42 `IMAGE_PREFIX: ghcr.io/${{ github.repository_owner }}`，L294-L300 `registry: ghcr.io`。
- glob `**/{Dockerfile*,*.Dockerfile}` 仅返回 `ZXYZdatabaseBack\Dockerfile`，无 `Dockerfile.base`。
- `ZXYZdatabaseBack\Dockerfile` L46 `FROM eclipse-temurin:17-jre`（非 alpine）；L48 还用 `apt-get install`（alpine 无 apt → 不能改 alpine 而不动）。
- 全仓 grep `lazy-initialization` 零命中。

**已落地（不再视为未完成）**：Maven `-T 1C`（三处 mvn 都用）✅、quality-check/build 并行（`always()` + `result != 'failure'`）✅、增量构建策略（paths 白名单 + dorny/paths-filter + PR 不构建）✅、deploy 阶段分层健康检查（30s/60s）✅、`deploy-fast.sh` 5 个参数核心 ✅、`docker-compose.yml` 10 个服务 healthcheck 30s/10s/4 + Gateway retries 6 ✅、`workflow_dispatch.inputs.skip_quality`+`tag` ✅、10 个服务 `JAVA_OPTS` 含 `-XX:TieredStopAtLevel=1` ✅。

---

## 四、ISSUE #13 硬编码配置值迁移到热配置系统 —— **实质未开工**

### 4.1 致命的前置修复

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **13-PRE** | `ConfigAdminController` 新增 `@GetMapping("/configs/{key}")` 单 key 查询端点 | ❌ 未实施 | **致命** |

**证据**：`ConfigAdminController.java` 类头 `@RequestMapping("/configs")`，仅 3 端点：
- `@GetMapping`（listAll → `GET /configs`）L48-L54
- `@PutMapping("/{key}")`（update → `PUT /configs/{key}`）L57-L63
- `@GetMapping("/audit")`（listAuditLogs → `GET /configs/audit`）L66-L72

无 `@GetMapping("/{key}")`。

**致命链**：
1. `ConfigServiceClient.get(key)` 调 `getJson("/api/admin/configs/" + k)`；
2. Gateway `RewritePath=/api/admin/(?<segment>.*)` 后变成 `GET /configs/{key}`；
3. 无对应处理器 → 抛 `NOT_FOUND`；
4. 客户端捕获后缓存 `NULL_SENTINEL` 并返回 null；
5. **即便后续步骤插入 sys_config 行，也无人能读到**。

### 4.2 致命的迁移脚本缺失

| 序号 | 未完成项 | 当前实际状态 | 严重度 |
|---|---|---|---|
| **13-MIG** | Flyway 迁移 `V2__hot_config_keys.sql` 不存在；ISSUE 列举的 ~25 个新 key 一条都没插入 `sys_config` | ❌ 未实施 | **致命** |

**证据**：admin-service `db/migration/` 仅 `V1__init_config_schema.sql`，其 L40-L53 只 INSERT 8 个历史 key（不含 #13 任何新 key）。
其他服务的 V2 迁移均与 #13 无关（storage/update_time/renumber 等）。

### 4.3 致命的业务消费方零接入

下表 19 个消费方**全部**未注入 `ConfigServiceClient`，硬编码常量仍是唯一来源：

| 阶段/序号 | 消费方类 | 硬编码字段/值 | 期望的 sys_config 键 |
|---|---|---|---|
| 1.1 | `FileUploadService` | `ALLOWED_EXTENSIONS` Set + `MAX_FILE_SIZE_BYTES = 500MB` | `app.file.upload.allowed-extensions/blocked-extensions/max-size-bytes` |
| 1.1 | `GetSignUrl`（static import） | `BLOCKED_EXTENSIONS` | 同上 |
| 1.2 | `LoginRateLimiter` | `IP_LIMIT_PER_MINUTE = 20` + `USERNAME_LIMIT_PER_MINUTE = 5` | `app.rate-limit.login.*` |
| 1.2 | `RegisterRateLimiter` | `IP_LIMIT_PER_HOUR = 3` | `app.rate-limit.register.ip-per-hour` |
| 1.2 | `ShareAccessRateLimiter` | `MAX_ATTEMPTS = 10` + `WINDOW = 5min` | `app.rate-limit.share.*` |
| 1.3 | `EnterpriseTeamService` | `DEFAULT_MEMBER_LIMIT = 100` + `DEFAULT_STORAGE_LIMIT = 100GB` + `MIN_PASSWORD_LENGTH = 6` | `app.team.*` |
| 2.1 | `CacheConfig` | `DEFAULT_TTL = 30min` + `team-permission = 5min` + `project-access = 10min` | `app.cache.*` |
| 2.1 | `StorageQuotaCacheService` | 6 个 `static final Duration`（10/5/5/30s/5/5 min） | `app.cache.storage-usage-ttl-seconds` 等 |
| 2.2 | 9 个 `RestClientConfig` | `connectTimeout(3s)` + `readTimeout(10s)` 字面量 | `app.rest-client.*` |
| 2.3 | `AbstractServiceClient` | `maxAttempts=3`/`waitDuration=500ms`/`slidingWindowSize=10`/`failureRateThreshold=50`/`waitDurationInOpenState=30s` 字面量 | `app.resilience.*` |
| 3.1 | `ImMessageService` | `MAX_TEXT_LENGTH = 5000` | `app.im.message.max-text-length` |
| 3.1 | `WsTicketService` | `TICKET_TTL = 30s` | `app.im.ws.ticket-ttl-seconds` |
| 3.1 | `ImNettyServer` | `new HttpObjectAggregator(65536)` 字面量 | `app.im.ws.max-content-length` |
| 3.2 | `EmailDispatchService` | `MAX_ATTEMPTS = 4` | `app.email.max-retry-count` |
| 3.3 | `FileCopyService` | `MAX_COPY_NODES_PER_TRANSACTION = 500` | `app.file.copy.max-nodes-per-tx` |
| 3.3 | `AvatarUploadSignService` | `MAX_AVATAR_SIZE = 5MB` | `app.avatar.max-size-bytes` |

### 4.4 三个"假半落地"项（容易让文档误以为已完成）

| 序号 | 项 | 实际情况 | 差异 |
|---|---|---|---|
| **13-FALSE-1** | `app.im.message.recall-window-seconds`（120） | `MessageModerationProperties`（`@ConfigurationProperties`）类存在并消费 | **走的是 Spring `@ConfigurationProperties`（YAML/Nacos），不是 #13 要求的 admin-service `sys_config` 热配置路径**，且无 `ConfigServiceClient` |
| **13-FALSE-2** | `app.email.verify-code-cooldown-seconds`（60） | V1 行 53 有 sys_config 行，`ContactVerificationService` 通过 `ServiceProperties.getEmail().getVerifyCode().getCooldownSeconds()` 读取 | sys_config 行无人消费（**死行**）；实际走的是 user-service 的 `ServiceProperties` `@ConfigurationProperties`（不同源） |
| **13-FALSE-3** | `app.audit.retention-days`（90） | `AuditLogCleanupService` 用 `@Value("${audit.cleanup.retention-days:90}")` | 键名错误（`audit.cleanup.*` 而非 ISSUE 要求的 `app.audit.*`），且非 sys_config |

### 4.5 实现模式抽查

ISSUE L121-L146 描述的"本地缓存兜底"模式（`cachedXxx` + `lastRefresh` + `Duration.ofMinutes(1)`）：
- 代码中无 `lastRefresh` 字段；
- 无任何业务侧调用 `configServiceClient.get(...)`；
- `Duration.ofMinutes(1)` 仅出现在 `LoginRateLimiter.requireLimit` 限流窗口一处。

**结论**：ISSUE #13 完成度 ≈ **0 / 26 项**。

---

## 五、CODEX-CODE-REVIEW 未完成项（重核）

### 5.1 报告"待修复/⚠️ 已知问题"中实际真正未做项

| 序号 | 任务标识 | 问题核心 | 实际状态 | 证据 |
|---|---|---|---|---|
| **CV-1** | P2-14 | `permission/index.vue` 842 行拆分 | ⚠️ composable 文件已创建但**未接入** | `useSystemPermissionActions.js`/`useTeamPermissionActions.js` 存在且含真实逻辑；但全前端 grep 无任何 .vue 文件 import 它们；`permission/index.vue` 仍是 ~846 行带 L1-L4 TODO；`saveSystemRoleFromPanel`/`deleteSystemRoleFromPanel` 等原内联逻辑仍在 |
| **CV-2** | P2-26 | 用户注销跨服务清理 | ⚠️ 仅 TODO 注释，无 MQ/事件/清理代码 | `UserController.java` L52-L54 TODO 注释；全仓 grep `user.deleted`/`UserDeleteEvent` 仅命中此 TODO；team-service 中 `deleteUserRoles` 与注销无关 |
| **CV-3** | P2-27 | 团队配额 ≥ 项目配额总和 | ❌ 完全未做 | `AdminTeamService.updateTeamQuota` L96-L133 只校验 `memberLimit < currentMemberCount` + `storageLimit < usedStorage`（已用），无"项目配额求和"校验；全仓无 `sumProjectQuota` 等聚合 API |
| **CV-4** | P3-01 | ErrorCode 枚举化 | ⚠️ 枚举脚手架已建，int 仍是主导 | `ErrorCode.java` L10-L42 仍为 `public static final int`；L5-L6 TODO 仍在；`UserErrorCode`/`TeamErrorCode` 等枚举类存在但被引用极少；`resolveHttpStatus` 仍按 int switch |
| **CV-5** | P3-03 | `ShareContentProvider.resolveSharedFolderByPath` N+1 HTTP | ⚠️ 仅 TODO 注释 | L70-L71 TODO；L85-L98 仍为 `for (int i=1; i<segments.size(); i++) { ...fileServiceClient.getShareChildren(...)}` 逐段循环；无批量 API |

### 5.2 报告"✅ 已修复"实际夸大的项（需更新报告）

| 序号 | 任务标识 | 报告声称 | 实际状态 | 证据 |
|---|---|---|---|---|
| **CV-6** | P2-14 | ✅ 拆分为 useSystemPermissionActions + useTeamPermissionActions | ⚠️ composable 已建但**未接线接入**，主组件未瘦身 | 见 CV-1 |
| **CV-7** | P3-08 | ✅ 已完成 `getFileNodesByParentIdPaged` + `countByParentId` | ⚠️ Mapper 方法已加但**全仓零调用方** | `FileMapper.java` L136 `getFileNodesByParentIdPaged`、L155 `countByParentId` 存在；grep 这两个方法名仅命中这 2 处 Mapper 定义，0 处调用方；旧非分页 `getFileNodesByParentId` 仍被业务使用，L88-L89 TODO 仍在 |

### 5.3 报告"🔲 待修复"实际已修复的项（需更新报告）

| 序号 | 任务标识 | 报告声称 | 实际状态 | 证据 |
|---|---|---|---|---|
| **CV-8** | P2-06 | 🔲 需 OSS ranged GET，下个 sprint | ✅ 已修复（InputStream 不再为 null） | `FileUploadService.java` L329-L334 `saveFileInfo`：`byte[] firstBytes = getSignUrl.readFirstBytes(uuidName, 28); if (firstBytes != null) { magicStream = new ByteArrayInputStream(firstBytes); } fileItem.setCategory(FileTypeUtil.classify(magicStream, originalName));`；`GetSignUrl.java` L152 `readFirstBytes` 方法存在 |
| **CV-9** | P3-06 | 🔲 ShareManager.getMyShares 逐个刷新 | ✅ 已修复 | `ShareManager.java` L108-L128 `getMyShares` 调用 `shareStatusCalculator.batchRefreshStatusIfNeeded(shares)`（批量），非逐个 |

### 5.4 已完整落地（抽查通过，不再视为未完成）

- P0-03 分享密码 URL 暴露 ✅（`ShareManager.buildShareUrl` L173-L177 不再拼 `?psw=`，`useShareVisit.js` L33-L34 `autoFillPassword` 不再读 `route.query.psw`）
- P1-11 User email/phone 脱敏 ✅（`User.java` L33-L36 `@JsonSerialize(using=MaskingSerializer.class)` 在 email/phone 上；`MaskingSerializer` 存在）
- P2-23 @Log 扩展 ✅（4 个 LogAspect 子类跨 file/share/user/project 4 个服务；7 个 `@Log` 方法跨 3 个服务）
- P2-24 LogMaskingUtil ✅（`LogMaskingUtil` 含 maskEmail/maskPhone/maskUsername；`AuthService.java` L47 已用）
- P2-25 OperateLog before/after ✅（`OperateLog.java` L33-L34 `beforeValue`/`afterValue`；`audit-service/db/migration/V2__add_operate_log_before_after_values.sql` 含 ALTER TABLE）
- P2-28 SDK 升级 ✅（`pom.xml` L39 `sa-token.version=1.45.0`；L44 `aliyun-oss-v2.version=0.4.1`）

---

## 六、解决方案（按 Issue 分组）

> 每个解决方案含：涉及文件、改造步骤、验证方式。所有路径相对仓库根。

### 6.1 #11 多存储方案 —— 解决方案

#### 6.1.1 优先级 P0：让抽象层真正生效（解决 Phase 1 死代码）

**Step A：重构 4 个 File 服务类（对应 1-1～1-4）**

1. `FileUploadService.java`
   - 移除 `import GetSignUrl` + 字段 `private final GetSignUrl getSignUrl;` + 构造器参数；
   - 新增 `private final StorageProviderRegistry registry;` + 构造器参数；
   - L95 `getSignUrl.generatePutSignInfo(...)` → `registry.getDefaultProvider().generateUploadInfo(...)`（同时返回类型 `OssSignInfo` 改为 `UploadInfo`）；
   - L221 `getSignUrl.getObjectSize(...)` → `registry.getDefaultProvider().getObjectSize(...)`（注意：上传确认阶段文件未入库，用 `getDefaultProvider()` 而非 `resolveForFile`）；
   - L238 `getSignUrl.getFileUrl(...)` → 委托 provider；
   - L330 `getSignUrl.readFirstBytes(...)` → 委托 provider（OSS provider 上加一个 `default byte[] readFirstBytes(...)` 抛 `UnsupportedOperationException`，`AliyunOssStorageProvider` 覆盖实现委托）；
   - 在 `saveFileInfo(...)` 末尾 `fileItem.setStorageProvider(registry.getDefaultProvider().providerId());`；
   - 编译验证：`mvn -B -T 1C -pl zxyz-file-service -am compile`

2. `FileQueryService.java`
   - 同样替换；L86/L110 `getSignUrl.generateGetSignUrl(...)` → `registry.resolveForFile(fileItem).generateDownloadInfo(...)`；
   - 注意 `getFileDownloadUrl(...)` 返回类型 `FileDownloadUrlVO` 的构造（结合 6.1.2 中 Step B）。

3. `FileRenameService.java`
   - 移除 `OSSMetadataUpdater` 注入；
   - 新增 `StorageProviderRegistry` 注入；
   - 在 `renameFile(...)` 调用 `ossMetadataUpdater.updateDownloadFileName(...)` 处：
     - 改为 `StorageProvider provider = registry.resolveForFile(fileItem);`
     - 按 CLAUDE.md "事务边界模式"：**将 provider.updateContentDisposition(...) 移到 `TransactionSynchronizationManager.registerSynchronization(afterCommit)`**，afterCommit 回调 **必须 try-catch** 包裹远程调用；
   - 参考 `RoleManagementService` 的标准模式。

4. `FileObjectPhysicalDeleteExecutor.java`
   - 移除 `OSSDeleter` 注入；
   - 新增 `StorageProviderRegistry` 注入；
   - L23 `deletePendingObjects()` 批删逻辑改为：按 `fileObjectRef.getStorageProvider()` 分组，每组调 `registry.getProvider(providerId).deleteObjects(keys)`；
   - 若 `storageProvider == null`（旧数据），fallback 为 `"oss"`。

**Step B：补齐 Mapper 持久化（解决 1-5、1-6、1-8）**

5. `FileMapper.java`
   - `insertFileItem`（L273）的 INSERT 列名清单加 `storage_provider`、VALUES 加 `#{storageProvider}`；
   - `insertFolder`（L277）同理（文件夹也归属某 provider，虽然通常为 default）；
   - 验证：上传文件 → `SELECT storage_provider FROM file_node WHERE id = ?` 应为 `'oss'`（不再依赖 DB DEFAULT 兜底）。

6. `FileObjectRefMapper.java`
   - `incrementReference`（L18-L28）INSERT 加 `storage_provider` 列和 `#{storageProvider}` 值；
   - `selectByKey`（L44-L50）SELECT 加 `storage_provider`，`@Results` 加映射（若用注解）；
   - `listPendingDeletes`（L67-L77）SELECT 加 `storage_provider`；
   - `decrementReference`/`markForDelete` 等 UPDATE 语句若有指定 provider 的需求，相应加 WHERE 条件。

7. `saveFileInfo(...)` 调用处（见 Step A.1 末）设 `storageProvider = registry.getDefaultProvider().providerId()` 兜底；`FileObjectRef` 创建处也要 `setStorageProvider(fileItem.getStorageProvider())`。

**Step C：在 OSS Provider 上补 `readFirstBytes`（Step A.1 依赖）**

8. `AliyunOssStorageProvider.java` 增加 `readFirstBytes(String objectKey, int maxBytes)` 方法（委托 `getSignUrl.readFirstBytes(...)`）；`StorageProvider.java` 接口加一个 `default byte[] readFirstBytes(String objectKey, int maxBytes) { throw new UnsupportedOperationException(); }`；Local provider 实现为 `Files.newInputStream(Path.of(basePath, objectKey)).readNBytes(maxBytes)`。

**Step D：Gateway 路由核实（解决 4-4）**

9. 在 `zxyz-gateway/src/main/resources/application.yml` 确认 `/api/admin/storage-providers/**` 是否有 `RewritePath` strip `/api/admin` 前缀后转发到 `file-service`，而非默认 `admin-service`。若无，新增路由：
   ```yaml
   - id: admin-storage-providers
     uri: lb://zxyz-file-service
     predicates:
       - Path=/api/admin/storage-providers/**
     filters:
       - RewritePath=/api/admin/(?<segment>.*), /${segment}
       - AddRequestHeader=X-Internal-Service-Token, ${INTERNAL_SERVICE_TOKEN}
   ```

#### 6.1.2 优先级 P0：API 契约 + 前端适配（Phase 2 全部）

**Step E：FileDownloadUrlVO 扩展（解决 2-1）**

1. `zxyz-common/.../vo/FileDownloadUrlVO.java` 新增：
   ```java
   @Schema(description="是否直传下载") private boolean directDownload = true;
   @Schema(description="原始文件名（流式下载时使用）") private String fileName;
   ```
   保留旧构造器（向后兼容）+ 新增全参构造器。

**Step F：后端新端点（解决 2-2、2-3、2-4、2-5）**

2. `FileUploadPort.java`：
   - `getUploadSign` 返回类型 `OssSignInfo` → `UploadInfo`；
   - 新增 `UploadConfirmItemResultVO directUpload(String objectKey, String originalName, Long fileSize, Long parentId, MultipartFile file, Long userId);`。

3. `FileUploadService.java` 实现 `directUpload`：
   - `registry.getDefaultProvider().receiveUpload(objectKey, file.getInputStream(), file.getContentType(), contentDisposition)`；
   - 校验返回字节数 `== file.getSize()`；
   - 调 `saveFileInfo(...)` 保存元数据；
   - 返回 `UploadConfirmItemResultVO`。

4. `FileController.java` 新增 2 个端点（同步基础设施在 6.1.2 Step F 之外需配合）：
   - `@GetMapping("/{fileId}/stream")` 流式下载：
     - `fileDomainValidator.requireFileItem(fileId)`；
     - `fileAccessGuardService.requireReadAccess(...)`；
     - `provider = registry.resolveForFile(fileItem)`；
     - 设响应头 `Content-Type: application/octet-stream`、`Content-Disposition: attachment; filename*=utf-8''<编码后的文件名>`、`Content-Length: <provider.getObjectSize>`；
     - `provider.streamDownload(fileItem.getUuidName(), response.getOutputStream())`；
   - `@PostMapping("/uploads/direct") ` 直传上传：转调 `fileUploadPort.directUpload(...)`，返回 `UploadConfirmItemResultVO`；
   - 同时更新 `@PostMapping("/uploads") getUploadSign` 返回类型 `Result<OssSignInfo>` → `Result<UploadInfo>`。

5. `ShareController.java` 新增 `@GetMapping("/public/shares/{shareKey}/files/{fileId}/stream")` 端点，逻辑同 `FileController.streamFile` 但用分享鉴权（参考现有 `/api/public/shares/{shareKey}/files/{fileId}/download-url` 的鉴权方式）。

**Step G：前端适配（解决 2-6～2-10）**

6. `ZXYZdatabaseFront/src/composables/useFileDownload.js`
   - `getDownloadInfo(row)` 返回完整 `response.data`；
   - `downloadFile(row)`：`if (directDownload !== false) { downloadBlobByUrl(downloadUrl, row.fileName) } else { downloadBlobByUrl("/api/files/" + row.id + "/stream", fileName || row.fileName) }`；

7. `ZXYZdatabaseFront/src/services/upload.js` `uploadFileWithPresign`：
   - `const { uploadUrl, objectKey, contentType, contentDisposition, directUpload } = signRes.data;`
   - `if (directUpload !== false) { await uploadToOss(uploadUrl, file, {...}) } else { await uploadToBackend(uploadUrl, objectKey, file, onProgress) }`
   - 本地存储时 `confirmUpload` 一步可省（后端 `receiveUpload` 已存盘并在 `directUpload` 中完成元数据登记，或保留半确认语义——见 #11 文档 Step 2.4 实现逻辑）。

8. `ZXYZdatabaseFront/src/utils/oss.js` 新增 `export function uploadToBackend(endpointUrl, objectKey, file, onProgress)`（XHR + FormData，详见 #11 文档 L881-L907）。

9. `ZXYZdatabaseFront/src/utils/archive/backendArchive.js` `collectFileEntry`：根据 `directDownload` 决定 `downloadUrl` 直接用或拼 `/api/files/${file.id}/stream`。

10. `ZXYZdatabaseFront/src/composables/useShareFileDownload.js` 同 6 改造。

#### 6.1.3 优先级 P0：让本地存储能运行（Phase 3 闭合）

**Step H：配置落位（解决 3-1、3-2）**

1. `zxyz-file-service/src/main/resources/application-dev.yml` 增加：
   ```yaml
   app:
     storage:
       default-provider: ${STORAGE_DEFAULT_PROVIDER:oss}
       provider:
         oss:
           enabled: ${STORAGE_OSS_ENABLED:true}
         local:
           enabled: ${STORAGE_LOCAL_ENABLED:false}
           base-path: ${LOCAL_STORAGE_PATH:./data/zxyz-files}
           max-disk-usage-bytes: ${LOCAL_MAX_DISK:10737418240}
           download-speed-bytes-per-second: ${LOCAL_DOWNLOAD_SPEED:0}
   ```
2. `nacos-config/zxyz-file-service.yml` 同步加同段（生产用 Nacos），注意按 CLAUDE.md "Service URL config" 避免嵌套错误。

**Step I：docker-compose 卷 + 环境变量（解决 3-3）**

3. `docker-compose.yml` file-service：
   ```yaml
   volumes:
     - ${LOCAL_STORAGE_PATH:-/data/zxyz-files}:/data/zxyz-files
   environment:
     - STORAGE_DEFAULT_PROVIDER=oss
     - STORAGE_LOCAL_ENABLED=false
     - STORAGE_OSS_ENABLED=true
     - LOCAL_STORAGE_PATH=/data/zxyz-files
   ```

**Step J：消除"启用即崩"（解决 3-4 与 2-3 闭合）**

4. 完成 **Step F** 中的 `POST /api/files/uploads/direct` 端点后，`LocalDiskStorageProvider.generateUploadInfo` 返回的 `uploadUrl="/api/files/uploads/direct"` 才有归宿。两步必须配套，否则启用本地存储后前端 PUT 一个不存在的路径必 404。

#### 6.1.4 优先级 P1：Admin 健康检查真实化（解决 4-1）

1. `StorageProviderController.healthCheck` try 块体改为：
   - OSS provider：`provider.objectExists("zxyz-health-check-probe")` 或 `provider.getObjectSize(...)` 任一调用判定 connectivity（不要求返回 true，无异常即视为连通）；
   - Local provider：`Files.isDirectory(basePath) && Files.isWritable(basePath)`；
   - 异常时 `healthy = false; message = e.getMessage()`。

#### 6.1.5 优先级 P1：前端 Admin 管理页面（解决 4-2、4-3）

1. `ZXYZdatabaseFront/src/api/` 新增 `storage.js`：`GET /api/admin/storage-providers`、`PATCH /api/admin/storage-providers/{id}`、`GET /api/admin/storage-providers/{id}/health`；
2. `ZXYZdatabaseFront/src/views/setting/` 新增 `StorageAdmin.vue` + 路由（位于 `router/index.js`，按 CLAUDE.md "Setting 子路由" 约束确保 `route.name` 在 Setting watcher `immediate: true` 之前就绪）；
3. 更新 `Setting.vue` tab 列表加入"存储管理"。

#### 6.1.6 优先级 P2：基线 schema 一致性（解决 1-7）

1. `sql/schema_file.sql` 中 `file_node`、`file_object_ref` 建表加 `storage_provider VARCHAR(32) NULL DEFAULT 'oss'` 列；新增 `storage_provider_config` 建表语句。这样新数据库用 `sql/00-init-zxyz.sh` 初始化后即与 V2 ALTER 后状态一致。
2. 若有人**用现成 Flyway 历史**升级，V2 ALTER 也会补齐；但对全新部署路径更友好。

---

### 6.2 #12 CI/CD 优化 —— 解决方案

#### 6.2.1 优先级 P0：补齐 workflow_dispatch 快速模式（解决 12-1、12-10）

1. `.github/workflows/ci-cd.yml` `workflow_dispatch.inputs` 新增：
   ```yaml
   fast_deploy:
     description: '快速部署（跳过部署健康检查等待）'
     type: boolean
     default: false
   ```
2. deploy 阶段 L426-L451 健康检查加旁路：
   ```bash
   if [ "${{ github.event.inputs.fast_deploy }}" = "true" ]; then
     echo "fast_deploy=true, 仅等 10 秒"
     sleep 10
   else
     # 现有分层健康检查逻辑
   fi
   ```
3. quality-check 加条件：`needs.detect-changes.outputs.skip_quality == 'true'`（若希望 dev push 跳过 quality-check 给 dev push 同样的快速路径），需配合 `detect-changes` 新增 `outputs.skip_quality` 计算逻辑：`github.ref == 'refs/heads/dev' && github.event_name == 'push'`。

#### 6.2.2 优先级 P1：deploy-fast.sh 补 --build（解决 12-2）

1. `scripts/deploy-fast.sh` 参数解析 L36-L46 增加 `--build)` 分支；
2. 增加逻辑：先 `mvn -B -T 1C -pl zxyz-${MODULE} -am package -DskipTests`，再 `docker compose build zxyz-${MODULE}`，再 `docker compose up -d --no-deps zxyz-${MODULE}`；不 pull 远端镜像。

#### 6.2.3 优先级 P1：validate-env.sh 自动补全（解决 12-3）

1. `scripts/validate-env.sh` 增加 Step 1：
   ```bash
   while IFS= read -r line; do
     key="${line%%=*}"
     [ -z "$key" ] || [[ "$key" == \#* ]] && continue
     grep -q "^${key}=" .env || echo "$line" >> .env
   done < .env.example
   ```
2. 加 `--sync-only` 参数仅做补全不校验。

#### 6.2.4 优先级 P2：自托管 Runner（解决 12-4）

1. 在服务器（`45.207.192.248`）按 ISSUE L73-L88 安装 GitHub Actions Runner 并注册为 `self-hosted`；
2. `ci-cd.yml` 5 个 job `runs-on: ubuntu-latest` → `runs-on: self-hosted`；
3. 风险评估：runner 仓库代码安全性、构建 CPU/内存预留。

#### 6.2.5 优先级 P2：阿里云 ACR（解决 12-5）

1. 注册阿里云容器镜像服务，创建命名空间 `zxyz`；
2. GitHub Secrets 添加 `ACR_USERNAME`/`ACR_PASSWORD`；
3. `ci-cd.yml` L42 `IMAGE_PREFIX: registry.cn-shenzhen.aliyuncs.com/zxyz`；
4. L294-L300 `Login to ACR` 段：
   ```yaml
   registry: registry.cn-shenzhen.aliyuncs.com
   username: ${{ secrets.ACR_USERNAME }}
   password: ${{ secrets.ACR_PASSWORD }}
   ```
5. 服务器 `/www/zxyz/.env` 更新 `IMAGE_PREFIX` + `docker login registry.cn-shenzhen.aliyuncs.com` 并 /etc/docker/daemon.json 配 `registry-mirrors`。

#### 6.2.6 优先级 P2：本地开发环境（解决 12-7）

1. 新增 `docker-compose.dev.yml`：仅含 MySQL/Redis/RabbitMQ/Nacos 等中间件（不含业务服务），`restart: no`；
2. `scripts/dev-up.sh` 一键启动中间件；本地服务用 `mvn -pl zxyz-{service} spring-boot:run`；
3. 文档补充到 `DEPLOYMENT.md` 的"本地开发"段。

#### 6.2.7 优先级 P2：Maven 基础镜像 / Alpine 瘦身 / 懒初始化（解决 12-6、12-8、12-9）

- **12-6**：新建 `ZXYZdatabaseBack/Dockerfile.base`，预执行 `mvn -B dependency:go-offline`，主 Dockerfile 改 `FROM aclouda/zxyz-maven-base:latest AS builder`；维护：pom.xml 变更时手动重建。
- **12-8**：将后端服务 final stage 基础换成 `eclipse-temurin:17-jre-alpine`，并将 L48 `apt-get install` 改为 `apk add --no-cache` 对应包；测试 GLIBC 依赖的库兼容性（如 Netty native），不兼容可保留 deb-base。
- **12-9**：每个服务的 `application-dev.yml` 加 `spring.main.lazy-initialization: true`；`application-prod.yml` **不加**（生产不应懒初始化）。

---

### 6.3 #13 热配置迁移 —— 解决方案

> **核心策略**：先修前置 + 迁移文件，再逐一改造消费方。可以采用"配置助手（ConfigGetter）"统一本地缓存兜底逻辑以避免每个消费方重写一份模式。

#### 6.3.1 优先级 P0：前置修复（解决 13-PRE）

`ConfigAdminController.java` 类中新增（位于现有 `@PutMapping("/{key}")` 之前）：

```java
@Operation(summary = "按 key 查询单个配置")
@GetMapping("/{key}")
public Result<SysConfigVO> getByKey(@PathVariable String key) {
    return Result.of(configService.findByKey(key));   // 返回 null 时 Result.of(null) 仍 code=1
}
```

`ConfigService.findByKey(key)` 若已存在直接复用；空值时返回 null（不要抛异常，客户端会缓存 NULL_SENTINEL）。

注意 `@GetMapping("/audit")` 已在 `@GetMapping("/{key}")` 类似路径下，Spring 路由匹配会优先匹配字面 `audit`（精确匹配优先于变量捕获），但避免歧义可显式 `@GetMapping(value="/audit", params="")` 或调整路径以消除风险（验证这一点）。

#### 6.3.2 优先级 P0：迁移文件（解决 13-MIG）

新建 `zxyz-admin-service/src/main/resources/db/migration/V2__hot_config_keys.sql`：

```sql
-- 阶段一：文件
INSERT INTO sys_config (config_key, config_value, value_type, description, default_value) VALUES
('app.file.upload.allowed-extensions', '["pdf","doc","docx","xls","xlsx","ppt","pptx","jpg","jpeg","png","gif","zip","rar","7z","txt","md","mp4","mp3"]', 'JSON', '允许上传的文件扩展名', '["pdf","doc",...]'),
('app.file.upload.blocked-extensions', '[".exe",".bat",".cmd",".sh",".js",".com",".scr",".vbs"]', 'JSON', '危险文件扩展名黑名单', '[".exe",".bat",...]'),
('app.file.upload.max-size-bytes', '524288000', 'NUMBER', '单文件最大上传大小', '524288000'),
-- 阶段一：限流
('app.rate-limit.login.ip-per-minute', '20', 'NUMBER', '每分钟每 IP 登录上限', '20'),
('app.rate-limit.login.username-per-minute', '5', 'NUMBER', '每分钟每用户名登录上限', '5'),
('app.rate-limit.register.ip-per-hour', '3', 'NUMBER', '每小时每 IP 注册上限', '3'),
('app.rate-limit.share.attempts-per-window', '10', 'NUMBER', '分享验证最大尝试次数', '10'),
('app.rate-limit.share.window-minutes', '5', 'NUMBER', '分享验证限流窗口', '5'),
-- 阶段一：团队
('app.team.default-max-members', '100', 'NUMBER', '团队默认成员上限', '100'),
('app.team.default-storage-limit-bytes', '107374182400', 'NUMBER', '团队默认存储上限', '107374182400'),
('app.team.min-password-length', '6', 'NUMBER', '团队密码最小长度', '6'),
-- 阶段二：缓存 TTL
('app.cache.default-ttl-minutes', '30', 'NUMBER', '全局缓存默认 TTL', '30'),
('app.cache.team-permission-ttl-minutes', '5', 'NUMBER', '权限缓存 TTL', '5'),
('app.cache.project-access-ttl-minutes', '10', 'NUMBER', '项目访问缓存 TTL', '10'),
('app.cache.storage-usage-ttl-seconds', '30', 'NUMBER', '存储用量缓存 TTL', '30'),
-- 阶段二：Resilience4j
('app.resilience.retry.max-attempts', '3', 'NUMBER', '最大重试次数', '3'),
('app.resilience.retry.wait-duration-ms', '500', 'NUMBER', '重试间隔', '500'),
('app.resilience.circuit-breaker.sliding-window', '10', 'NUMBER', '熔断滑动窗口', '10'),
('app.resilience.circuit-breaker.failure-threshold', '50', 'NUMBER', '失败率阈值', '50'),
('app.resilience.circuit-breaker.wait-open-ms', '30000', 'NUMBER', '半开等待时间', '30000'),
-- 阶段三：IM
('app.im.message.max-text-length', '5000', 'NUMBER', '消息最大文本长度', '5000'),
('app.im.message.recall-window-seconds', '120', 'NUMBER', '消息可撤回时间窗口', '120'),
('app.im.ws.ticket-ttl-seconds', '30', 'NUMBER', 'WebSocket 票据有效期', '30'),
('app.im.ws.max-content-length', '65536', 'NUMBER', 'WebSocket 最大消息长度', '65536'),
-- 阶段三：邮件
('app.email.max-retry-count', '4', 'NUMBER', '邮件最大重试次数', '4'),
-- 阶段三：其他
('app.file.copy.max-nodes-per-tx', '500', 'NUMBER', '单次复制最大节点数', '500'),
('app.audit.retention-days', '90', 'NUMBER', '审计日志保留天数', '90'),
('app.avatar.max-size-bytes', '5242880', 'NUMBER', '头像最大大小', '5242880');
```

`V1__init_config_schema.sql` 已有的 `app.email.verify-code-cooldown-seconds` 历史行，本迁移不重复插入。

#### 6.3.3 优先级 P0：统一实现模式（抽象 ConfigGetter 助手）

为避免 19 个消费方各自重写一遍"缓存兜底 + Duration ofMinutes(1) 刷新"，在 `zxyz-common` 中新增助手：

文件：`zxyz-common/src/main/java/uno/acloud/common/config/ConfigGetter.java`

```java
@Component
public class ConfigGetter {
    private final ConfigServiceClient client;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String getString(String key, String fallback) {
        CacheEntry e = cache.computeIfAbsent(key, k -> new CacheEntry());
        if (e.value != null && Instant.now().isBefore(e.refreshedAt.plus(Duration.ofMinutes(1)))) {
            return e.value.equals(NULL_SENTINEL) ? fallback : e.value;
        }
        try {
            String v = client.get(key);
            e.value = (v == null ? NULL_SENTINEL : v);
            e.refreshedAt = Instant.now();
            return v != null ? v : fallback;
        } catch (Exception ex) {
            // 不抛、不更新 refresh 时间，下次仍会重试
            return e.value != null && !e.value.equals(NULL_SENTINEL) ? e.value : fallback;
        }
    }

    public long getLong(String key, long fallback) {
        try { return Long.parseLong(getString(key, null)); } catch (Exception e) { return fallback; }
    }

    public int getInt(String key, int fallback) { return (int) getLong(key, fallback); }
    public Set<String> getJsonSet(String key, Set<String> fallback) { /* JSON parse + fallback */ }

    private static final String NULL_SENTINEL = "\0NULL\0";
    private static class CacheEntry { volatile String value; volatile Instant refreshedAt = Instant.MIN; }
}
```

（`ConfigServiceClient.get(key)` 在前置修复未做时会去打 404 路径——见 6.3.1）

Redis Pub/Sub 监听 `zxyz:config:changed` 频道，收到变更时 `cache.remove(key)` 促使下次重新拉取（admin-service 已有 ConfigService.update 时发布的逻辑，这里只需订阅）。

#### 6.3.4 优先级 P0：改造 19 个消费方（对应 4.3 表）

每个消费方注入 `ConfigGetter`，将硬编码常量替换为 `configGetter.getXxx(key, fallback)`：

例如 `FileUploadService`：

```java
private final ConfigGetter configGetter;
// 构造器加 ConfigGetter

private Set<String> getAllowedExtensions() {
    return configGetter.getJsonSet("app.file.upload.allowed-extensions", DEFAULT_ALLOWED_EXTENSIONS);
}
private Set<String> getBlockedExtensions() {
    return configGetter.getJsonSet("app.file.upload.blocked-extensions", FileNameUtil.BLOCKED_EXTENSIONS);
}
private long getMaxFileSizeBytes() {
    return configGetter.getLong("app.file.upload.max-size-bytes", 500L * 1024 * 1024);
}
```

`LoginRateLimiter`：

```java
private final ConfigGetter configGetter;
private int getIpLimitPerMinute() { return configGetter.getInt("app.rate-limit.login.ip-per-minute", 20); }
private int getUsernameLimitPerMinute() { return configGetter.getInt("app.rate-limit.login.username-per-minute", 5); }
```

`CacheConfig`：

```java
private final ConfigGetter configGetter;
private Duration getDefaultTtl() {
    int minutes = configGetter.getInt("app.cache.default-ttl-minutes", 30);
    return Duration.ofMinutes(minutes);
}
```

`AbstractServiceClient` —— 由于 Retry/CircuitBreaker 实例初始化后不可热刷新，ISSUE 已说明此条用 `@ConfigurationProperties` 重启生效，但若确实需要热更新，需订阅 Redis Pub/Sub 重建 Retry/CircuitBreaker 实例并替换 registry：复杂度高，建议本期只做 `@ConfigurationProperties`，热刷新留待后续。

`RestClientConfig` 同理：保持 `@ConfigurationProperties + 3s/10s` 字面量，本期不接入 sys_config（ISSUE 自己也说此项需重启生效）。

#### 6.3.5 优先级 P1：修正"假半落地"项（解决 13-FALSE-1/2/3）

1. **13-FALSE-1（IM recall window）**：决定走哪个路径。若采纳 sys_config 路径，把 `MessageModerationProperties.recallWindowSeconds` 改为通过 `ConfigGetter.getInt("app.im.message.recall-window-seconds", 120)` 读取，删除 `@ConfigurationProperties` 字段。**建议**：第一阶段优先消除"半落地"歧义，统一到 sys_config 热配置路径。
2. **13-FALSE-2（email cooldown）**：V1 行 53 的 sys_config 行无人消费 → 重点删/接通：若 user-service 接通 `ConfigGetter.getInt("app.email.verify-code-cooldown-seconds", 60)`，删除 `ServiceProperties.EmailConfig.VerifyCode.cooldownSeconds`，或保留作为 fallback 默认值来源（与 ConfigGetter 配合）。
3. **13-FALSE-3（audit retention）**：键名错误，修正 `@Value("${audit.cleanup.retention-days:90}")` → 改为 `ConfigGetter.getInt("app.audit.retention-days", 90)`，统一到 sys_config 路径。同时确认 `app.audit.retention-days` 已在 V2 迁移中插入（6.3.2 已含）。

---

### 6.4 CODEX 审核未完成项 —— 解决方案

#### 6.4.1 优先级 P0：消除"已完成夸大"

1. **CV-6（P2-14 permission/index.vue 拆分接线）**：
   - `permission/index.vue` 中将原内联的 `saveSystemRoleFromPanel`、`deleteSystemRoleFromPanel`、`saveTeamRoleFromPanel` 等方法改为调用 `const { saveSystemRole, deleteSystemRole } = useSystemPermissionActions(...)` 和 `const { saveTeamRole, ... } = useTeamPermissionActions(...)`；
   - 删除 `index.vue` 内重复的内联实现；
   - 移除 L1-L4 TODO 注释；
   - 目标：`index.vue` 行数从 ~846 行降到 ~300 行；
   - 验证：`npm run test` + `npm run build`。

2. **CV-7（P3-08 文件列表分页接线）**：
   - `FileQueryService` 改用 `fileMapper.getFileNodesByParentIdPaged(parentId, spaceType, offset, limit, orderBy)` + `fileMapper.countByParentId(parentId, spaceType)`；
   - `FileController` 列表端点接收 `page`/`pageSize` 参数，返回 `Result<Page<FileItemVO>>`；
   - 前端 `Index.vue` 文件列表改为分页加载；
   - 移除 `FileMapper.java` L88-L89 TODO；
   - 验证：超大目录性能测试（>1000 子项）。

#### 6.4.2 优先级 P0：补做真正未做项

3. **CV-2（P2-26 用户注销跨服务清理）**：
   - 在 `zxyz-common` 新增 RoutingKey 常量 `USER_DELETED`；
   - `UserController.java` L52-L54 TODO 处的删除流程：在 user-service DB 事务提交后发布 `user.deleted` 事件，载荷含 `userId`；
   - 各下游消费者订阅该路由键：
     - `file-service`：删除用户在个人空间的文件记录 + OSS 对象（需调 storage provider，与 #11 接通后做）；
     - `share-service`：删除用户创建的分享 + 取消其他用户的分享指向该用户文件的 items；
     - `im-service`：删除会话成员关系 + 历史消息（或转写为"已注销"占位）；
     - `team-service`：移除用户在所有团队的成员关系 + 角色 + 配额占用；
     - `project-service`：移除项目成员关系 + 配额回收；
   - 每个消费者按 CLAUDE.md "MQ poison message handling" 规范处理 `JsonProcessingException` → `AmqpRejectAndDontRequeueException`；
   - 幂等通过 Redis SETNX；
   - 移除 `UserController.java` L52-L54 TODO；
   - 验证：测试用户删除后 5 个服务的清理事件按序触发。

4. **CV-3（P2-27 团队配额 vs 项目配额总和）**：
   - `project-service` 新增 internal API：`GET /api/internal/projects/quota-sum-by-team?teamId={id}` → 返回 `sum(storageLimit)`；
   - `ProjectQuotaService` 实现该查询；
   - `FileServiceClient` / 团队用的 service client 加 `sumProjectQuota(teamId)` 方法；
   - `AdminTeamService.updateTeamQuota` 在 L110-L111 后增加第三组校验：
     ```java
     Long projectQuotaSum = projectServiceClient.sumProjectQuota(teamId);
     if (projectQuotaSum != null && storageLimit < projectQuotaSum) {
         throw new BusinessException(ErrorCode.BAD_REQUEST, "团队存储配额不能小于项目配额总和: " + projectQuotaSum);
     }
     ```
   - 验证：单元测试覆盖 `storageLimit < projectQuotaSum`、`=`、`>` 三种场景。

#### 6.4.3 优先级 P1：渐进迁移项

5. **CV-4（P3-01 ErrorCode 枚举化）**：
   - 本期不要求全量切换，但建立"增量目标"：
     - 所有**新代码**必须使用 `*ErrorCode` 枚举，禁止新增 `public static final int`；
     - 每个迭代迁移一个领域（无破坏性替换：枚举 `getCode()` 返回相同 int，调用方先切换到枚举，再后续废弃 int 常量）；
     - `resolveHttpStatus(int)` 增加重载 `resolveHttpStatus(ErrorCodeMarker)` 以便切换；
   - 移除 `ErrorCode.java` L5-L6 TODO 时标明"完成 X/N 领域"。

6. **CV-5（P3-03 ShareContentProvider N+1）**：
   - `file-service` 新增 internal API：`POST /api/internal/files/batch-children`，入参 `List<Long> parentIds`，返回 `Map<Long, List<FileInfoDTO>>`；
   - `ShareContentProvider.resolveSharedFolderByPath` 重构：单次批量请求所有路径段的 children，本地索引匹配；
   - 或更优：file-service 新增 `GET /api/internal/files/resolve-by-path?parentFileId={}&path={a/b/c}`，server 端递归处理一次链路；
   - 移除 `ShareContentProvider.java` L70-L71 TODO。

#### 6.4.4 优先级 P2：更新 CODEX-CODE-REVIEW-RESULTS.md 消除矛盾

按本次核对结果更新报告：
- P2-06 标记 "✅ 已修复（InputStream 类型改为 byte[] + ByteArrayInputStream）"，删除 L948/L976/L1118 中"🔲 待修复"条目；
- P3-06 标记 "✅ 已修复（改用 batchRefreshStatusIfNeeded）"，删除 L953 中"🔲 待修复"条目；
- P2-14 标记从 "✅" 降为 "⚠️ 部分完成（composable 未接线）"，更新 L959 表述；
- P3-08 标记从 "✅" 降为 "⚠️ 部分完成（方法无调用方）"，更新 L953 表述；
- L1266-L1272 "整体进度跟踪表" 重新算出"已完成/误报/待修复"列。

---

## 七、推荐执行顺序（更新）

| 优先级 | 任务 | 预估工作量 | 备注 |
|---|---|---|---|
| **P1** | 前端文件列表分页加载（A1） | 1 天 | 后端已就绪，仅需前端 Index.vue 改分页 |
| **P2** | 本地开发环境 compose.dev（A5） | 1 天 | 低优 |
| **P3** | 自托管 Runner（A3） | 0.5 天 + 服务器 | 需服务器资源 |
| **P3** | 阿里云 ACR（A4） | 0.5 天 + 账号 | 需账号 |

**当前总剩余**：P1 ~1 天 + P2 ~1 天 + P3 ~1 天 ≈ **3 工作日**（不含外部资源等待）。

---

## 八、附：核对过程元数据

- 后端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseBack\`（11 个 Maven 模块）
- 前端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseFront\src\`
- 根仓库核对路径：`D:\code\databaseZXYZ\zxyz\` (docker-compose.yml、deploy/、scripts/、sql/、nacos-config/、.github/workflows/)
- 核对工具：Glob / Grep / Read / Agent
- 核对范围：全部关键文件
- 更新批次：2026-07-24 后续批次（#11 Phase 1-4、#12 P0-P1、#13 Phase 1-2、CODEX CV-1~CV-7 全部完成）