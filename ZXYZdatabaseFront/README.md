# 指绣云章 (ZXYZ) 前端

指绣云章是一个云端文件管理平台，支持团队协作与即时通讯。本目录为平台的前端项目，基于 Vue 3 单页应用 (SPA) 架构。

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 框架 | Vue 3 (Composition API + `<script setup>`) | ^3.5.32 |
| 构建工具 | Vite | ^7.3.2 |
| 路由 | Vue Router 4 (`createWebHistory`) | ^4.6.3 |
| 状态管理 | Pinia 3 | ^3.0.4 |
| UI 组件库 | Element Plus | ^2.11.7 |
| HTTP 客户端 | Axios | ^1.13.2 |
| 图标 | @element-plus/icons-vue | ^2.3.2 |
| 归档/打包 | @zip.js/zip.js | ^2.8.26 |
| XSS 防护 | DOMPurify | ^3.4.5 |

开发依赖：

- `@vitejs/plugin-vue` — Vite Vue 插件
- `unplugin-auto-import` — Element Plus 按需自动导入
- `unplugin-vue-components` — 组件自动注册

## 本地开发环境搭建

### 前置条件

- Node.js 18+
- npm 9+
- 后端服务已启动（API Gateway 默认端口 18000，IM WebSocket 默认端口 19090）

### 安装与运行

```bash
# 安装依赖
npm install

# 启动开发服务器（端口 5173）
npm run dev

# 生产构建
npm run build

# 预览生产构建（端口 4173）
npm run preview
```

开发服务器启动后访问 `http://localhost:5173`。生产构建输出到 `dist/` 目录。

## 环境变量配置

环境变量以 `VITE_` 为前缀，通过 `.env.*` 文件加载。

### 开发环境 (`.env.development`)

| 变量 | 值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:18000` | API Gateway 地址 |
| `VITE_IM_API_BASE_URL` | `http://localhost:18000` | IM 服务 HTTP API 地址（经 Gateway 转发） |
| `VITE_IM_WS_URL` | `ws://localhost:19090/ws` | IM WebSocket 地址 |
| `VITE_DEV_ALLOWED_HOSTS` | `natfrp.acloud.uno,.acloud.uno` | Vite 开发服务器允许的外部主机名 |

### 生产环境 (`.env.production`)

| 变量 | 值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `/` | 相对路径，由 Nginx 反代至 Gateway |
| `VITE_IM_API_BASE_URL` | `/im-api` | 相对路径，由 Nginx 反代 |
| `VITE_IM_WS_URL` | `/ws` | 相对路径，由 Nginx 反代至 WebSocket 端口 |

## 项目结构

```
ZXYZdatabaseFront/
├── index.html                  # 入口 HTML
├── package.json
├── vite.config.js              # Vite 构建配置
├── .env.development            # 开发环境变量
├── .env.production             # 生产环境变量
├── public/                     # 静态资源（图标字体等）
│   └── iconFont/
├── src/
│   ├── main.js                 # 应用入口
│   ├── App.vue                 # 根组件（仅含 <router-view />）
│   ├── api/                    # API 模块（按领域分离）
│   ├── assets/                 # 静态资源
│   ├── components/             # 公共组件
│   ├── composables/            # 组合函数（业务逻辑复用）
│   │   ├── project/            # 项目相关组合函数
│   │   └── team/               # 团队相关组合函数
│   ├── constants/              # 常量定义
│   ├── models/                 # 数据模型与映射函数
│   ├── router/                 # 路由配置与守卫
│   │   └── guards/             # 路由守卫（权限校验）
│   ├── store/                  # Pinia 状态管理
│   │   └── im/                 # IM 相关领域状态
│   ├── utils/                  # 工具函数
│   └── views/                  # 页面视图
│       ├── chat/               # 即时通讯
│       ├── collaboration/      # 协作
│       ├── index/              # 文件资源管理器
│       ├── join/               # 加入团队
│       ├── layout/             # 主布局框架
│       ├── login/              # 登录
│       ├── my-share/           # 我的分享
│       ├── my-star/            # 我的收藏
│       ├── no-team/            # 无团队引导页
│       ├── permission/         # 权限中心
│       ├── projects/           # 项目管理
│       ├── recycle-bin/        # 回收站
│       ├── register/           # 注册
│       ├── setting/            # 设置（账号、团队管理、系统管理）
│       ├── share/              # 公开分享页
│       └── temp.vue            # 临时页面
└── dist/                       # 生产构建输出
```

