---
kind: frontend_style
name: 前端样式体系：Element Plus + 原生 CSS 组件化方案
category: frontend_style
scope:
    - '**'
source_files:
    - ZXYZdatabaseFront/src/main.js
    - ZXYZdatabaseFront/src/App.vue
    - ZXYZdatabaseFront/vite.config.js
    - ZXYZdatabaseFront/public/iconFont/iconfont.css
    - ZXYZdatabaseFront/src/views/layout/index.vue
    - ZXYZdatabaseFront/src/views/setting/style.css
---

ZXYZ 前端（ZXYZdatabaseFront）采用 Vue 3 + Vite + Element Plus 的样式体系，整体以 Element Plus 设计系统为基础，辅以原生 CSS 进行页面级定制，未引入 Tailwind、SCSS/Less 等预处理器或原子化框架。

**样式系统与主题**
- 通过 `el-config-provider` 包裹全局应用并设置中文 locale（`zhCn`），所有 Element Plus 组件默认使用中文文案与主题。
- 在 `src/main.js` 中按需引入 Element Plus 的 message 组件样式（`element-plus/es/components/message/style/css`），其余样式由 unplugin-vue-components 自动按需加载。
- 未自定义 Element Plus 主题变量或覆盖全局色板，颜色值直接硬编码在 CSS 中（如 `#f5f7fb`、`#1f2937`、`#409eff`、`#6b7280` 等），遵循 Element Plus 默认语义色约定。

**CSS 组织方式**
- 全局重置样式集中在 `App.vue` 的 `<style>` 块中，包含基础 reset、`html/body/#app` 尺寸与 `overflow: hidden` 布局约束，以及 `.icon` 通用图标类。
- 页面级样式采用 Vue SFC 的 `<style scoped>` 模式，按视图/组件文件内聚管理，如 `views/layout/index.vue` 中的布局样式、`views/setting/style.css` 中的设置页样式。
- 无独立的全局 CSS 入口（`src/assets/main.css` 被注释），样式分散在各组件内部，属于典型的“组件内样式”策略。

**图标与字体资源**
- 业务图标通过自托管的 iconfont 字体库提供，位于 `public/iconFont/`，包含 `iconfont.css` 及 woff2/woff/ttf 字体文件，定义 `.iconfont` 基类与各类文件类型图标（`.icon-excel`、`.icon-pdf`、`.icon-wenjianjiao` 等）。
- 同时使用 `@element-plus/icons-vue` 作为组件图标来源，两者互补：Element Icons 用于菜单/按钮等 UI 元素，iconfont 用于文件类型等语义化图标。

**响应式策略**
- 通过 CSS `@media (max-width: 900px)` 断点实现移动端适配，主要调整 Grid 布局为单列、工具栏纵向排列等，未见媒体查询变量或响应式工具类。

**构建与打包**
- Vite 配置中将 `element-plus` 单独拆分为 `manualChunks`，与 `vue/vue-router/pinia/axios` 合并为 vendor chunk，优化首屏加载。
- 目标浏览器环境为 `es2020`，sourcemap 生产环境关闭。

**约束与规范**
- 未使用 SCSS/Less/Tailwind，所有样式均为原生 CSS。
- 样式作用域遵循 Vue SFC 的 scoped 原则，避免全局污染。
- 颜色、间距等视觉值直接写在 CSS 中，未抽象为 CSS 变量或设计令牌。