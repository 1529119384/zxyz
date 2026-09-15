// 只读线上冒烟（对应 ISSUE/17 #3 的「不需要凭证」那一半）。
//
// 📌 文件名刻意用 `.e2e.js`（避开 vitest 的 `**/*.spec.js` 默认 include），理由见 playwright.config.js。
//
// 硬约束：**只发 GET、不登录、不写任何数据**。任何需要写权限或需要账号的检查都不放这里
// —— 那部分卡在「只读固定账号 + 白名单接口清单」（ISSUE/20 的 C-4）。
//
// 跑法（默认打本站生产）：
//   npx playwright test --project=smoke-readonly
//   E2E_BASE_URL=http://127.0.0.1:8081 npx playwright test --project=smoke-readonly
import { test, expect } from '@playwright/test'

const TITLE = /指绣云章/

test.describe('只读冒烟', () => {
  test('首页返回 200 且标题正确', async ({ page }) => {
    const response = await page.goto('/', { waitUntil: 'domcontentloaded' })
    expect(response?.status()).toBe(200)
    await expect(page).toHaveTitle(TITLE)
  })

  test('网关健康检查返回 200', async ({ request }) => {
    const response = await request.get('/api/health')
    expect(response.status()).toBe(200)
  })

  test('未登录访问受保护路由被重定向到登录页', async ({ page }) => {
    await page.goto('/projects', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/login/)
  })

  test('首屏同源资源无 4xx/5xx', async ({ page, baseURL }) => {
    const origin = new URL(baseURL).origin
    const failures = []
    page.on('response', (response) => {
      if (response.status() >= 400 && response.url().startsWith(origin)) {
        failures.push(`${response.status()} ${response.url()}`)
      }
    })
    await page.goto('/', { waitUntil: 'load' })
    await page.waitForLoadState('networkidle')
    expect(failures).toEqual([])
  })

  test('首屏无未捕获的 JS 错误', async ({ page }) => {
    const pageErrors = []
    page.on('pageerror', (error) => pageErrors.push(String(error)))
    await page.goto('/', { waitUntil: 'load' })
    await page.waitForLoadState('networkidle')
    expect(pageErrors).toEqual([])
  })
})