## 路由与页面说明

路由使用 `createWebHistory` 模式，配置位于 `src/router/index.js`。

### 路由表

| 路径 | 路由名 | 组件 | 说明 |
|---|---|---|---|
| `/login` | `login` | `Login` | 登录页（公开） |
| `/register` | `register` | `Register` | 注册页（公开） |
| `/s/:shareKey` | `sharePublic` | `SharePublic` | 公开分享页（公开） |
| `/no-team` | `noTeam` | `NoTeam` | 无团队引导页 |
| `/` | `layout` → `/index` | `Layout` | 主布局，重定向到 `/index` |
| `/index` | `index` | `Index` | 个人文件空间 |
| `/team-space` | `teamSpace` | `Index` | 团队文件空间（复用 Index 组件，`space=team`） |
| `/projects/:projectId/space` | `projectSpace` | `Index` | 项目文件空间（复用 Index 组件，`space=project`） |
| `/my-star` | `myStar` | `MyStar` | 我的收藏 |
| `/my-share` | `myShare` | `MyShare` | 我的分享 |
| `/projects` | `projects` | `Projects` | 项目列表 |
| `/chat` | `chatHome` | `ChatHome` | 即时通讯主页 |
| `/recycle-bin` | `recycleBin` | `RecycleBin` | 回收站 |
| `/join/team/:token` | `joinTeam` | `JoinTeam` | 通过邀请链接加入团队 |
| `/setting/account` | `accountSettings` | `AccountSettings` | 账号设置 |
| `/setting/team-admin` | `teamAdminSettings` | `TeamAdminSettings` | 团队管理（需系统管理员） |
| `/setting/system-admin` | `systemAdminSettings` | `SystemAdminSettings` | 系统管理（需系统管理员） |
| `/setting/permissions` | `permissionCenter` | `PermissionCenter` | 权限中心 |

### 路由守卫

全局前置守卫 (`router.beforeEach`) 执行以下逻辑：

1. **公开路由**（`login`、`register`、`temp`、`sharePublic`）直接放行
2. **登录检查**：若 `currentUserStore.profile` 为空，重定向到登录页并携带 `redirect` 参数
3. **会话初始化**：调用 `sessionStore.ensureSessionReady()` 加载用户资料和团队列表
4. **团队检查**：若用户无团队且非管理员，重定向到 `/no-team`
5. **认证失败处理**：401/403 响应清除登录状态并跳转登录页

子路由守卫：
- `requireSystemAdminRole()` — 团队管理、系统管理页面需要系统管理员角色
- `requirePermissionCenter` — 权限中心需要系统权限或团队权限

## API 模块说明

API 模块位于 `src/api/`，严格按业务领域分离。每个文件只使用一个 HTTP 客户端，同一业务语义只在一个模块中导出。

| 文件 | HTTP 客户端 | 职责 |
|---|---|---|
| `auth.js` | `request` | 登录、注册、获取当前用户、登出 |
| `user.js` | `request` | 用户设置、头像上传、密码修改、绑定手机/邮箱、设置默认团队、用户搜索 |
| `account.js` | `request` | 验证码发送、联系方式验证、关联账号管理、账号切换 |
| `team.js` | `request` | 团队 CRUD、成员管理、成员存储配额、管理员团队操作 |
| `teamIm.js` | `imRequest` | IM 侧团队协作：公告发布、禁言、邀请链接、加入申请、团队权限与角色管理 |
| `project.js` | `request` | 项目目录、项目创建申请、项目成员、项目配额、项目归档 |
| `permission.js` | `request` | 系统权限与角色管理、权限审计 |
| `files.js` | `request` | 文件列表、搜索、上传签名、确认上传、下载、重命名、移动、复制、删除、回收站操作、存储用量查询 |
| `share.js` | `request` + `publicRequest` | 创建分享、我的分享列表、取消分享；公开分享信息验证、文件列表、下载 |
| `im.js` | `imRequest` | IM 会话管理、消息查询与搜索、已读同步、在线状态、系统通知 |
| `databaseAdmin.js` | `request` | 数据库维护状态、导出、导入（管理员） |
| `emailAdmin.js` | `request` | 邮件服务器配置管理、邮件记录查询（管理员） |

## HTTP 客户端

