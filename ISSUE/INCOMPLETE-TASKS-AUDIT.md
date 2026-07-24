# 未完成项目核对报告（2026-07-24 第三轮）

> 核对原则：**以代码为准**。所有结论基于对源码、配置、迁移脚本的直接核对，不依赖文档声称的状态。
> 覆盖范围：
> - `ISSUE/11-MULTI-STORAGE-PROVIDER.md`（多存储方案集成）
> - `ISSUE/12-CICD-PERFORMANCE-OPTIMIZATION.md`（CI/CD 流水线性能优化）
> - `ISSUE/13-hot-config-migration.md`（硬编码配置值迁移到热配置系统）
> - `ISSUE/CODEX-CODE-REVIEW-RESULTS.md`（代码审核问题）
>
> 本文档只列出**未完成**或**部分完成**的项目；已完全落地的项目不在本文范围内。

---

## 一、整体状态总表

| ISSUE | 总项数 | 完全完成 | 部分完成 | 完全未做 | 完成度 |
|---|---|---|---|---|---|
| #11 多存储方案 | 27 | 21 | 4 | 2 | **~85%** |
| #12 CI/CD 优化 | 10 | 7 | 0 | 3 | **70%** |
| #13 热配置迁移 | 19 消费方 + 3 基础 | 16 消费方 + 3 基础 | 0 | 1 消费方 + 2 设计豁免 | **~90%** |
| CODEX 审核未完项 | 9 | 7 | 2 | 0 | **~90%** |

**真正未完成项合计：12 项**（含 3 个需外部资源的低优项 + 3 个配置键名 bug + 6 个功能缺陷）。

---

## 二、ISSUE #11 多存储方案集成 —— 未完成项

### 2.1 已完成项（不再列出，共 21 项）

Phase 1（1-1～1-8）全部完成：4 个 File 服务类已注入 `StorageProviderRegistry`、Mapper INSERT/SELECT 含 `storage_provider`、schema 含 `storage_provider_config` 表、`saveFileInfo` 设 `storageProvider`、`FileRenameService` 有 afterCommit 事务模式。

Phase 2 后端（2-1～2-5、2-7、2-8、2-11）完成：`FileDownloadUrlVO` 有 `directDownload`+`fileName`、`FileController` 有 `GET /{fileId}/stream` + `POST /uploads/direct`、`PublicShareController` 有 stream 端点、`FileUploadPort` 返回 `UploadInfo` + `directUpload` 方法、`upload.js` + `oss.js` 已适配。

Phase 3 配置（3-1、3-2、3-3）完成：`application-dev.yml` + Nacos 有 `app.storage` 配置、`docker-compose.yml` 有 volumes + `LOCAL_STORAGE_PATH`。

Phase 4（4-1、4-2）完成：`StorageProviderController.healthCheck` 调真实 `provider.healthCheck()`、`StorageAdmin.vue` 存在且有路由守卫。

### 2.2 未完成项

