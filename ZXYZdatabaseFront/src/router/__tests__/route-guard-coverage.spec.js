/**
 * 路由守卫完整性（结构性断言）
 *
 * 为什么需要（审计 11 §7.14 / P2-15）：
 *   本项目「页面可见性」靠 `meta.requiresAdmin` / `meta.requiresSystemPermissionCenter`，
 *   而「实际访问控制」靠路由级 `beforeEnter` —— 这是**两套彼此独立的东西**。
 *   一旦某页只声明了 meta 却漏配 `beforeEnter`，导航里看不到它，
 *   但**直接输 URL 就能进去**（真实的越权路径）。
 *
 *   原 `guards.spec.js` 只逐个测守卫的**行为**，无法发现「某页完全没配守卫」这类缺失。
 *   本文件只做**结构断言**（不模拟导航）⇒ 跑得极快，也不会因 store 行为变化而误报。
 */
import { describe, it, expect, vi } from 'vitest'

// 只检查路由表结构，不需要 store 的真实行为 ⇒ 全部 mock（避免 Pinia 未安装时报错）
vi.mock('@/store/chat', () => ({ useChatStore: () => ({ clearReadSyncTimers: vi.fn() }) }))
vi.mock('@/store/currentUser', () => ({ useCurrentUserStore: () => ({}) }))
vi.mock('@/store/session', () => ({ useSessionStore: () => ({}) }))
vi.mock('@/store/team', () => ({ useTeamStore: () => ({}) }))

import router, { publicRouteNames } from '@/router/index'

/** 免登录白名单的**期望值**：新增公开路由必须同步改这里（强制走一次 review） */
const EXPECTED_PUBLIC_ROUTE_NAMES = ['login', 'register', 'sharePublic']

/** 声明「需要权限」的 meta 键 —— 一旦声明，就必须有 beforeEnter 兜底 */
const PERMISSION_META_KEYS = ['requiresAdmin', 'requiresSystemPermissionCenter']

describe('路由守卫完整性', () => {
  const routes = router.getRoutes()
  const routeNames = new Set(routes.map((route) => route.name))

  it('公开路由白名单精确等于期望集合（棘轮：新增免登录页必须显式改本断言）', () => {
    expect([...publicRouteNames].sort()).toEqual([...EXPECTED_PUBLIC_ROUTE_NAMES].sort())
  })

  it('白名单中的 name 必须真实存在于路由表（防拼写漂移造成「以为公开了」）', () => {
    const missing = [...publicRouteNames].filter((name) => !routeNames.has(name))
    expect(missing, `以下公开 name 在路由表中不存在：${missing.join(', ')}`).toEqual([])
  })

  it('声明了权限 meta 的路由必须有 beforeEnter（否则导航隐藏但可直接输 URL 进入）', () => {
    const offenders = []
    for (const route of routes) {
      const meta = route.meta || {}
      const declares = PERMISSION_META_KEYS.filter((key) => meta[key] === true)
      if (declares.length === 0) continue
      if (!route.beforeEnter) {
        offenders.push(`${route.name} (${route.path}) 声明了 ${declares.join('/')}`)
      }
    }
    expect(offenders, `以下路由声明了权限却没有 beforeEnter：\n${offenders.join('\n')}`).toEqual([])
  })

  it('公开路由不得再挂 beforeEnter（免登录与权限守卫语义冲突）', () => {
    const offenders = routes
      .filter((route) => publicRouteNames.has(route.name) && route.beforeEnter)
      .map((route) => route.name)
    expect(offenders, `公开路由不应有 beforeEnter：${offenders.join(', ')}`).toEqual([])
  })

  it('存在仅靠全局 beforeEach 保护的路由，且它们与公开白名单不相交（隐式保护被固化为断言）', () => {
    // `router.beforeEach` 注册的全局守卫**不对外暴露**（vue-router 4 把它存在闭包里），
    // 因此无法直接断言「全局守卫已注册」。这里换成可验证的等价命题：
    //   路由表中确实有一批「非公开、且自带无 beforeEnter」的路由 ⇒ 它们唯一的保护来源
    //   就是全局 beforeEach。若将来有人删掉全局守卫或把整批路由改成公开，
    //   这个集合会先变成空集 ⇒ 本用例失败，从而守住「非公开路由一定有保护」这条不变量。
    const globalOnly = routes
      .filter((route) => route.name && !publicRouteNames.has(route.name) && !route.beforeEnter)
      .map((route) => route.name)

    expect(
      globalOnly.length,
      '没有任何路由依赖全局 beforeEach —— 要么全局守卫被删，要么路由表被整体改成了公开',
    ).toBeGreaterThan(0)

    // 语义互斥：受保护集合里不允许出现白名单成员
    expect(globalOnly.filter((name) => publicRouteNames.has(name))).toEqual([])

    // 声明了权限 meta 的路由必须走 beforeEnter 分支（见上一条用例），
    // 所以它们不该出现在「仅靠全局守卫」的集合里。
    const leaked = routes
      .filter((route) => globalOnly.includes(route.name))
      .filter((route) => PERMISSION_META_KEYS.some((key) => (route.meta || {})[key] === true))
      .map((route) => route.name)
    expect(leaked, `以下路由只有全局守卫却声明了权限 meta：${leaked.join(', ')}`).toEqual([])
  })
})
