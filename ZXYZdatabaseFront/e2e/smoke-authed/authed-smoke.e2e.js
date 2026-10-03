// 登录态只读冒烟（C-4，「只读固定账号 + 白名单接口清单」的那一半）。
//
// 📌 文件名刻意用 `.e2e.js`（避开 vitest 的 `**/*.spec.js` 默认 include），
//    理由见 playwright.config.js —— 改回 `.spec.js` 会被 vitest 收走并在
//    coverage 跑里以「Playwright Test did not expect test() to be called here」成片失败。
//
// ⚠️ 风险与纪律（动手前必读）：
//  1. **登录限流**：`POST /api/users/login` 有 IP + 用户名双维度限流
//     （`UserController.loginRateLimiter.checkAndIncrement(ip, username)`）。
//     **登录次数 = 每个套件运行 1 次**（`test.beforeAll` 建一个共享 APIRequestContext
//     并登录一次，`storageState` 序列化 Cookie 后供全部「已登录」用例复用；
//     对照组的匿名 context 由 `playwrightRequest.newContext` 创建，不参与登录）。
//     即便如此本套件仍**按需手动 / 低频跑**，**不要**放进每次 push 的默认 CI
//     （Lead 已裁定不接线）。建议使用专用测试账号（普通用户权限即可，不要管理员）。
//  2. **凭据来源**：一律走环境变量 `E2E_USERNAME` / `E2E_PASSWORD`，
//     **绝不硬编码、绝不入仓**。凭据缺失 → 全部用例响亮跳过（skip 理由会打印）；
//     但若显式设置 `E2E_REQUIRE_AUTHED=1` 而凭据缺失 → **直接 fail**（fail-closed），
//     防止「想跑却没跑」被吞成永远绿的假门禁。
//  3. **只读承诺**：白名单仅收「纯读 + 无需路径参数」的 GET 端点（逐个读过服务端源码确认，
//     见下方白名单表）。断言只到**形状**层（HTTP 200 / code===1 / data 类型），
//     **绝不**断言条数、具体值；**绝不**把响应体打进日志或报告（可能含 PII）。
//     测试**不创建、不修改、不删除任何资源**。
//  4. **登录实现**：`playwrightRequest.newContext()` 建共享 context，POST
//     `/api/users/login` 后服务端下发的 HttpOnly Cookie 留在该 context 的 Cookie jar
//     内，后续「已登录」请求都走这同一个实例 —— **不手工拼 Authorization Header**
//     （CLAUDE.md：认证依赖 HttpOnly Cookie + withCredentials）。
//     🔴 不能用内置 `request` fixture 做「登录+断言」：它是**每条用例一个全新
//     context**（独立 Cookie jar），模块级布尔无法跨用例传递登录态 —— 曾因此
//     在并行 workers 下随机 401（Lead 桩服务器实测 9~11 failed）。
//
// 跑法：
//   E2E_USERNAME=... E2E_PASSWORD=... npx playwright test --project=smoke-authed
//   E2E_BASE_URL=http://127.0.0.1:8081 E2E_USERNAME=... E2E_PASSWORD=... \
//     npx playwright test --project=smoke-authed
//   npx playwright test --project=smoke-authed --list     # 只列用例（凭据缺失也能列）
import { test, expect, request as playwrightRequest } from '@playwright/test'

// ---------------------------------------------------------------- 守卫与登录

const USERNAME = process.env.E2E_USERNAME || ''
const PASSWORD = process.env.E2E_PASSWORD || ''
const REQUIRE_AUTHED = process.env.E2E_REQUIRE_AUTHED === '1'
const HAS_CREDS = Boolean(USERNAME && PASSWORD)

// ⚠️ 以下两个守卫必须放在**模块加载期**（收集期），不能放进 test.beforeAll：
// `test.skip(条件)` 会在收集期给整个文件打上静态 skip 注解，Playwright 对
// expectedStatus=skipped 的用例**不执行任何 beforeAll hook**（workerProcessEntry:
// isSkipped → 直接标 skipped 返回）—— 放进 beforeAll 的 fail-closed 是死代码。

// fail-closed：显式要求跑登录态冒烟时，凭据缺失必须在收集期直接 throw，
// 让整个文件 fail（Playwright 报收集错误、退出码非 0），绝不以「全部跳过」冒充门禁。
if (REQUIRE_AUTHED && !HAS_CREDS) {
  throw new Error(
    '[smoke-authed] E2E_REQUIRE_AUTHED=1 但 E2E_USERNAME/E2E_PASSWORD 未配置 —— ' +
      'fail-closed：拒绝以「全部跳过」冒充门禁通过。请配置凭据，或去掉 E2E_REQUIRE_AUTHED。',
  )
}

// 响亮跳过：list reporter 不显示 test.skip 的 description，收集期 console.warn
// 让「跳过原因」必然出现在 stdout，而不是看起来像什么都没发生。
if (!HAS_CREDS) {
  console.warn(
    '[smoke-authed] 未提供 E2E_USERNAME/E2E_PASSWORD，' +
      '跳过登录态只读冒烟（全部用例将 skip）。如需强制执行请设置 E2E_REQUIRE_AUTHED=1（凭据缺失时会 fail）。',
  )
}

test.skip(!HAS_CREDS, '未提供 E2E_USERNAME/E2E_PASSWORD，跳过登录态只读冒烟')

/**
 * 登录一次：共享 context 的 Cookie jar 里拿到服务端下发的 HttpOnly Cookie。
 *
 * 服务端 `setAuthCookies(response, ...)` 下发 HttpOnly Cookie，同一 context 的
 * 后续请求自动携带 —— 全程不接触 token 明文、不手工拼 Authorization Header。
 * 响应体不打印（LoginVO 含 token）。
 */