| 编号 | 项 | 状态 | 严重度 | 证据 |
|---|---|---|---|---|
| **11-A** | `useFileDownload.js` 的 `directDownload` 分支是空操作 | ⚠️ 部分完成 | **严重** | `useFileDownload.js` L40-46：`if (directUpload) { await downloadBlobByUrl(downloadUrl, fileName) } else { await downloadBlobByUrl(downloadUrl, fileName) }` — 两个分支代码完全相同，未区分直传下载 vs 流式下载 |
| **11-B** | `backendArchive.js` 读取 `directDownload` 但未分支处理 | ⚠️ 部分完成 | **严重** | `backendArchive.js` L62 读取 `directDownload`、L72 将其放入返回对象，但函数内无任何 `if (directDownload)` 分支逻辑 — 标志被传播但从未被消费 |
| **11-C** | `useShareFileDownload.js` 完全未处理 `directDownload` | ❌ 未完成 | **致命** | `useShareFileDownload.js` L18 仅取 `response?.data?.downloadUrl`、L24 直接 `downloadBlobByUrl(downloadUrl, row.fileName)`。grep `directDownload` 零命中。**后果**：本地存储的分享文件 `downloadUrl=null`（`LocalDiskStorageProvider.java` L79），分享下载报"未获取到下载链接"；后端 `PublicShareController` L83-90 的 stream 端点无前端调用方 |
| **11-D** | `LocalDiskStorageProvider.generateUploadInfo` 返回 `directUpload=false`，与前端语义矛盾 | ⚠️ 部分完成 | **致命** | `LocalDiskStorageProvider.java` L71 返回 `directUpload=false`，注释说"非直传，需要经过后端"。但前端 `upload.js` L45-50 的逻辑是：`directUpload===true` → 调 `uploadToBackend`（POST 到后端），`directUpload===false` → 调 `uploadToOss`（PUT presigned URL）。本地存储返回 `uploadUrl="/api/files/uploads/direct"`（POST 端点）+ `directUpload=false`，前端会尝试 OSS presigned PUT 到一个 POST-only multipart 端点 → **本地存储做默认 provider 时上传路径断裂** |
| **11-E** | Gateway 路由顺序可能导致 `/api/admin/storage-providers/**` 被吞 | ⚠️ 待验证 | **严重** | `zxyz-gateway/application.yml` L133-137 定义了 `file-service-storage-providers` 路由，但位于 L125-131 的 `admin-service` catch-all 路由 `/api/admin/**`（含 RewritePath）**之后**。Spring Cloud Gateway 按声明顺序匹配，catch-all 可能先匹配 → 请求被转发到 admin-service 而非 file-service |
| **11-F** | 前端无 `StorageProviderVO` 类型定义 | ⚠️ 部分完成 | **低** | `api/storage.js` 已有 3 个 API 方法（L4/L8/L12），但无显式 VO 类型定义。纯 JS 项目可接受，但缺少类型约束 |

### 2.3 致命链分析

**链 1：本地存储上传断裂（11-D）**
```
LocalDiskStorageProvider.generateUploadInfo()
  → uploadUrl="/api/files/uploads/direct", directUpload=false
  → 前端 upload.js: directUpload===false → uploadToOss(uploadUrl, file)
  → OSS presigned PUT 到 /api/files/uploads/direct（POST-only multipart 端点）
  → 405 Method Not Allowed
```

**链 2：本地存储分享下载断裂（11-C）**
```
LocalDiskStorageProvider.generateDownloadInfo()
  → downloadUrl=null（无 presigned URL）
  → useShareFileDownload.js: 仅取 downloadUrl → null
  → 报错"未获取到下载链接"
  → 后端 PublicShareController stream 端点存在但无前端调用方
```

**链 3：流式下载前端空操作（11-A + 11-B）**
```
FileQueryService 返回 directDownload=false + downloadUrl=null（本地存储）
  → useFileDownload.js: 两个分支代码相同 → 都调 downloadBlobByUrl(null, fileName)
  → 下载失败
  → backendArchive.js: directDownload 被读取但未分支 → 打包时同样用 null URL
```

---

## 三、ISSUE #12 CI/CD 流水线性能优化 —— 未完成项

### 3.1 已完成项（7/10）

| 项 | 状态 | 证据 |
|---|---|---|
| workflow_dispatch fast_deploy | ✅ | `ci-cd.yml` L33-37 声明 input + L444-475 健康检查旁路 |
| deploy-fast.sh --build | ✅ | `deploy-fast.sh` L44 参数解析 + L99-118 构建逻辑 |
| validate-env.sh 自动补全 | ✅ | `validate-env.sh` L39-70 从 .env.example 补全 + L72-76 `--sync-only` |
| Dockerfile.base | ✅ | `Dockerfile.base` 存在（27 行），主 Dockerfile L18 `FROM aclouda/zxyz-maven-base:latest` |
| Alpine 瘦身 | ✅ | `Dockerfile` L47 `FROM eclipse-temurin:17-jre-alpine` |
| 懒初始化 | ✅ | 9 个服务 `application-dev.yml` 均有 `spring.main.lazy-initialization: true` |
| dev push 跳过 quality-check | ✅ | `ci-cd.yml` L145-152 计算 `skip_quality` + L159-162/L188-191 消费 |

