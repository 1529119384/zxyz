// Playwright E2E 配置（对应 ISSUE/17 #1/#2/#3）。
//
// 两个 project，边界刻意分开：
//   ui-mocked       —— UI 层回归：除浏览器外**不需要任何后端**（后端用 page.route() 打桩）。
//                      默认自动起本地 dev server；也可用 E2E_LOCAL_BASE_URL 指向已部署环境
//                      （此时加 E2E_SKIP_LOCAL_SERVER=1 跳过启动，见下方注释）。
//   smoke-readonly  —— 只读冒烟：打**真实环境**（默认本站生产），**只发 GET**、不登录、不写数据。
//
// 用法：
//   npx playwright test --project=ui-mocked                       # 本机：自动起 dev server
//   npx playwright test --project=smoke-readonly                  # 只读冒烟（默认打线上主站）
//   E2E_BASE_URL=http://127.0.0.1:8081 npx playwright test --project=smoke-readonly
//   npx playwright test --list                                    # 只列用例（不下载浏览器、不联网）
//
// ⚠️ smoke-readonly 只覆盖「无需凭证即可断言」的部分；`ISSUE/17 #3` 里需要
//    「只读固定账号 + 白名单接口清单」的那部分仍未做（卡在 `ISSUE/20` C-4）。
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
  ],
})