项目使用三个 Axios 实例，通过 `src/utils/createApiClient.js` 工厂函数创建，内置统一的鉴权注入、业务码解析和错误处理逻辑。

### `request`（`src/utils/request.js`）

- **用途**：主服务 API 调用
- **baseURL**：`VITE_API_BASE_URL`
- **超时**：15 秒（上传相关接口 30 秒）
- **Token 过期策略**：`redirect`（清除 token 并跳转登录页）
- **支持 Blob 响应**：是（用于文件下载等场景）

### `imRequest`（`src/utils/imRequest.js`）

- **用途**：IM 服务 HTTP API 调用
- **baseURL**：`VITE_IM_API_BASE_URL`
- **超时**：5 秒
- **Token 过期策略**：`silent`（仅标记错误已处理，不跳转）
- **错误消息前缀**：`IM `

### `publicRequest`（`src/utils/publicRequest.js`）

- **用途**：公开分享页 API 调用（无需登录）
- **baseURL**：`VITE_API_BASE_URL`
- **超时**：5 秒
- **鉴权**：不注入认证 Header
- **Cookie**：不携带凭证

### 统一响应处理

所有客户端共享以下响应拦截逻辑：

- 后端返回 `code === 1` 表示成功，直接返回 payload
- 认证失败（`code === 4010` 或特定错误关键词）清除本地用户状态，按策略决定是否跳转登录
- 业务错误包装为 `BusinessError` 对象，携带 `code`、`msg`、`data`
- 网络错误（超时、断网）给出友好提示
- 503 服务不可用做特殊处理

## 认证机制

项目采用 **HttpOnly Cookie** 认证方式。前端不再在 localStorage 中存储 token，认证状态由后端通过 HttpOnly Cookie 管理。

- `src/utils/auth.js` 中的 `getAuthHeader()` 和 `getToken()` 均返回空值，`setLoginUser()` 为空操作
- `clearToken()` 清除 localStorage 中的遗留 key `loginUser`
- 登录成功后通过 `fetchCurrentUser()` 获取完整用户资料
- 用户显示层数据（不含 roles/permissions 等敏感字段）缓存在 localStorage 的 `displayUser` key 中

## 状态管理 (Pinia)

Pinia store 位于 `src/store/`，使用 Composition API 风格定义。

### `currentUser`（`src/store/currentUser.js`）

当前登录用户状态。缓存显示层数据到 localStorage，完整 profile（含 roles/permissions）每次会话初始化时从 API 获取。

- `profile` — 用户资料（id、username、name、avatar、email 等）
- `roles` / `permissions` — 系统角色与权限列表
- `isAdmin` — 是否系统管理员
- `canWrite` / `canDelete` / `canReadTrash` — 文件操作权限
- `login()` / `loadProfile()` / `clearAll()` — 登录、加载资料、清除状态

### `session`（`src/store/session.js`）

会话管理 store，协调 currentUser 和 team store 的初始化流程。

- `ensureSessionReady()` — 确保会话已初始化（加载 profile + 团队列表），带去重和版本控制
- `resetSessionBootstrap()` — 重置会话状态（用于账号切换等场景）
- 返回会话快照：`profile`、`teams`、`hasTeams`、`shouldEnterNoTeam`

### `team`（`src/store/team.js`）

团队状态管理，内部拆分为 `teamDomain` 和 `permissionDomain` 两个领域。

- `teams` — 当前用户的团队列表
- `selectedTeamId` — 当前选中的团队 ID（团队上下文的唯一真源）
- `teamMembers` / `teamRoles` / `teamPermissions` — 团队成员、角色、权限
- `loadTeams()` / `setSelectedTeam()` / `createNewTeam()` — 团队操作
- `loadTeamPermissionCenter()` / `saveTeamRole()` — 权限中心操作

### `chat`（`src/store/chat.js`）

IM 聊天状态管理，内部拆分为三个领域：

- **conversationDomain** — 会话管理：加载会话列表、打开会话、消息加载与搜索、已读同步
- **notificationDomain** — 系统通知：加载通知列表、未读计数、标记已读
- **realtimeDomain** — 实时通信：WebSocket 连接管理、消息发送（文本/文件卡片）、在线状态

关键状态：
- `conversations` — 会话列表
- `activeConversationId` / `activeConversation` — 当前活跃会话
- `messagesByConversation` — 按会话 ID 存储的消息映射
- `wsStatus` — WebSocket 连接状态（`DISCONNECTED` / `CONNECTING` / `CONNECTED` / `RECONNECTING`）
- `notifications` / `unreadCount` — 系统通知与未读数

