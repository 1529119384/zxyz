/**
 * 路由导航行为（真的发起 `router.push`，不是结构断言）
 *
 * 与 `route-guard-coverage.spec.js` 的分工：
 *   - `route-guard-coverage.spec.js`：**只读路由表** ⇒ 回答「有没有配守卫」（静态完整性）
 *   - 本文件：**真的跑一遍导航** ⇒ 回答「守卫到底拦不拦得住」（动态行为）
 *
 * 为什么必须把页面组件打桩：
 *   `src/views/**` 目前**完全不在**覆盖率分母里（没有任何测试加载过真实页面）。
 *   若真去 import 真实页面，会把 18 个页面及其依赖一并拉进分母，
 *   并以大量低覆盖文件把全局覆盖率拉垮。本文件验证的是**路由**（表 + 守卫），不是页面。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const h = vi.hoisted(() => ({
  // 共用桩组件：真实页面与本文件无关
  stubView: () => ({ default: { name: 'StubView', render: () => null } }),
  // 可变状态：用来在同一个文件里切换「未登录 / 已登录 / 无团队 / 会话异常」
  state: {
    profile: null,
    ensureSessionReady: async () => ({ shouldEnterNoTeam: false, hasTeams: true }),
  },
}))

// ---- 页面打桩（18 个懒加载出口，逐个桩掉）----
vi.mock('@/views/layout/index.vue', h.stubView)
vi.mock('@/views/index/index.vue', h.stubView)
vi.mock('@/views/login/index.vue', h.stubView)
vi.mock('@/views/register/index.vue', h.stubView)
vi.mock('@/views/my-share/index.vue', h.stubView)
vi.mock('@/views/recycle-bin/index.vue', h.stubView)
vi.mock('@/views/setting/index.vue', h.stubView)
vi.mock('@/views/setting/AccountSettings.vue', h.stubView)
vi.mock('@/views/setting/TeamAdmin.vue', h.stubView)
vi.mock('@/views/setting/SystemAdmin.vue', h.stubView)
vi.mock('@/views/setting/ConfigAdmin.vue', h.stubView)
vi.mock('@/views/setting/StorageAdmin.vue', h.stubView)
vi.mock('@/views/permission/index.vue', h.stubView)
vi.mock('@/views/chat/index.vue', h.stubView)
vi.mock('@/views/projects/index.vue', h.stubView)
vi.mock('@/views/join/team.vue', h.stubView)
vi.mock('@/views/share/index.vue', h.stubView)
vi.mock('@/views/no-team/index.vue', h.stubView)

// ---- store 打桩 ----
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: () => ({
    get profile() {
      return h.state.profile
    },
    clearAll: vi.fn(),
  }),
}))
vi.mock('@/store/session', () => ({
  useSessionStore: () => ({
    ensureSessionReady: () => h.state.ensureSessionReady(),
    resetSessionBootstrap: vi.fn(),
  }),
}))
vi.mock('@/store/chat', () => ({
  useChatStore: () => ({ clearReadSyncTimers: vi.fn() }),
}))

// 路由级守卫在本文件里一律放行 —— 它们的行为由 `guards.spec.js` 专门覆盖。
// ⚠️ 必须返回**真函数**：`requireSystemAdminRole()` 是在模块顶层被调用的，
//    若桩成 undefined，`beforeEnter` 会变成 undefined，
//    `route-guard-coverage.spec.js` 的「声明权限 meta ⇒ 必须有 beforeEnter」就会失效。
vi.mock('@/router/guards/permission', () => ({
  requireSystemAdminRole: () => (to, from, next) => next(),
  requirePermissionCenter: (to, from, next) => next(),
}))

import router from '@/router/index'

/** 已登录 + 会话就绪 + 有团队 —— 绝大多数用例的起点 */
function loginReady() {
  h.state.profile = { id: 1 }
  h.state.ensureSessionReady = async () => ({ shouldEnterNoTeam: false, hasTeams: true })
}