### 3.2 未完成项

| 编号 | 项 | 状态 | 严重度 | 证据 | 备注 |
|---|---|---|---|---|---|
| **12-A** | 自托管 Runner | ❌ 未完成 | 中 | `ci-cd.yml` 5 个 job 全部 `runs-on: ubuntu-latest`，grep `self-hosted` 零命中 | 需服务器安装 GitHub Actions Runner |
| **12-B** | 阿里云 ACR | ❌ 未完成 | 中 | `ci-cd.yml` L47 `IMAGE_PREFIX: ghcr.io/...`、L315 `registry: ghcr.io`。`scripts/setup-acr.sh` 存在（切换脚本）但未启用 | 需阿里云容器镜像服务账号 |
| **12-C** | 本地开发环境 compose.dev | ❌ 未完成 | 低 | 无 `docker-compose.dev.yml`、无 `scripts/dev-up.sh` | 低优 |

---

## 四、ISSUE #13 热配置迁移 —— 未完成项

### 4.1 已完成项

- **13-PRE 前置修复** ✅：`ConfigAdminController.java` L58-65 有 `@GetMapping("/{key}")` 端点
- **13-MIG 迁移文件** ✅：`V2__hot_config_keys.sql` 存在，25 个 config key 跨 3 阶段
- **ConfigGetter 助手** ✅：`zxyz-common/.../config/ConfigGetter.java`（199 行），含 `getString`/`getInt`/`getLong`/`getJsonSet` + Caffeine 缓存 + Redis Pub/Sub 失效
- **13-FALSE-1** ✅：`MessageModerationProperties` 类不存在，`MessageModerationService` 已用 `ConfigGetter`
- **13-FALSE-3** ✅：`AuditLogCleanupService` 已用 `ConfigGetter.getInt("app.audit.retention-days", 90)`
- **13 消费方接入（13/16 完成）**：FileUploadService、LoginRateLimiter、RegisterRateLimiter、ShareAccessRateLimiter、EnterpriseTeamService、CacheConfig、StorageQuotaCacheService、ImMessageService、WsTicketService、ImNettyServer、EmailDispatchService、FileCopyService、AvatarUploadSignService 均已注入 ConfigGetter
- **13-FALSE-2** ✅（但见 13-C bug）：`ContactVerificationService` 已改用 `ConfigGetter.getInt("app.email.verify-code-cooldown-seconds", 60)`

### 4.2 未完成项

| 编号 | 项 | 状态 | 严重度 | 证据 |
|---|---|---|---|---|
| **13-A** | `GetSignUrl.java` 仍用静态 `BLOCKED_EXTENSIONS` | ❌ 未完成 | **中等** | `GetSignUrl.java` L23 `import static ...FileNameUtil.BLOCKED_EXTENSIONS`、L212 `BLOCKED_EXTENSIONS.contains(ext)`。未注入 `ConfigGetter`，`app.file.upload.blocked-extensions` 配置键在此处不被消费。CLAUDE.md 要求两处 BLOCKED_EXTENSIONS 保持同步，但此处未迁移 |
| **13-B** | `RestClientConfig`（9 个服务）未接入 | ⬜ 设计豁免 | 低 | 9 个 `RestClientConfig.java` 均硬编码 `connectTimeout(3s)`/`readTimeout(10s)`。V2 SQL 种了 `app.rest-client.*` 键但无人消费（死键）。各类 javadoc 明确标注"基础设施层参数，不接入 ConfigGetter"。ISSUE 本身也允许此项保持 `@ConfigurationProperties` |
| **13-C** | `AbstractServiceClient` Resilience4j 参数未接入 | ⬜ 设计豁免 | 低 | `AbstractServiceClient.java` L66-92 硬编码 `maxAttempts(3)`/`waitDuration(500ms)`/`slidingWindowSize(10)`/`failureRateThreshold(50)`/`waitDurationInOpenState(30s)`。V2 SQL 种了 `app.resilience.*` 键但无人消费（死键）。javadoc 明确标注不接入。Retry/CircuitBreaker 实例初始化后不可热刷新 |