### `currentId`（`src/store/currentId.js`）

文件资源管理器的当前目录 ID 与路径-ID 映射缓存。映射数据持久化到 sessionStorage，最多缓存 500 条，支持按路径层级回退查找最近的父路径。

### IM Store 子领域（`src/store/im/`）

| 文件 | 职责 |
|---|---|
| `conversationDomain.js` | 会话管理领域逻辑 |
| `notificationDomain.js` | 系统通知领域逻辑 |
| `realtimeDomain.js` | WebSocket 实时通信领域逻辑 |
| `teamDomain.js` | 团队操作领域逻辑 |
| `permissionDomain.js` | 权限管理领域逻辑 |
| `normalizers.js` | 数据规范化工具 |

## WebSocket (IM)

IM 实时通信通过 `src/utils/imWebSocket.js` 管理的 WebSocket 客户端实现。

### 连接参数

- **地址**：`VITE_IM_WS_URL`，通过 `resolveWebSocketUrl()` 自动处理相对路径（根据当前页面协议选择 `ws://` 或 `wss://`）
- **认证**：连接 URL 携带 `ws_token` 查询参数，从 cookie 中读取
- **心跳**：每 25 秒发送 `PING` 帧
- **重连**：指数退避策略，基础延迟 1 秒，最大 30 秒，最多 20 次重连尝试

### 消息格式

所有消息使用 JSON 信封格式：

```json
{
  "type": "消息类型",
  "requestId": "客户端生成的请求 ID",
  "clientMessageId": "客户端消息 ID",
  "conversationId": "会话 ID",
  "payload": {},
  "timestamp": 1234567890
}
```

### 连接状态

| 状态 | 说明 |
|---|---|
| `DISCONNECTED` | 未连接 |
| `CONNECTING` | 连接中 |
| `CONNECTED` | 已连接 |
| `RECONNECTING` | 重连中 |

## 组合函数 (Composables)

组合函数位于 `src/composables/`，封装可复用的业务逻辑。

### 文件资源管理器

| 函数 | 职责 |
|---|---|
| `useFileNavigation` | 文件夹导航（进入、返回、面包屑） |
| `useSelectionManager` | 文件选择管理（单选、多选、全选） |
| `useDragSelection` | 框选（拖拽矩形选择文件） |
| `useFileUpload` | 文件上传流程 |
| `useFolderUpload` | 文件夹上传流程 |
| `useFileSearch` | 文件搜索 |
| `useSortState` | 排序状态管理 |
| `useSpaceFileList` | 空间文件列表加载 |
| `useFileContextMenu` | 文件右键菜单 |
| `useFileExplorerHotkeys` | 文件资源管理器快捷键 |
| `useCorePathNavigation` | 核心路径导航逻辑 |
| `useFileDownload` | 文件下载 |
| `useArchiveDownload` | 打包下载 |
| `useFolderPickerNavigation` | 文件夹选择器导航 |
| `useCurrentSpaceContext` | 当前空间上下文（个人/团队/项目） |
| `useStorageUsage` | 存储用量查询 |

### 文件操作

| 函数 | 职责 |
|---|---|
| `useRenameAction` | 文件重命名 |
| `useDeleteDialog` | 删除确认对话框 |
| `useMoveCopyAction` | 移动/复制文件 |
| `useCreateFolderAction` | 创建文件夹 |
| `useShareCreateAction` | 创建分享 |
| `useRecycleBinActions` | 回收站操作（还原、永久删除） |
| `useRecycleBinList` | 回收站列表 |
| `useFileSpaceActions` | 文件空间操作 |

### 分享相关

| 函数 | 职责 |
|---|---|
| `useMyShareList` | 我的分享列表 |
| `useShareVisit` | 公开分享页访问 |
| `useShareFileList` | 分享文件列表 |
| `useShareFileNavigation` | 分享文件导航 |
| `useShareFileDownload` | 分享文件下载 |
| `useShareArchiveDownload` | 分享打包下载 |

### IM 相关

| 函数 | 职责 |
|---|---|
| `useImWorkspace` | IM 工作区初始化与管理 |
| `useSendToConversation` | 向会话发送消息 |

### 其他

