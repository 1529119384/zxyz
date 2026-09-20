// UI 层 E2E（后端全部打桩，不需要任何后端服务）。
//
// 📌 文件名刻意用 `.e2e.js` 而不是 `.spec.js`：vitest 的默认 include 会把 `**/*.spec.js`
//    全部收走（含 `e2e/`），导致 `npm run test:coverage` 里以
//    「Playwright Test did not expect test() to be called here」失败。理由同 playwright.config.js 头部注释。
//
// 覆盖的是「vitest + happy-dom 测不到」的部分：真实浏览器里的**真导航**与**真路由守卫**。
// 现有单测（src/router/__tests__/route-guard-coverage.spec.js）只能断言"路由表里有依赖全局守卫的路由"，
// 因为 vue-router 4 把 beforeEach 存在闭包里；这里用真浏览器跑一遍完整跳转。
import { test, expect } from '@playwright/test'

// 未登录上下文：任何后端调用都返回 401，等价于"没有有效会话"。
test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 401, message: '未登录' }),
    }),
  )
})

test.describe('路由守卫（真浏览器 + 打桩后端）', () => {
  test('未登录访问根路径会被重定向到 /login', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login/)
  })

  test('未登录访问深层受保护路由同样被重定向，且带回跳参数', async ({ page }) => {
    // /projects 是 layout 下真实存在的受保护子路由（router/index.js `path: 'projects'`）
    await page.goto('/projects', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login/)
    // 守卫会把原始目标写进 redirect 查询参数（router/index.js 的 sanitizeRedirectPath）
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/projects')
  })

  test('未登录访问不存在的路径 → 同样回落登录页，不向未认证者暴露「该路径不存在」', async ({ page }) => {
    // 这条钉住一个**有意的设计约束**：兜底路由 notFound 刻意不在 publicRouteNames 里。
    // 加了 catch-all 之后最容易出的偏差，就是让未认证用户直接看到 404 页 ——
    // 那样一来「路径存在与否」就成了可探测信号（存在 ⇒ 跳登录，不存在 ⇒ 404），
    // 等于把路由表暴露给未认证访问者。
    await page.goto('/no-such-page-abc-123', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/no-such-page-abc-123')
  })

  test('公开路由 /login 直接渲染，不被守卫拦截', async ({ page }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login$/)
    await expect(page).toHaveTitle(/指绣云章/)
    await expect(page.locator('#app')).not.toBeEmpty()
  })

  test('首屏不产生未捕获的 JS 错误', async ({ page }) => {
    const pageErrors = []
    page.on('pageerror', (error) => pageErrors.push(String(error)))
    await page.goto('/login', { waitUntil: 'load' })
    await page.waitForLoadState('networkidle')
    expect(pageErrors).toEqual([])
  })
})