### 4.3 配置键名 Bug（3 处 — 消费方读取的键与 V2 SQL 种的键不匹配，静默回退默认值）

| 编号 | 消费方 | 读取的键 | V2 SQL 种的键 | 后果 | 证据 |
|---|---|---|---|---|---|
| **13-BUG-1** | `FileCopyService.java` L59 | `app.file.copy.max-nodes-per-transaction` | `app.file.copy.max-nodes-per-tx` | DB 修改该行无效果，始终用 fallback 500 | `FileCopyService.java` L59 vs `V2__hot_config_keys.sql` L63 |
| **13-BUG-2** | `StorageQuotaCacheService.java` L61 | `app.cache.storage-usage-ttl-minutes` | `app.cache.storage-usage-ttl-seconds` | DB 修改该行无效果，始终用 fallback 10 | `StorageQuotaCacheService.java` L61 vs `V2__hot_config_keys.sql` L35 |
| **13-BUG-3** | `ContactVerificationService.java` L54 | `app.email.verify-code-cooldown-seconds` | **V2 SQL 中不存在此键** | 始终用 fallback 60，无法通过 DB 热配置 | `ContactVerificationService.java` L54；V2 SQL 仅含 `app.email.max-retry-count` |

---

## 五、CODEX-CODE-REVIEW 未完成项

### 5.1 已完成项（7/9）

| 项 | 状态 | 证据 |
|---|---|---|
| CV-1/CV-6 permission/index.vue 拆分 | ✅ | `useSystemPermissionActions.js`(104行) + `useTeamPermissionActions.js`(115行) 存在且被 index.vue L289/L291 import + L526-541 实例化 + 模板 L39-40/L86/L170-171/L214 调用。index.vue 从 ~846 行降到 669 行。无顶部 TODO |
| CV-2 用户注销跨服务清理 | ✅ | `UserAdminService.deleteUser` L41-66 事务提交后发 `user.deleted` 事件。5 个服务有 `UserDeletedEventConsumer`（file/share/team/project/im）。`RabbitMqConstants` L30 定义路由键 |
| CV-3 团队配额 ≥ 项目配额总和 | ✅ | `AdminTeamService.updateTeamQuota` L119-124 调 `projectServiceClient.sumProjectQuota(teamId)` 校验。project-service `InternalProjectController` L54-58 提供聚合 API。测试覆盖 |
| CV-5 ShareContentProvider N+1 | ✅ | `ShareContentProvider.resolveSharedFolderByPath` L103-128 改为单次批量 `getShareChildrenByParentIds`。file-service 有 `FileMapper.getShareChildrenByParentIdsWithDeleted` L183-195 |
| CV-8 OSS ranged GET magic bytes | ✅ | `FileUploadService.saveFileInfo` L397-403 调 `registry.getDefaultProvider().readFirstBytes(uuidName, 28)` + `FileTypeUtil.classify`。`GetSignUrl.readFirstBytes` L148-172 |
| CV-9 ShareManager 批量刷新 | ✅ | `ShareManager.getMyShares` L122-123 调 `batchRefreshStatusIfNeeded(shares)` 批量刷新 |
| CV-7 后端分页 | ✅ | `FileMapper.getFileNodesByParentIdPaged` L119-140 + `countByParentId` L142-157 被 `FileQueryService` L85/L88 调用。`FileController` L100-117 接收 `page`/`pageSize` 参数 |

### 5.2 未完成项

