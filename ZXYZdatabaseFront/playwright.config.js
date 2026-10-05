// Playwright E2E 配置（对应 ISSUE/17 #1/#2/#3）。
//
// 三个 project，边界刻意分开：
//   ui-mocked       —— UI 层回归：除浏览器外**不需要任何后端**（后端用 page.route() 打桩）。
//                      默认自动起本地 dev server；也可用 E2E_LOCAL_BASE_URL 指向已部署环境
//                      （此时加 E2E_SKIP_LOCAL_SERVER=1 跳过启动，见下方注释）。
//   smoke-readonly  —— 只读冒烟：打**真实环境**（默认本站生产），**只发 GET**、不登录、不写数据。
//   smoke-authed    —— 登录态只读冒烟（C-4）：打**真实环境**，用 E2E_USERNAME/E2E_PASSWORD
//                      登录拿 HttpOnly Cookie，对白名单端点**只发 GET、只断形状**。
//                      凭据缺失 → 响亮跳过；E2E_REQUIRE_AUTHED=1 且凭据缺失 → 直接 fail。
//                      ⚠️ 不进默认 CI：登录端点有 IP+用户名双维度限流（loginRateLimiter），
//                      反复跑会触发限流甚至误伤真实账号，只按需手动/低频跑。
//
// 用法：
//   npx playwright test --project=ui-mocked                       # 本机：自动起 dev server
//   npx playwright test --project=smoke-readonly                  # 只读冒烟（默认打线上主站）
//   E2E_BASE_URL=http://127.0.0.1:8081 npx playwright test --project=smoke-readonly
//   E2E_USERNAME=... E2E_PASSWORD=... npx playwright test --project=smoke-authed   # 登录态冒烟
//   npx playwright test --list                                    # 只列用例（不下载浏览器、不联网）
//
// 🔴 用例文件名必须是 `*.e2e.js`，**不要**改回 `*.spec.js`：
//    vitest 的默认 include 是 `**/*.{test,spec}.?(c|m)[jt]s?(x)`，它会把 `e2e/**/*.spec.js`
//    一并收走，然后在 `npm run test:coverage` 里以
//    「Playwright Test did not expect test() to be called here」成片失败（本机实测 2 failed）。
//    改名为 `.e2e.js` 后 vitest 不匹配，配合下面这行 testMatch 由 Playwright 独占该目录 ——
//    这样就不必去动 vite.config.mjs 的 vitest.exclude（覆盖默认值本身是另一种坑）。
import { defineConfig, devices } from '@playwright/test'

const LOCAL_BASE_URL = process.env.E2E_LOCAL_BASE_URL || 'http://127.0.0.1:5173'
const REMOTE_BASE_URL = process.env.E2E_BASE_URL || 'http://160.202.46.118:8081'

const uiProject = {
  name: 'ui-mocked',
  testDir: './e2e/ui',
  use: { ...devices['Desktop Chrome'], baseURL: LOCAL_BASE_URL },
}

// 默认自动拉起本地 dev server。两种情况下跳过：
//   1) E2E_SKIP_LOCAL_SERVER=1 —— 把同一批 UI 断言指向**已部署环境**时（baseURL 用 E2E_LOCAL_BASE_URL 指定）；
//   2) 环境本身不允许监听端口（部分受限沙箱）。
// 这两种情况下若 baseURL 不可达，用例会直接失败，不会静默跳过。
if (process.env.E2E_SKIP_LOCAL_SERVER !== '1') {
  uiProject.webServer = {
    command: 'npm run dev',
    url: LOCAL_BASE_URL,
    reuseExistingServer: true,
    timeout: 120_000,
  }
}

export default defineConfig({
  // 只收 `*.e2e.js`（理由见文件头注释）
  testMatch: '**/*.e2e.js',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list'], ['html', { outputFolder: 'e2e-report', open: 'never' }]],
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    uiProject,
    {
      name: 'smoke-readonly',
      testDir: './e2e/smoke',
      use: { ...devices['Desktop Chrome'], baseURL: REMOTE_BASE_URL },
    },
    {
      // 认证前置：登录一次并落 storageState（整个运行只登录 1 次）。
      // 必须在 smoke-authed 之前跑，故冒烟 project 用 dependencies 依赖它。
      name: 'smoke-authed-auth',
      testDir: './e2e/smoke-authed',
      testMatch: '**/*.setup.e2e.js',
      use: { ...devices['Desktop Chrome'], baseURL: REMOTE_BASE_URL },
    },
    {
      name: 'smoke-authed',
      testDir: './e2e/smoke-authed',
      // 冒烟用例文件；setup 由上面的 project 专职跑
      testIgnore: '**/*.setup.e2e.js',
      // 🔴 dependencies + storageState 组合 = 登录次数恒为 1，与 worker 数无关。
      // 为什么不能在冒烟文件里 test.beforeAll 登录：beforeAll 的执行次数是
      // **worker 进程数**（本机 20 核 ⇒ 10 workers ⇒ 10 次登录），会越过登录限流的
      // 「每用户名 5 次/分钟」⇒ 后半段用例拿到 code 4000「请求过于频繁」。
      // 2026-10-05 对生产实测确认：默认并行 8~11 条失败，--workers=1 则全绿。
      dependencies: ['smoke-authed-auth'],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: REMOTE_BASE_URL,
        storageState: 'e2e/.auth/authed-state.json',
      },
    },
  ],
})