| 函数 | 职责 |
|---|---|
| `useLoginForm` | 登录表单逻辑 |
| `usePostLoginGuide` | 登录后引导流程 |
| `useBatchFeedback` | 批量操作反馈 |
| `useStorageUsage` | 存储用量查询 |

### 子目录组合函数

- `composables/project/` — `useProjectManagement`、`useCreateProjectAction`
- `composables/team/` — `useTeamManagement`、`useTeamStorageAllocation`

## 数据模型 (Models)

数据模型位于 `src/models/`，负责 API 响应数据到前端视图对象的映射转换。

| 文件 | 职责 |
|---|---|
| `file.js` | 文件条目映射（空间文件、搜索结果、回收站文件） |
| `fileActions.js` | 文件操作相关模型 |
| `filePresentation.js` | 文件展示层数据格式化 |
| `imPresentation.js` | IM 消息展示层数据格式化 |
| `permission.js` | 权限数据映射 |
| `share.js` | 分享数据映射 |
| `space.js` | 空间类型映射 |
| `upload.js` | 上传相关数据映射 |

## 公共组件

组件位于 `src/components/`，为跨页面复用的 UI 组件。

| 组件 | 职责 |
|---|---|
| `FileExplorer.vue` | 文件资源管理器主体 |
| `RecycleBinExplorer.vue` | 回收站文件浏览 |
| `ShareFileExplorer.vue` | 分享文件浏览 |
| `FileUploader.vue` | 文件上传组件 |
| `FolderUploader.vue` | 文件夹上传组件 |
| `Uploader.vue` | 通用上传组件 |
| `FileContextMenu.vue` | 文件右键菜单 |
| `CreateFolder.vue` | 创建文件夹对话框 |
| `RenameFileDialog.vue` | 重命名对话框 |
| `DeleteConfirmDialog.vue` | 删除确认对话框 |
| `MoveCopyDialog.vue` | 移动/复制对话框 |
| `CreateShareDialog.vue` | 创建分享对话框 |
| `ShareSuccessDialog.vue` | 分享成功提示 |
| `InputDialog.vue` | 通用输入对话框 |
| `ArchiveNameDialog.vue` | 压缩包命名对话框 |
| `ConversationPickerDialog.vue` | 会话选择器 |
| `FileCardPickerDialog.vue` | 文件卡片选择器 |
| `CreateProjectDialog.vue` | 创建项目对话框 |
| `ProjectSettingsDialog.vue` | 项目设置对话框 |
| `TeamSelectDialog.vue` | 团队选择对话框 |
| `TeamSwitcher.vue` | 团队切换器 |
| `TeamSettingDrawer.vue` | 团队设置抽屉 |
| `RoleManagementPanel.vue` | 角色管理面板 |
| `LogoutDialog.vue` | 登出确认对话框 |

## Vite 构建配置

配置位于 `vite.config.js`：

- **Vue 插件**：`@vitejs/plugin-vue`
- **自动导入**：`unplugin-auto-import` + `unplugin-vue-components`，配合 `ElementPlusResolver` 实现 Element Plus 按需导入
- **路径别名**：`@` 映射到 `src/` 目录
- **开发服务器**：支持通过 `VITE_DEV_ALLOWED_HOSTS` 环境变量配置允许的外部主机名（用于内网穿透等场景）

## 关键领域模式

### 文件空间类型

| 类型 | 值 | 说明 |
|---|---|---|
| `PERSONAL` | 1 | 个人文件空间 |
| `TEAM` | 2 | 团队文件空间 |
| `PROJECT` | 3 | 项目文件空间 |

### 文件删除状态

| 状态 | 值 | 说明 |
|---|---|---|
| `NORMAL` | 0 | 正常 |
| `RECYCLE` | 1 | 回收站 |
| `DELETED` | 2 | 已永久删除 |

### 服务间通信

前端通过 API Gateway（端口 18000）统一访问后端服务。IM 服务的 HTTP API 和 WebSocket 通过 Gateway 的路径剥离（StripPrefix）转发。

### 错误处理

- `BusinessError` 自定义错误类型，携带 `code`、`msg`、`data`
- `handleBusinessError()` 统一展示错误消息（Element Plus `ElMessage.error`）
- 认证失败自动清除本地状态并跳转登录页

## Docker 部署

项目包含 `Dockerfile`，用于构建生产镜像。构建输出为 `dist/` 静态文件，由 Nginx 提供服务并反代 API 请求到后端 Gateway。