| 编号 | 项 | 状态 | 严重度 | 证据 |
|---|---|---|---|---|
| **CODEX-A** | CV-4 ErrorCode 枚举化：3 个枚举类零引用 | ⚠️ 部分完成 | **中等** | `UserErrorCode` 已被 13 处调用（AuthService/UserAdminService 等）。但 `TeamErrorCode`(33行)/`ShareErrorCode`(33行)/`ProjectErrorCode`(30行) 三个枚举类**零引用** — 是死代码。`ErrorCode.java` L11-49 仍为 `public static final int` 常量，L5-7 TODO 承认待迁移。各域调用点仍用 `ErrorCode.TEAM_NOT_FOUND` 等 int 常量而非 `TeamErrorCode.TEAM_NOT_FOUND` 枚举 |
| **CODEX-B** | CV-7 前端文件列表分页未接线 | ⚠️ 部分完成 | **严重** | 后端已就绪（Mapper + Service + Controller 全链路支持 `page`/`pageSize`，默认 pageSize=50）。但前端**无调用方发送分页参数**：`useSpaceFileList.js` L73-76 仅传 sortOptions + spaceParams；`filePathResolver.js` L91-97 仅组 sortOptions；`FileExplorer.vue` grep `el-pagination`/`currentPage`/`pageSize` 零命中。**后果**：超过 50 个子项的目录被静默截断为前 50 个，无分页 UI、无"加载更多" |

---

## 六、解决方案

### 6.1 #11 多存储 —— 解决方案

#### 6.1.1 P0：修复 LocalDiskStorageProvider 的 directUpload 标志（解决 11-D）

**根因**：`UploadInfo.directUpload` 的语义是"前端是否直传到存储"（true=前端直传存储，false=前端传到后端）。但前端 `upload.js` 的分支逻辑是：`directUpload===true` → `uploadToBackend`（POST 后端），`directUpload===false` → `uploadToOss`（PUT presigned）。两者语义相反。

**修复方案**（改后端，影响最小）：
- `LocalDiskStorageProvider.java` L71：`directUpload=false` → `directUpload=true`
- 注释改为"本地存储：前端直传到后端 multipart 端点"
- 这样前端 `upload.js` L45-47 `directUpload===true` → `uploadToBackend(uploadUrl, ...)` → POST 到 `/api/files/uploads/direct` ✅

**验证**：将 `STORAGE_DEFAULT_PROVIDER=local` 后上传文件，确认走 `uploadToBackend` 路径。

#### 6.1.2 P0：修复 useShareFileDownload.js（解决 11-C）

`useShareFileDownload.js` 改造：
- L18 取完整 `response.data`（含 `directDownload` + `downloadUrl` + `fileName`）
- 增加 `directDownload === false` 分支：调 `downloadBlobByUrl("/api/public/shares/" + shareKey + "/files/" + fileId + "/stream", fileName)`
- `api/share.js` 增加对 stream 端点的支持（或直接拼 URL）

#### 6.1.3 P0：修复 useFileDownload.js 空操作分支（解决 11-A）

`useFileDownload.js` L40-46 改造：
- `directDownload !== false` 分支：`downloadBlobByUrl(downloadUrl, fileName)`（presigned URL 直下）
- `directDownload === false` 分支：`downloadBlobByUrl("/api/files/" + row.id + "/stream", fileName || row.fileName)`（流式下载）

#### 6.1.4 P1：修复 backendArchive.js（解决 11-B）

`backendArchive.js` `collectFileEntry`：
- 根据 `directDownload` 决定 `downloadUrl`：`directDownload !== false` → 用 `downloadUrl`；`directDownload === false` → 拼 `/api/files/${file.id}/stream`

#### 6.1.5 P1：修复 Gateway 路由顺序（解决 11-E）

`zxyz-gateway/application.yml`：将 `file-service-storage-providers` 路由（L133-137）移到 `admin-service` catch-all 路由（L125-131）**之前**。Spring Cloud Gateway 按声明顺序匹配，更具体的路由必须在 catch-all 之前。

```yaml
# 先匹配存储 provider 路由（更具体）
- id: file-service-storage-providers
  uri: lb://zxyz-file-service
  predicates:
    - Path=/api/admin/storage-providers/**
# 再匹配 admin catch-all
- id: admin-service
  uri: lb://zxyz-admin-service
  predicates:
    - Path=/api/admin/**
  filters:
    - RewritePath=/api/admin/(?<segment>.*), /${segment}
```

