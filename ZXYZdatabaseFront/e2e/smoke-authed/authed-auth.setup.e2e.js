// 登录态冒烟 · 认证前置（Playwright "setup project"）。
//
// 为什么需要这个文件（而不是在冒烟文件里 test.beforeAll 登录）：
//   `test.beforeAll` 的执行次数 = **worker 进程数**，不是「每个套件一次」。
//   默认 workers ≈ CPU/2（本机 20 核 ⇒ 10 个 worker）⇒ 冒烟文件里的 beforeAll
//   会被执行最多 10 次 = 10 次登录，直接越过登录限流的「每用户名 5 次/分钟」
//   （LoginRateLimiter.usernameLimitPerMinute 默认 5）⇒ 后半段用例拿到
//   「请求过于频繁，请稍后再试」(code 4000)。
//   2026-10-05 对生产实测确认过这个形态：默认并行 8~11 条失败，--workers=1 则全绿。
//
//   正确做法就是把「登录」从 worker 里挪出来，变成**整个测试运行只发生一次**：
//   setup project 先跑，登录一次并把 Cookie 落成 storageState 文件；
//   冒烟 project 声明 dependencies + use.storageState 直接复用，自身不再登录。
//   ⇒ 登录次数恒为 1，与 worker 数无关。
//
// ⚠️ storageState 里是**真实会话 Cookie**，属凭据物：
//   - 路径 `e2e/.auth/` 已在 .gitignore 中显式忽略（务必保持）；
//   - 不要把它复制到别处、不要提交、不要贴进 issue。
import { test as setup, expect, request as playwrightRequest } from '@playwright/test'

const STATE_PATH = 'e2e/.auth/authed-state.json'

const USERNAME = process.env.E2E_USERNAME || ''
const PASSWORD = process.env.E2E_PASSWORD || ''
const REQUIRE_AUTHED = process.env.E2E_REQUIRE_AUTHED === '1'
const HAS_CREDS = Boolean(USERNAME && PASSWORD)

// 与 authed-smoke.e2e.js 同款守卫（必须在**模块加载期**，理由见那个文件）。
// ⚠️ setup 也必须跳过：否则凭据缺失时它会拿空账号去登录 ⇒ HTTP 400 ⇒
//    连带 dependencies 把整个 smoke-authed 拖成 failed，破坏
//    「无凭据 = 全部 skip + exit 0」这一约定（2026-10-05 实测到过这个回归）。
if (REQUIRE_AUTHED && !HAS_CREDS) {
  throw new Error(
    '[smoke-authed-auth] E2E_REQUIRE_AUTHED=1 但 E2E_USERNAME/E2E_PASSWORD 未配置 —— ' +
      'fail-closed：拒绝以「全部跳过」冒充门禁通过。请配置凭据，或去掉 E2E_REQUIRE_AUTHED。',
  )
}

if (!HAS_CREDS) {
  console.warn(
    '[smoke-authed-auth] 未提供 E2E_USERNAME/E2E_PASSWORD，跳过登录前置（storageState 不生成）。',
  )
}

setup.skip(!HAS_CREDS, '未提供 E2E_USERNAME/E2E_PASSWORD，跳过登录前置')

setup('登录一次并保存 storageState', async () => {
  const api = await playwrightRequest.newContext({
    baseURL: setup.info().project.use.baseURL,
  })
  try {
    const response = await api.post('/api/users/login', {
      data: { username: USERNAME, password: PASSWORD, rememberMe: false },
    })
    expect(
      response.status(),
      `登录接口应返回 HTTP 200（实际 ${response.status()}）；` +
        '若为 400 且 msg 含「过于频繁」，说明触发了登录限流，请等 1 分钟后重试',
    ).toBe(200)

    const body = await response.json()
    expect(
      body.code,
      `登录应成功（code===1），实际 code=${body.code} msg=${body.msg}。` +
        'code=4100 ⇒ 账号或密码错误；code=4000 ⇒ 触发限流。',
    ).toBe(1)

    // 落盘 Cookie（HttpOnly Cookie 同样会被写入 storageState 的 cookies 数组）
    await api.storageState({ path: STATE_PATH })
  } finally {
    await api.dispose()
  }
})