describe('路由导航（全局守卫的真实行为）', () => {
  beforeEach(async () => {
    h.state.profile = null
    h.state.ensureSessionReady = async () => ({ shouldEnterNoTeam: false, hasTeams: true })
    await router.replace('/login')
  })

  it('未登录访问受保护页面 → 被拦到登录页，并带上可回跳的 redirect 参数', async () => {
    await router.push('/index')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/index')
  })

  it('protocol-relative 注入（/%2F%2F…）的回跳参数被降级为 /index（不外跳）', async () => {
    // 该路径不匹配任何路由，vue-router 会打「No match found」告警。
    // 本用例正是要覆盖这种「畸形路径」输入 ⇒ 告警是预期的，
    // 故临时静音，而不是让它污染 CI 日志。
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await router.push('/%2F%2Fevil.example.com/steal')
    } finally {
      warn.mockRestore()
    }
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/index')
  })

  it('公开路由（分享页 / 注册页）免登录即可进入', async () => {
    await router.push('/s/share-key-123')
    expect(router.currentRoute.value.name).toBe('sharePublic')
    expect(router.currentRoute.value.query.redirect).toBeUndefined()

    await router.push('/register')
    expect(router.currentRoute.value.name).toBe('register')
  })

  it('已登录且会话就绪 → 正常进入受保护页面', async () => {
    loginReady()
    await router.push('/index')
    expect(router.currentRoute.value.name).toBe('index')
  })

  it('会话要求进入无团队态 → 被导向 no-team', async () => {
    h.state.profile = { id: 1 }
    h.state.ensureSessionReady = async () => ({ shouldEnterNoTeam: true, hasTeams: false })
    await router.push('/index')
    expect(router.currentRoute.value.name).toBe('noTeam')
  })

  it('已有团队时访问 no-team → 被弹回 index', async () => {
    // ⚠ 必须从别的路由出发：vue-router 会在**跑守卫之前**短路「重复导航」。
    //   若当前已停在 /no-team，再 push 同一位置守卫根本不会执行 —— 属于典型的假绿。
    loginReady()
    await router.push('/no-team')
    expect(router.currentRoute.value.name).toBe('index')
  })

  it('会话校验 401 → 视为登录态失效，回登录页', async () => {
    loginReady()
    h.state.ensureSessionReady = async () => {
      throw Object.assign(new Error('unauthorized'), { response: { status: 401 } })
    }
    await router.push('/index')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('会话校验遇到非认证错误（网络故障）→ 同样回登录页，不把用户留在异常态', async () => {
    loginReady()
    h.state.ensureSessionReady = async () => {
      throw new Error('network down')
    }
    await router.push('/index')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('legacy ?tab=permissions → 重定向到权限管理，且 query 里的 tab 被剔除', async () => {
    loginReady()
    await router.push('/setting?tab=permissions')
    expect(router.currentRoute.value.name).toBe('permissionCenter')
    expect(router.currentRoute.value.query.tab).toBeUndefined()
  })

  it('legacy ?tab= 缺省或未知 → 落到个人设置页', async () => {
    loginReady()
    await router.push('/setting')
    expect(router.currentRoute.value.name).toBe('accountSettings')

    await router.push('/setting?tab=not-a-real-tab')
    expect(router.currentRoute.value.name).toBe('accountSettings')
  })

  it('已登录时路由表全部受保护出口均可解析（无一处被守卫误拦）', async () => {
    loginReady()
    const targets = [
      '/team-space',
      '/my-share',
      '/projects',
      '/projects/42/space',
      '/chat',
      '/join/team/tok-123',
      '/recycle-bin',
      '/setting/account',
      '/setting/team-admin',
      '/setting/system-admin',
      '/setting/config-admin',
      '/setting/storage-admin',
      '/setting/permissions',
    ]
    const visited = []
    for (const target of targets) {
      await router.push(target)
      visited.push(router.currentRoute.value.name)
    }
    expect(visited).toHaveLength(targets.length)
    expect(visited).not.toContain('login')
    expect(visited).toContain('permissionCenter')
  })
})