#### 6.1.6 P2：前端 VO 类型定义（解决 11-F）

纯 JS 项目可后续补充 JSDoc 类型注释，低优。

---

### 6.2 #12 CI/CD —— 解决方案

#### 6.2.1 P2：自托管 Runner（解决 12-A）

1. 在服务器安装 GitHub Actions Runner 并注册为 `self-hosted`
2. `ci-cd.yml` 5 个 job `runs-on: ubuntu-latest` → `runs-on: self-hosted`
3. 评估：runner 仓库代码安全性、构建 CPU/内存预留

#### 6.2.2 P2：阿里云 ACR（解决 12-B）

`scripts/setup-acr.sh` 已存在（切换脚本），需：
1. 注册阿里云容器镜像服务，创建命名空间
2. GitHub Secrets 添加 `ACR_USERNAME`/`ACR_PASSWORD`
3. 运行 `./scripts/setup-acr.sh enable` 切换
4. 服务器 `/www/zxyz/.env` 更新 `IMAGE_PREFIX` + `docker login`

#### 6.2.3 P3：本地开发环境（解决 12-C）

1. 新增 `docker-compose.dev.yml`：仅含 MySQL/Redis/RabbitMQ/Nacos
2. 新增 `scripts/dev-up.sh` 一键启动中间件

---

### 6.3 #13 热配置 —— 解决方案

#### 6.3.1 P1：迁移 GetSignUrl 的 BLOCKED_EXTENSIONS（解决 13-A）

`GetSignUrl.java` 是 `zxyz-common` 中的 OSS 工具类，被 `AliyunOssStorageProvider` 包装。两种方案：
- **方案 A（推荐）**：在 `AliyunOssStorageProvider` 层面拦截扩展名校验，从 `GetSignUrl` 中移除 `BLOCKED_EXTENSIONS` 检查，改为在 `FileUploadService` 统一用 `configGetter.getJsonSet("app.file.upload.blocked-extensions", FALLBACK)` 校验
- **方案 B**：给 `GetSignUrl` 注入 `ConfigGetter`（但 `GetSignUrl` 是 common 包的工具类，注入 Spring Bean 不太合适）

#### 6.3.2 P0：修复 3 个配置键名 Bug（解决 13-BUG-1/2/3）

| Bug | 修复方式 |
|---|---|
| **13-BUG-1** | `FileCopyService.java` L59：`app.file.copy.max-nodes-per-transaction` → `app.file.copy.max-nodes-per-tx`（与 V2 SQL L63 一致） |
| **13-BUG-2** | `StorageQuotaCacheService.java` L61：`app.cache.storage-usage-ttl-minutes` → `app.cache.storage-usage-ttl-seconds`（与 V2 SQL L35 一致），并将 `Duration.ofMinutes(...)` 改为 `Duration.ofSeconds(...)` |
| **13-BUG-3** | `V2__hot_config_keys.sql` 增加 `app.email.verify-code-cooldown-seconds`（值 60，类型 NUMBER）的 INSERT 行；或新建 V3 迁移文件补充 |

#### 6.3.3 P3：清理死键（解决 13-B、13-C）

V2 SQL 中 `app.rest-client.*`（2 键）和 `app.resilience.*`（5 键）无人消费。可选择：
- 删除这些死键（如果确定永不接入）
- 或保留并标注 `-- TODO: 待 RestClientConfig/AbstractServiceClient 接入` 注释

---

### 6.4 CODEX 审核未完成项 —— 解决方案

#### 6.4.1 P2：迁移 3 个 ErrorCode 枚举的调用点（解决 CODEX-A）

