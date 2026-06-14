# 前端架构详解

本文档为 CLAUDE.md 的补充，提供前端架构的详细说明。

## 技术栈

Vue 3.5 (Composition API + `<script setup>`)、Vite 7.3、Element Plus 2.11（auto-import）、Pinia 3.0、Axios 1.13、Vitest 4.1。

## HTTP 客户端

三个 Axios 实例，按场景分离：
- `request.js` — 需登录的常规 API（15s 超时，30s 上传）
- `imRequest.js` — IM 服务专用
- `publicRequest.js` — 公开/未认证接口

所有客户端通过 `createApiClient` 工厂构建，支持 blob 响应、token 过期自动跳转。

## API 模块组织

API 模块严格按领域分离（`src/api/`），禁止跨领域引用。每个模块使用单一 HTTP 客户端实例。详见 `src/api/README.md`。

## Composables

~40 个组合函数（`src/composables/`）驱动文件浏览器行为，覆盖：文件操作（上传、下载、导航、重命名、移动/复制、删除）、UI 状态（拖拽选择、排序、右键菜单、快捷键）、领域工作流（回收站、分享、项目、团队、认证）。

`views/chat/` 目录有独立的 composables 子目录（~10 个聊天专用组合函数：虚拟滚动、消息模型、置顶会话等）。

## Pinia Stores

`store/im/` 包含 5 个领域文件（conversationDomain, messageDomain, notificationDomain, permissionDomain, realtimeDomain, teamDomain）+ chatBridge 插件。

顶层 stores：session、currentUser、currentId、team、chat。

## Session 引导

`useSessionStore.ensureSessionReady()` 是 router guard 中的单一入口，加载用户 profile 和 teams，去重并发调用，处理账号切换版本。`beforeEach` guard 将未认证用户重定向到 `/login`，无团队用户重定向到 `/no-team`。

## IM WebSocket

专用 `src/utils/imWebSocket.js` 处理实时通信，chat store 通过 `store/plugins/chatBridge.js` 将 WebSocket 事件桥接到 Pinia 状态。

## 路由

所有路由组件（Layout 和 Index 除外）使用动态 `import()` 实现代码分割。`router/guards/permission.js` 导出 `requireSystemAdminRole()` 和 `requirePermissionCenter` 作为 `beforeEnter` guards。
