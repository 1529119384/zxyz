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
import { existsSync } from 'node:fs'

const STATE_PATH = 'e2e/.auth/authed-state.json'

const USERNAME = process.env.E2E_USERNAME || ''
const PASSWORD = process.env.E2E_PASSWORD || ''
const REQUIRE_AUTHED = process.env.E2E_REQUIRE_AUTHED === '1'
const HAS_CREDS = Boolean(USERNAME && PASSWORD)

// 🔁 幂等化（69 号 P2-1）：storageState 若仍有效则**跳过登录**。
//    起因：本轮审核连跑 3 次冒烟，第 2 次因「每用户名 5 次/分钟」登录限流拿到
//    HTTP 400 ⇒ setup 失败 ⇒ 后续 14 个用例 did not run，被误读成 P1「登录坏了」。
//    修法：先拿已落盘的会话发一个**只读探针**（GET /api/users/me），
//    code===1 即复用，不再发登录请求 ⇒ 重复执行不产生额外登录，天然幂等。
//    想要「强制重新登录」时设 E2E_REUSE_STATE=0（或删掉 e2e/.auth/authed-state.json）。
const REUSE_STATE = process.env.E2E_REUSE_STATE !== '0'

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
  const baseURL = setup.info().project.use.baseURL

  // ---- 复用短路（幂等化，见文件头注释）----
  if (REUSE_STATE && existsSync(STATE_PATH)) {
    const probe = await playwrightRequest.newContext({ baseURL, storageState: STATE_PATH })
    try {
      const res = await probe.get('/api/users/me')
      let body = null
      if (res.status() === 200) {
        body = await res.json().catch(() => ({}))
        if (body && body.code === 1) {
          console.log(
            '[smoke-authed-auth] 复用未过期的 storageState（会话仍有效），跳过本次登录 —— ' +
              '避免重复运行触发登录限流。如需强制重新登录请设 E2E_REUSE_STATE=0。',
          )
          return
        }
      }
      console.log(
        `[smoke-authed-auth] 已有 storageState 已失效（HTTP ${res.status()} / code ${body && body.code}），改为重新登录。`,
      )
    } catch (err) {
      console.log(`[smoke-authed-auth] 复用探针异常，改为重新登录：${err && err.message}`)
    } finally {
      await probe.dispose()
    }
  }

  const api = await playwrightRequest.newContext({
    baseURL,
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