`TeamErrorCode`/`ShareErrorCode`/`ProjectErrorCode` 枚举类已存在但零引用。需逐域迁移调用点：
- **TeamErrorCode**：grep `ErrorCode.TEAM_NOT_FOUND`/`ErrorCode.TEAM_*` 的调用点 → 替换为 `TeamErrorCode.TEAM_NOT_FOUND.getCode()` 等
- **ShareErrorCode**：grep `ErrorCode.SHARE_*` → 替换为 `ShareErrorCode.*`
- **ProjectErrorCode**：grep `ErrorCode.PROJECT_*` → 替换为 `ProjectErrorCode.*`
- 每个域迁移后移除 `ErrorCode.java` 中对应的 deprecated int 常量
- 验证：`mvn test` 全通过

#### 6.4.2 P1：前端文件列表分页接线（解决 CODEX-B）

后端已就绪，仅需前端改造：
1. `composables/useSpaceFileList.js`：增加 `currentPage`/`pageSize` 响应式状态，`fetchFileList` 时传入 `page`/`pageSize` 参数
2. `FileExplorer.vue`：底部增加 `<el-pagination>` 组件，绑定 `currentPage`/`pageSize`，`@current-change` 触发重新加载
3. `api/files.js` `fetchFileList` 已支持 `page`/`pageSize` 参数（L30-35），无需改 API 层
4. 验证：超过 50 个子项的目录可翻页加载

---

## 七、推荐执行顺序

| 优先级 | 编号 | 任务 | 预估工作量 | 依赖 |
|---|---|---|---|---|
| **P0** | 13-BUG-1/2/3 | 修复 3 个配置键名 Bug | 0.5 天 | 无 |
| **P0** | 11-D | 修复 LocalDiskStorageProvider directUpload 标志 | 0.5 天 | 无 |
| **P0** | 11-C | 修复 useShareFileDownload.js | 0.5 天 | 11-D |
| **P0** | 11-A | 修复 useFileDownload.js 空操作分支 | 0.5 天 | 11-D |
| **P0** | 11-E | 修复 Gateway 路由顺序 | 0.5 天 | 无 |
| **P1** | 11-B | 修复 backendArchive.js | 0.5 天 | 11-D |
| **P1** | CODEX-B | 前端文件列表分页接线 | 1 天 | 无 |
| **P1** | 13-A | 迁移 GetSignUrl BLOCKED_EXTENSIONS | 1 天 | 无 |
| **P2** | CODEX-A | 迁移 3 个 ErrorCode 枚举调用点 | 2 天 | 无 |
| **P2** | 12-A | 自托管 Runner | 0.5 天 + 服务器 | 需服务器 |
| **P2** | 12-B | 阿里云 ACR | 0.5 天 + 账号 | 需账号 |
| **P3** | 12-C | 本地开发环境 | 1 天 | 无 |
| **P3** | 13-B/C | 清理死键或保留注释 | 0.5 天 | 无 |
| **P3** | 11-F | 前端 VO 类型定义 | 0.5 天 | 无 |

**总预估**：P0 ~2.5 天 + P1 ~2.5 天 + P2 ~3 天 + P3 ~2 天 ≈ **10 工作日**（不含外部资源等待）。

---

## 八、附：核对过程元数据

- 后端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseBack\`（11 个 Maven 模块）
- 前端核对路径：`D:\code\databaseZXYZ\zxyz\ZXYZdatabaseFront\src\`
- 根仓库核对路径：`D:\code\databaseZXYZ\zxyz\`（docker-compose.yml、deploy/、scripts/、sql/、nacos-config/、.github/workflows/）
- 核对工具：Glob / Grep / Read / Agent（4 个并行 explore 子代理）
- 核对范围：全部关键文件，逐行验证
- 核对批次：2026-07-24 第三轮（用户修改后重新核对）
- 关键发现：
  - #11 Phase 1 全部完成（4 个服务类已注入 Registry），Phase 2 后端完成，前端有 3 处分支未真正实现 + 1 处 directUpload 语义反转
  - #13 前置 + 迁移 + ConfigGetter + 13/16 消费方完成，但 3 个键名 Bug 导致热配置静默失效
  - CODEX 7/9 完成，ErrorCode 枚举化仅 User 域迁移，前端分页后端就绪但前端未接线