async function login(apiContext) {
  const response = await apiContext.post('/api/users/login', {
    data: { username: USERNAME, password: PASSWORD, rememberMe: false },
  })
  expect(response.status()).toBe(200)
  const body = await response.json()
  expect(body.code).toBe(1)
  // Cookie 已写入共享 context 的 Cookie jar；这里只确认状态，不消费 token 字段。
}

// ---------------------------------------------------------------- 白名单

/**
 * 只读端点白名单 —— 每一条都读过服务端源码确认「纯读」（SELECT 查询，无写库/写缓存/
 * 发 MQ/改状态），且无需路径参数（不依赖生产真实数据 id）：
 *
 * | 端点 | 服务端入口 | 纯读证据 |
 * |---|---|---|
 * | GET /api/users/me | UserController#getCurrentUser(:143) | → UserQueryHelper.getCurrentUser：userMapper.getById + Sa-Token session 读 |
 * | GET /api/users/settings | UserController#getUserSettings(:151) | → requireCurrentUser → 同上纯读 |
 * | GET /api/teams/my | TeamController#listMyTeams(:46) | → EnterpriseTeamService.listMyTeams：teamMapper.listMyTeams（SELECT）|
 * | GET /api/files | FileController#getFileList(:111) | → fileQueryPort.getFileListByParentId（查询端口）；⚠️ parentId 必填，固定用 -1（前端 ROOT_ID 约定，根目录）|
 * | GET /api/trash/files | TrashController#listTrashFiles(:38) | → fileQueryPort.getRecycleList（查询端口）|
 * | GET /api/storage/usage | StorageUsageController#usage(:36) | → StorageQuotaService.getUsage：缓存读 + 聚合 SELECT + **回写用量缓存**（缓存自愈，非业务数据变更，认定为可接受）|
 * | GET /api/im/conversations | ConversationController#listMyConversations(:53) | → ConversationService.listMyConversations：conversationMapper.listMyConversations（SELECT）|
 * | GET /api/im/system-notifications/unread-count | SystemNotificationController#getUnreadCount(:41) | → systemNotificationMapper.countUnread（SELECT COUNT）|
 *
 * 刻意**不**加入（读码结论）：
 * - GET /api/im/presence/me、/api/im/presence/users —— UserPresenceService.getPresence/
 *   listPresence 本身是纯 SELECT，**技术上纯读**；但在线状态属易变语义，白名单保持最小、
 *   不给「看似读、将来可能被加成心跳刷新」的端点开门，故不入列。
 * - 所有 /api/admin/**、/api/internal/**、一切非 GET 方法。
 */
const READONLY_ENDPOINTS = [
  { path: '/api/users/me', dataKind: 'object' },
  { path: '/api/users/settings', dataKind: 'object' },
  { path: '/api/teams/my', dataKind: 'array' },
  { path: '/api/files?parentId=-1', dataKind: 'object' },
  { path: '/api/trash/files', dataKind: 'object' },
  { path: '/api/storage/usage', dataKind: 'object' },
  { path: '/api/im/conversations', dataKind: 'array' },
  { path: '/api/im/system-notifications/unread-count', dataKind: 'object' },
]

// ---------------------------------------------------------------- 用例

test.describe('登录态只读冒烟', () => {
  // 套件级共享 context：登录次数 = 1 次/套件（限流风险最低的形态）。
  // ⚠️ 必须是共享的同一个 APIRequestContext 实例 —— 内置 `request` fixture 是
  // 「每条用例一个全新 context（独立 Cookie jar）」，靠它做「登录+断言」时
  // 登录态无法跨用例传递，并行 workers 下会随机 401。
  let sharedAuthedContext

  test.beforeAll(async () => {
    // newContext() 来自 playwright.request 命名空间（APIRequestContext 实例上没有它）。
    // baseURL 显式传入 —— 手动创建的 context 不继承 project 配置。
    sharedAuthedContext = await playwrightRequest.newContext({
      baseURL: test.info().project.use.baseURL,
    })
    await login(sharedAuthedContext)
  })

  test.afterAll(async () => {
    await sharedAuthedContext?.dispose()
    sharedAuthedContext = undefined
  })

  for (const { path, dataKind } of READONLY_ENDPOINTS) {
    test(`已登录 GET ${path} 返回 200 且业务体形状正确`, async () => {
      const response = await sharedAuthedContext.get(path)
      expect(response.status()).toBe(200)

      const body = await response.json()
      expect(body.code).toBe(1)
      if (dataKind === 'array') {
        expect(Array.isArray(body.data)).toBe(true)
      } else {
        expect(body.data).toBeTruthy()
        expect(typeof body.data).toBe('object')
      }
      // 刻意到此为止：不断言条数、字段值 —— 响应体也不打印（可能含 PII）。
    })

    test(`未登录 GET ${path} 被拒绝（401/403）`, async () => {
      // 全新匿名 context：证明该端点确实受保护，登录态用例不是白跑。
      // 🔴 必须用 playwrightRequest 命名空间的 newContext（APIRequestContext 实例上
      // 没有该方法）；baseURL 需显式传入 —— 匿名 context 不继承 project 配置。
      const anonymous = await playwrightRequest.newContext({
        baseURL: test.info().project.use.baseURL,
      })
      try {
        const response = await anonymous.get(path)
        expect([401, 403]).toContain(response.status())
      } finally {
        await anonymous.dispose()
      }
    })
  }
})
