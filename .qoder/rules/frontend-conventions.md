# 前端编码约定

## 基本规范

WHEN 编写前端代码, DO 在 `ZXYZdatabaseFront/` 目录执行 npm 命令。
WHEN 添加 API 接口, DO 按领域放入对应 `api/` 文件，禁止跨领域引用。
WHEN 选择 HTTP 客户端, DO 按场景选：`request.js`（已认证，默认）、`publicRequest.js`（公开/分享）、`imRequest.js`（IM）。所有客户端均 `withCredentials: true`。
WHEN 处理认证, DO 依赖 HttpOnly Cookie（`withCredentials: true`），不手动注入 Authorization Header。
WHEN 显示时间戳, DO 使用 `fmtTime()` 函数（`utils/format.js`），不要直接 `|| '-'` 显示原始值。
WHEN 设置时间戳默认值, DO 使用 `?? null`（而非 `|| null`），`||` 会将空字符串 `""` 和 `0` 误转为 `null`。
WHEN 处理文件操作, DO 使用 `composables/` 中的组合函数，不直接操作 store。
WHEN 提交代码, DO 使用 conventional commits 格式（Husky + commitlint 强制）。
WHEN 处理错误, DO 使用 `BusinessException` → `ErrorCode` → `Result` 模式。
WHEN 添加 API 接口, DO 参考 `src/api/README.md` 中的模块规范。
WHEN 添加 admin 页面, DO 放在 `src/views/setting/` 下，路由在 `src/router/index.js` 中配置。
WHEN 添加 setting 子路由, DO 确保 `route.name` 在 Setting 组件 watcher 的 `{ immediate: true }` 执行前已就绪，否则会被重定向到第一个可见 tab。

## 前端测试

26 个测试文件，278 个用例（`npm run test`）。覆盖 composables、utils、api、store、router guards。

- 测试文件命名 `*.spec.js`，放在对应目录的 `__tests__/` 下
- 新增测试使用 `vi.mock()` mock 外部依赖
- 测试命名用中文
- 测试约定详见 `docs/testing.md`

## 前端测试 import 顺序

vitest/vue 导入在最前，空行后是 `vi.mock()` 调用（紧挨，无空行），再空行后是 `@/` 和第三方包导入。`element-plus` 的 `import` 必须放在 `vi.mock()` 之后（与 `@/` 导入同组），否则 `import-x/order` 报错。
