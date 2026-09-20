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

// ---- 页面打桩（19 个懒加载出口，逐个桩掉）----
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
vi.mock('@/views/not-found/index.vue', h.stubView)

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
// ⚠️ 桩体必须用 **return 风格**（vue-router 5 已废弃 `next()`）：
//    早先这里写的是 `(to, from, next) => next()`，于是每跑一次本文件就刷一片
//    `[VUE_ROUTER_R0025] The next() callback ... is deprecated`。真实守卫
//    （src/router/guards/permission.js）其实早就改成了 return 风格，
//    告警**只**来自这个桩 ⇒ 会让人误判成「生产代码还没改完」。桩也要跟上。
vi.mock('@/router/guards/permission', () => ({
  // 工厂：路由表顶层 `requireSystemAdminRole()` 会调用它，须返回守卫函数
  requireSystemAdminRole: () => () => true,
  // 直接作为 `beforeEnter: requirePermissionCenter` 使用 ⇒ 它本身就是守卫函数
  requirePermissionCenter: () => true,
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
    // 本用例只钉一件事：**回跳参数必须被清洗**。
    // `/%2F%2Fevil.example.com/steal` 解码后是 `//evil.example.com/steal`，
    // 若原样写进 `?redirect=` 就是一个可用的外跳（开放重定向）载荷。
    //
    // 📌 2026-09-20 加了 catch-all 路由后，本用例的**前提变了**：
    //    该路径现在由 `notFound` 接住，不再产生 vue-router 的「No match found」告警
    //    ⇒ 原先为静音该告警而加的 `console.warn` spy 已成**死代码**，一并删除。
    //      留着一个永不触发的 spy 比删掉更危险：它会让人以为这里仍在验证告警行为。
    //    断言本身不受影响 —— 清洗发生在 redirect 参数上，与路径是否匹配路由无关。
    await router.push('/%2F%2Fevil.example.com/steal')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/index')
  })

  it('未匹配路径（已登录）→ 落到兜底 404 路由，不再是白屏', async () => {
    loginReady()
    await router.push('/definitely-not-a-real-route')
    expect(router.currentRoute.value.name).toBe('notFound')
    // 404 不是被守卫重定向过来的，不应带 redirect 参数
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })

  it('未匹配路径（未登录）→ 仍统一回登录页（该路径存不存在对未认证者不可区分）', async () => {
    // 这是一条**有意的设计约束**：`notFound` 刻意不在 `publicRouteNames` 里。
    // 好处是未认证访问者对「路径存在」与「路径不存在」拿到完全一致的响应，
    // 无法靠「有没有 404」探出路由表的存在性。
    // 若将来有人把 notFound 加进白名单，本用例会失败 ⇒ 强制那次改动走一次 review，
    // 而不是让这个副作用悄无声息地生效。
    await router.push('/definitely-not-a-real-route')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/definitely-not-a-real-route')
  })

  it('catch-all 不吞噬任何已声明的路由（加 catch-all 的头号风险）', async () => {
    loginReady()
    const shadowCheck = [
      ['/', 'index'], // 根路径：自身有 redirect，最终落在 index
      ['/projects', 'projects'], // layout 下的普通静态子路由
      ['/setting/permissions', 'permissionCenter'], // 嵌套两层、且隔壁有 alias 的子路由
      ['/s/abc', 'sharePublic'], // 带动态段的公开路由（形态上最像 catch-all）
    ]
    for (const [target, expected] of shadowCheck) {
      await router.push(target)
      expect(router.currentRoute.value.name, `${target} 被 catch-all 吃掉了`).toBe(expected)
    }
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
