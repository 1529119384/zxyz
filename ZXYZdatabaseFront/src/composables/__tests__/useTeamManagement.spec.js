import { describe, it, expect, vi, beforeEach } from 'vitest'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { TEAM_PERMISSION_CODES } from '@/constants/teamPermissions'
import { createTeamMember } from '@/api/team'
import { handleBusinessError } from '@/utils/error'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

vi.mock('@/api/team', () => ({
  createTeamMember: vi.fn(),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

// 这些单例只在「不传 options」的默认参数里才会被调用，本 spec 一律显式注入，
// 所以它们永远不会被执行 —— 这里 mock 掉纯粹是为了**阻断真实模块的加载链**：
// @/store/currentUser → @/api/auth → @/utils/request → 需要 VITE_API_BASE_URL，
// 否则整份 spec 会在 import 阶段就抛 "VITE_API_BASE_URL 未配置"。
vi.mock('@/store/currentUser', () => ({ useCurrentUserStore: vi.fn() }))
vi.mock('@/store/team', () => ({ useTeamStore: vi.fn() }))
vi.mock('@/store/chat', () => ({ useChatStore: vi.fn() }))
vi.mock('@/composables/useImWorkspace', () => ({ useImWorkspace: vi.fn() }))

// 该组合函数把所有外部依赖都做成了可注入的 options，故测试里**完全不碰 pinia/真路由**，
// 只传假实现。这既让用例极快，也顺带把「依赖注入点是否覆盖完整」这件事固定下来：
// 一旦有人把某个依赖改成直接 import 单例，下面的 setup 就会失效。
function setup(options = {}) {
  const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k)

  const teamStore = {
    selectedTeamId: 5,
    inviteLink: null,
    hasTeamPermission: vi.fn(() => false),
    loadTeamMembersSafe: vi.fn(() => Promise.resolve('members')),
    loadTeamManagement: vi.fn(() => Promise.resolve()),
    clearTeamManagement: vi.fn(),
    updateSelectedTeam: vi.fn(() => Promise.resolve()),
    publishAnnouncement: vi.fn(() => Promise.resolve()),
    muteMember: vi.fn(() => Promise.resolve()),
    unmuteMember: vi.fn(() => Promise.resolve()),
    createInviteLink: vi.fn(() => Promise.resolve()),
    approveJoinRequest: vi.fn(() => Promise.resolve()),
    rejectJoinRequest: vi.fn(() => Promise.resolve()),
    leaveSelectedTeam: vi.fn(() => Promise.resolve()),
    removeMember: vi.fn(() => Promise.resolve()),
    searchUsers: vi.fn(() => Promise.resolve()),
    inviteUser: vi.fn(() => Promise.resolve()),
    ...options.teamStore,
  }

  const chatStore = {
    loadConversations: vi.fn(() => Promise.resolve()),
    loadNotifications: vi.fn(() => Promise.resolve()),
    clearActiveConversation: vi.fn(),
    createDirectConversationAndOpen: vi.fn(() => Promise.resolve()),
    ...options.chatStore,
  }

  const workspace = {
    refreshTeamMemberContext: vi.fn(() => Promise.resolve()),
    ...options.workspace,
  }

  const router = has(options, 'router') ? options.router : { push: vi.fn() }
  const currentUserStore = has(options, 'currentUserStore')
    ? options.currentUserStore
    : { profile: { id: 100 } }
  const close = has(options, 'close') ? options.close : vi.fn()

  const api = useTeamManagement({
    teamStore,
    chatStore,
    workspace,
    router,
    currentUserStore,
    close,
    teamId: has(options, 'teamId') ? options.teamId : null,
  })

  return { api, teamStore, chatStore, workspace, router, currentUserStore, close }
}

/** 让 teamManagement.hasTeamPermission 只对给定 code 放行。 */
function grant(teamStore, codes) {
  teamStore.hasTeamPermission = vi.fn(({ code }) => codes.includes(code))
}

/** 让 teamManagement.hasTeamPermission 对任何 code 都放行。 */
function grantAll(teamStore) {
  teamStore.hasTeamPermission = vi.fn(() => true)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useTeamManagement: selectedTeamId', () => {
  it('未显式传 teamId 时回退到团队 Store 的 selectedTeamId', () => {
    const { api } = setup({ teamStore: { selectedTeamId: 12 } })
    expect(api.selectedTeamId.value).toBe(12)
  })

  it('显式 teamId（原始值）优先于团队 Store', () => {
    const { api } = setup({ teamStore: { selectedTeamId: 12 }, teamId: 99 })
    expect(api.selectedTeamId.value).toBe(99)
  })

  it('teamId 支持 Ref', () => {
    const { api } = setup({ teamId: ref(77) })
    expect(api.selectedTeamId.value).toBe(77)
  })

  it('teamId 支持函数（延迟求值，便于读最新值）', () => {
    const current = ref(31)
    const { api } = setup({ teamId: () => current.value })
    expect(api.selectedTeamId.value).toBe(31)
    current.value = 32
    expect(api.selectedTeamId.value).toBe(32)
  })

  it('teamId 数字字符串被归一化为数字', () => {
    const { api } = setup({ teamId: '88' })
    expect(api.selectedTeamId.value).toBe(88)
  })

  it('非法 teamId（0 / 负数 / 非数字）回退到团队 Store', () => {
    expect(setup({ teamStore: { selectedTeamId: 3 }, teamId: 0 }).api.selectedTeamId.value).toBe(3)
    expect(setup({ teamStore: { selectedTeamId: 3 }, teamId: -2 }).api.selectedTeamId.value).toBe(3)
    expect(
      setup({ teamStore: { selectedTeamId: 3 }, teamId: 'abc' }).api.selectedTeamId.value,
    ).toBe(3)
  })

  it('两边都非法时为 null', () => {
    expect(
      setup({ teamStore: { selectedTeamId: 0 }, teamId: 'x' }).api.selectedTeamId.value,
    ).toBeNull()
    expect(setup({ teamStore: { selectedTeamId: null } }).api.selectedTeamId.value).toBeNull()
  })
})

describe('useTeamManagement: currentUserId', () => {
  it('取 currentUserStore.profile.id', () => {
    const { api } = setup({ currentUserStore: { profile: { id: 55 } } })
    expect(api.currentUserId.value).toBe(55)
  })

  it('未注入 currentUserStore 时为 null（不抛错）', () => {
    const { api } = setup({ currentUserStore: null })
    expect(api.currentUserId.value).toBeNull()
  })

  it('profile 存在但没有 id 时为 null', () => {
    const { api } = setup({ currentUserStore: { profile: {} } })
    expect(api.currentUserId.value).toBeNull()
  })
})

describe('useTeamManagement: 权限 computed 与 code 映射', () => {
  it('canXxx 各自使用正确的权限 code', () => {
    const cases = [
      ['canUpdateTeam', 'updateTeam'],
      ['canCreateMember', 'createMember'],
      ['canInviteMember', 'inviteMember'],
      ['canAssignRole', 'assignRole'],
      ['canRemoveMember', 'removeMember'],
      ['canPublishAnnouncement', 'publishAnnouncement'],
      ['canManageMute', 'manageMute'],
      ['canManageInviteLink', 'manageInviteLink'],
      ['canReviewJoinRequests', 'reviewJoinRequest'],
    ]

    for (const [flag, codeKey] of cases) {
      const { api, teamStore } = setup()
      grant(teamStore, [TEAM_PERMISSION_CODES[codeKey]])
      expect(api[flag].value, `${flag} 应使用 ${TEAM_PERMISSION_CODES[codeKey]}`).toBe(true)
      expect(teamStore.hasTeamPermission).toHaveBeenCalledWith({
        teamId: 5,
        code: TEAM_PERMISSION_CODES[codeKey],
      })
    }
  })

  it('缺少对应 code 的权限时为 false', () => {
    const { api } = setup()
    expect(api.canUpdateTeam.value).toBe(false)
    expect(api.canRemoveMember.value).toBe(false)
  })

  it('canAccessPermissionCenter：命中权限中心任一 code 即为 true', () => {
    const { api, teamStore } = setup()
    grant(teamStore, [TEAM_PERMISSION_CODES.readAudit])
    expect(api.canAccessPermissionCenter.value).toBe(true)
  })

  it('canAccessPermissionCenter：无权限时 false', () => {
    const { api } = setup()
    expect(api.canAccessPermissionCenter.value).toBe(false)
  })

  it('canManageTeamSettings：命中团队管理任一 code 即为 true', () => {
    const { api, teamStore } = setup()
    grant(teamStore, [TEAM_PERMISSION_CODES.manageMute])
    expect(api.canManageTeamSettings.value).toBe(true)
  })

  it('canManageTeamSettings：无权限时 false', () => {
    const { api } = setup()
    expect(api.canManageTeamSettings.value).toBe(false)
  })

  it('权限中心不因"仅团队管理"类权限而放行（readAudit 等专属 code 才可）', () => {
    const { api, teamStore } = setup()
    grant(teamStore, [TEAM_PERMISSION_CODES.manageMute])
    expect(api.canAccessPermissionCenter.value).toBe(false)
  })
})

describe('useTeamManagement: joinLinkText', () => {
  it('无 inviteLink 时为空串', () => {
    const { api } = setup({ teamStore: { inviteLink: null } })
    expect(api.joinLinkText.value).toBe('')
  })

  it('有 joinUrl 时拼上站点 origin', () => {
    const { api } = setup({ teamStore: { inviteLink: { joinUrl: '/join/abc' } } })
    expect(api.joinLinkText.value).toBe(`${window.location.origin}/join/abc`)
  })

  it('joinUrl 为空串时为空串', () => {
    const { api } = setup({ teamStore: { inviteLink: { joinUrl: '' } } })
    expect(api.joinLinkText.value).toBe('')
  })
})

describe('useTeamManagement: roleText / displayName', () => {
  it('roleText 映射三个内置角色', () => {
    const { api } = setup()
    expect(api.roleText('team_owner')).toBe('团队所有者')
    expect(api.roleText('team_admin')).toBe('团队管理员')
    expect(api.roleText('team_member')).toBe('团队成员')
  })

  it('roleText 对未知角色原样返回，对空值回退"成员"', () => {
    const { api } = setup()
    expect(api.roleText('custom_role')).toBe('custom_role')
    expect(api.roleText('')).toBe('成员')
    expect(api.roleText(undefined)).toBe('成员')
    expect(api.roleText(null)).toBe('成员')
  })

  it('displayName 优先级：name > username > 用户 {id} > 未知用户', () => {
    const { api } = setup()
    expect(api.displayName({ userId: 1, name: '张三', username: 'zs' })).toBe('张三')
    expect(api.displayName({ userId: 1, username: 'zs' })).toBe('zs')
    expect(api.displayName({ userId: 1 })).toBe('用户 1')
    expect(api.displayName({})).toBe('未知用户')
    expect(api.displayName()).toBe('未知用户')
  })

  it('displayName 在没有 userId 时回退用 row.id', () => {
    const { api } = setup()
    expect(api.displayName({ id: 7 })).toBe('用户 7')
  })
})

describe('useTeamManagement: hasTeamPermission / hasAnyTeamPermission', () => {
  it('显式 teamId 优先于 selectedTeamId', () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    api.hasTeamPermission({ teamId: 9, code: 'c1' })
    expect(teamStore.hasTeamPermission).toHaveBeenCalledWith({ teamId: 9, code: 'c1' })
  })

  it('未传 teamId 时用 selectedTeamId', () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    api.hasTeamPermission({ code: 'c1' })
    expect(teamStore.hasTeamPermission).toHaveBeenCalledWith({ teamId: 5, code: 'c1' })
  })

  it('无 teamId（团队 Store 也为空）时直接 false，不下探到 Store', () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    expect(api.hasTeamPermission({ code: 'c1' })).toBe(false)
    expect(teamStore.hasTeamPermission).not.toHaveBeenCalled()
  })

  it('缺 code 时直接 false', () => {
    const { api, teamStore } = setup()
    expect(api.hasTeamPermission({ teamId: 5 })).toBe(false)
    expect(teamStore.hasTeamPermission).not.toHaveBeenCalled()
  })

  it('hasAnyTeamPermission 任一命中即 true', () => {
    const { api, teamStore } = setup()
    grant(teamStore, ['b'])
    expect(api.hasAnyTeamPermission(['a', 'b', 'c'])).toBe(true)
  })

  it('hasAnyTeamPermission 全不命中为 false', () => {
    const { api, teamStore } = setup()
    grant(teamStore, [])
    expect(api.hasAnyTeamPermission(['a', 'b'])).toBe(false)
  })

  it('hasAnyTeamPermission 对非数组入参返回 false（防御）', () => {
    const { api, teamStore } = setup()
    grantAll(teamStore)
    expect(api.hasAnyTeamPermission(null)).toBe(false)
    expect(api.hasAnyTeamPermission('a')).toBe(false)
  })

  it('hasAnyTeamPermission 可显式指定 teamId', () => {
    const { api, teamStore } = setup()
    grantAll(teamStore)
    api.hasAnyTeamPermission(['a'], 66)
    expect(teamStore.hasTeamPermission).toHaveBeenCalledWith({ teamId: 66, code: 'a' })
  })
})

describe('useTeamManagement: currentTeamId', () => {
  it('有选中团队时返回该 id', () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    expect(api.currentTeamId()).toBe(5)
  })

  it('无选中团队时提示并返回 null', () => {
    const { api } = setup({ teamStore: { selectedTeamId: null } })
    expect(api.currentTeamId()).toBeNull()
    expect(ElMessage.warning).toHaveBeenCalledWith('请先选择团队')
  })

  it('warn:false 时不提示但仍返回 null', () => {
    const { api } = setup({ teamStore: { selectedTeamId: null } })
    expect(api.currentTeamId({ warn: false })).toBeNull()
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })
})

describe('useTeamManagement: 加载类方法', () => {
  it('loadTeamMembersSafe 归一化 teamId 后透传，并返回其结果', async () => {
    const { api, teamStore } = setup()
    await expect(api.loadTeamMembersSafe('8')).resolves.toBe('members')
    expect(teamStore.loadTeamMembersSafe).toHaveBeenCalledWith(8)
  })

  it('loadTeamMembersSafe 未传参时用 selectedTeamId', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    await api.loadTeamMembersSafe()
    expect(teamStore.loadTeamMembersSafe).toHaveBeenCalledWith(5)
  })

  it('loadTeamManagementSafe 归一化后调用 loadTeamManagement', async () => {
    const { api, teamStore } = setup()
    await api.loadTeamManagementSafe('3')
    expect(teamStore.loadTeamManagement).toHaveBeenCalledWith(3)
    expect(teamStore.clearTeamManagement).not.toHaveBeenCalled()
  })

  it('loadTeamManagementSafe 无有效 teamId 时清空团队设置且不请求', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await api.loadTeamManagementSafe(0)
    expect(teamStore.clearTeamManagement).toHaveBeenCalled()
    expect(teamStore.loadTeamManagement).not.toHaveBeenCalled()
  })

  it('loadTeamManagementSafe 失败时交给 handleBusinessError（默认文案）', async () => {
    const error = new Error('boom')
    const { api, teamStore } = setup({
      teamStore: { loadTeamManagement: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.loadTeamManagementSafe(3)).resolves.toBeUndefined()
    expect(teamStore.loadTeamManagement).toHaveBeenCalledWith(3)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '加载团队设置失败')
  })

  it('loadTeamManagementSafe 支持自定义错误文案', async () => {
    const error = new Error('boom')
    const { api } = setup({ teamStore: { loadTeamManagement: vi.fn(() => Promise.reject(error)) } })
    await api.loadTeamManagementSafe(3, '自定义文案')
    expect(handleBusinessError).toHaveBeenCalledWith(error, '自定义文案')
  })
})

describe('useTeamManagement: openPermissionCenter', () => {
  it('无 router 时直接返回（不抛错、不关面板）', () => {
    const close = vi.fn()
    const { api, teamStore } = setup({ router: null, close })
    grantAll(teamStore)
    api.openPermissionCenter()
    expect(close).not.toHaveBeenCalled()
  })

  it('无权限时不跳转、不关面板', () => {
    const close = vi.fn()
    const { api, router } = setup({ close })
    api.openPermissionCenter()
    expect(router.push).not.toHaveBeenCalled()
    expect(close).not.toHaveBeenCalled()
  })

  it('有 router 且有权限时：先关面板再带 scope/teamId 跳转', () => {
    const close = vi.fn()
    const { api, router, teamStore } = setup({ close, teamStore: { selectedTeamId: 5 } })
    grantAll(teamStore)

    api.openPermissionCenter()

    expect(close).toHaveBeenCalledTimes(1)
    expect(router.push).toHaveBeenCalledWith({
      name: 'permissionCenter',
      query: { scope: 'team', teamId: '5' },
    })
  })

  it('没有选中团队时不跳转（query:{} 分支实际不可达，此处固化该事实）', () => {
    // 权限判定会把 teamId 回退为 selectedTeamId；没有选中团队时
    // hasTeamPermission 直接 false ⇒ canAccessPermissionCenter 为 false
    // ⇒ 提前返回。故 push 里 `selectedTeamId.value ? {...} : {}` 的 `{}` 分支
    // 在现有实现下永远走不到。这里不断言不可达分支，只断言真实行为。
    const { api, router, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    grantAll(teamStore)

    api.openPermissionCenter()

    expect(router.push).not.toHaveBeenCalled()
  })

  it('close 不是函数时不调用（仅接收了非函数值）', () => {
    const { api, router, teamStore } = setup({ close: 'not-a-function' })
    grantAll(teamStore)
    expect(() => api.openPermissionCenter()).not.toThrow()
    expect(router.push).toHaveBeenCalled()
  })
})

describe('useTeamManagement: 团队资料与公告', () => {
  it('saveTeamProfile 成功后刷新会话并提示', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = { name: '新名字' }

    await expect(api.saveTeamProfile(form)).resolves.toBe(true)

    expect(teamStore.updateSelectedTeam).toHaveBeenCalledWith(form)
    expect(chatStore.loadConversations).toHaveBeenCalledWith(5)
    expect(ElMessage.success).toHaveBeenCalledWith('团队资料已保存')
  })

  it('saveTeamProfile 无选中团队时返回 false 且不发请求', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.saveTeamProfile({})).resolves.toBe(false)
    expect(teamStore.updateSelectedTeam).not.toHaveBeenCalled()
    expect(chatStore.loadConversations).not.toHaveBeenCalled()
  })

  it('saveTeamProfile 失败时走 handleBusinessError 并返回 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, updateSelectedTeam: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.saveTeamProfile({})).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '保存团队资料失败')
  })

  it('publishAnnouncement 成功后刷新会话与通知，并清空表单', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = { title: '放假通知', content: '正文' }

    await expect(api.publishAnnouncement(form)).resolves.toBe(true)

    expect(teamStore.publishAnnouncement).toHaveBeenCalledWith(5, form)
    expect(chatStore.loadConversations).toHaveBeenCalledWith(5)
    expect(chatStore.loadNotifications).toHaveBeenCalledWith(5)
    expect(form).toEqual({ title: '', content: '' })
    expect(ElMessage.success).toHaveBeenCalledWith('公告已发布')
  })

  it('publishAnnouncement 无选中团队时返回 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.publishAnnouncement({})).resolves.toBe(false)
    expect(teamStore.publishAnnouncement).not.toHaveBeenCalled()
  })

  it('publishAnnouncement 失败时返回 false 且不清空表单', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, publishAnnouncement: vi.fn(() => Promise.reject(error)) },
    })
    const form = { title: 't', content: 'c' }
    await expect(api.publishAnnouncement(form)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '发布公告失败')
    expect(form).toEqual({ title: 't', content: 'c' })
  })
})

describe('useTeamManagement: 禁言', () => {
  it('muteMember 成功后清空表单字段', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = { userId: 9, reason: '刷屏' }

    await expect(api.muteMember(form)).resolves.toBe(true)

    expect(teamStore.muteMember).toHaveBeenCalledWith(5, form)
    expect(form).toEqual({ userId: null, reason: '' })
    expect(ElMessage.success).toHaveBeenCalledWith('成员已禁言')
  })

  it('muteMember 无选中团队时为 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.muteMember({})).resolves.toBe(false)
    expect(teamStore.muteMember).not.toHaveBeenCalled()
  })

  it('muteMember 失败时为 false 并报错', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, muteMember: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.muteMember({})).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '禁言成员失败')
  })

  it('unmuteMember 成功透传 teamId 与 userId', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(api.unmuteMember(9)).resolves.toBe(true)
    expect(teamStore.unmuteMember).toHaveBeenCalledWith(5, 9)
    expect(ElMessage.success).toHaveBeenCalledWith('已解除禁言')
  })

  it('unmuteMember 无选中团队时为 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.unmuteMember(9)).resolves.toBe(false)
    expect(teamStore.unmuteMember).not.toHaveBeenCalled()
  })

  it('unmuteMember 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, unmuteMember: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.unmuteMember(9)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '解除禁言失败')
  })
})

describe('useTeamManagement: 邀请与审核', () => {
  it('createInviteLink 成功透传表单', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = { expireDays: 7 }
    await expect(api.createInviteLink(form)).resolves.toBe(true)
    expect(teamStore.createInviteLink).toHaveBeenCalledWith(5, form)
    expect(ElMessage.success).toHaveBeenCalledWith('邀请链接已生成')
  })

  it('createInviteLink 无选中团队时为 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.createInviteLink({})).resolves.toBe(false)
    expect(teamStore.createInviteLink).not.toHaveBeenCalled()
  })

  it('createInviteLink 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, createInviteLink: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.createInviteLink({})).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '生成邀请链接失败')
  })

  it('approveJoinRequest 成功后刷新会话（不需要选中团队）', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.approveJoinRequest(11)).resolves.toBe(true)
    expect(teamStore.approveJoinRequest).toHaveBeenCalledWith(11)
    expect(chatStore.loadConversations).toHaveBeenCalled()
    expect(ElMessage.success).toHaveBeenCalledWith('已通过申请')
  })

  it('approveJoinRequest 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { approveJoinRequest: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.approveJoinRequest(11)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '审核申请失败')
  })

  it('rejectJoinRequest 成功时为 true', async () => {
    const { api, teamStore } = setup()
    await expect(api.rejectJoinRequest(12)).resolves.toBe(true)
    expect(teamStore.rejectJoinRequest).toHaveBeenCalledWith(12)
    expect(ElMessage.success).toHaveBeenCalledWith('已拒绝申请')
  })

  it('rejectJoinRequest 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({ teamStore: { rejectJoinRequest: vi.fn(() => Promise.reject(error)) } })
    await expect(api.rejectJoinRequest(12)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '审核申请失败')
  })

  it('inviteUser 成功透传 teamId 与 userId', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(api.inviteUser(21)).resolves.toBe(true)
    expect(teamStore.inviteUser).toHaveBeenCalledWith(5, 21)
    expect(ElMessage.success).toHaveBeenCalledWith('邀请已发送')
  })

  it('inviteUser 无选中团队时为 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.inviteUser(21)).resolves.toBe(false)
    expect(teamStore.inviteUser).not.toHaveBeenCalled()
  })

  it('inviteUser 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, inviteUser: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.inviteUser(21)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '发送邀请失败')
  })
})

describe('useTeamManagement: 退出团队与移除成员', () => {
  it('leaveTeam 成功后清空当前会话、刷新列表并关面板', async () => {
    const close = vi.fn()
    const { api, teamStore, chatStore } = setup({ close, teamStore: { selectedTeamId: 5 } })

    await expect(api.leaveTeam()).resolves.toBe(true)

    expect(teamStore.leaveSelectedTeam).toHaveBeenCalledWith(5)
    expect(chatStore.clearActiveConversation).toHaveBeenCalled()
    expect(chatStore.loadConversations).toHaveBeenCalled()
    expect(close).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).toHaveBeenCalledWith('已退出团队')
  })

  it('leaveTeam 无选中团队时为 false', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.leaveTeam()).resolves.toBe(false)
    expect(teamStore.leaveSelectedTeam).not.toHaveBeenCalled()
    expect(chatStore.clearActiveConversation).not.toHaveBeenCalled()
  })

  it('leaveTeam 失败时为 false', async () => {
    const error = new Error('x')
    const { api, chatStore } = setup({
      teamStore: { selectedTeamId: 5, leaveSelectedTeam: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.leaveTeam()).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '退出团队失败')
    expect(chatStore.clearActiveConversation).not.toHaveBeenCalled()
  })

  it('removeMember 成功后按 teamId 刷新会话', async () => {
    const { api, teamStore, chatStore } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(api.removeMember(31)).resolves.toBe(true)
    expect(teamStore.removeMember).toHaveBeenCalledWith(5, 31)
    expect(chatStore.loadConversations).toHaveBeenCalledWith(5)
    expect(ElMessage.success).toHaveBeenCalledWith('已移除成员')
  })

  it('removeMember 无选中团队时为 false', async () => {
    const { api, teamStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.removeMember(31)).resolves.toBe(false)
    expect(teamStore.removeMember).not.toHaveBeenCalled()
  })

  it('removeMember 失败时为 false', async () => {
    const error = new Error('x')
    const { api } = setup({
      teamStore: { selectedTeamId: 5, removeMember: vi.fn(() => Promise.reject(error)) },
    })
    await expect(api.removeMember(31)).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '移除成员失败')
  })
})

describe('useTeamManagement: createMemberAccount', () => {
  it('成功后创建账号、重置表单并刷新成员上下文', async () => {
    const { api, workspace } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = {
      username: '  newuser  ',
      password: '  secret6  ',
      name: '  张三  ',
      roleCode: 'team_admin',
    }

    await expect(api.createMemberAccount(form)).resolves.toBe(true)

    expect(createTeamMember).toHaveBeenCalledWith(5, {
      username: 'newuser',
      password: 'secret6',
      name: '张三',
      roleCode: 'team_admin',
    })
    expect(workspace.refreshTeamMemberContext).toHaveBeenCalledWith(5)
    expect(form).toEqual({ username: '', password: '', name: '', roleCode: 'team_member' })
    expect(ElMessage.success).toHaveBeenCalledWith('成员账号已创建')
  })

  it('name 为空白时传 null（而不是空串）', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    await api.createMemberAccount({
      username: 'u',
      password: '123456',
      name: '   ',
      roleCode: 'team_member',
    })
    expect(createTeamMember).toHaveBeenCalledWith(5, {
      username: 'u',
      password: '123456',
      name: null,
      roleCode: 'team_member',
    })
  })

  it('无选中团队时为 false，只提示选团队、不做表单校验', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.createMemberAccount({ username: '', password: '' })).resolves.toBe(false)
    expect(createTeamMember).not.toHaveBeenCalled()
    // 先因缺 teamId 提示；用户名/密码的校验在其之后，故不应出现
    expect(ElMessage.warning).toHaveBeenCalledWith('请先选择团队')
    expect(ElMessage.warning).not.toHaveBeenCalledWith('请填写成员用户名和初始密码')
  })

  it('用户名或密码为空时提示并返回 false', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(api.createMemberAccount({ username: '  ', password: '123456' })).resolves.toBe(
      false,
    )
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写成员用户名和初始密码')

    vi.clearAllMocks()
    await expect(api.createMemberAccount({ username: 'u', password: '   ' })).resolves.toBe(false)
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写成员用户名和初始密码')
    expect(createTeamMember).not.toHaveBeenCalled()
  })

  it('密码短于 6 位时提示并返回 false', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(api.createMemberAccount({ username: 'u', password: '12345' })).resolves.toBe(false)
    expect(ElMessage.warning).toHaveBeenCalledWith('初始密码不能少于 6 位')
    expect(createTeamMember).not.toHaveBeenCalled()
  })

  it('恰好 6 位密码可通过', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(
      api.createMemberAccount({ username: 'u', password: '123456', name: 'n', roleCode: 'r' }),
    ).resolves.toBe(true)
  })

  it('密码校验基于 trim 后的长度（前后空格不计入）', async () => {
    const { api } = setup({ teamStore: { selectedTeamId: 5 } })
    await expect(
      api.createMemberAccount({ username: 'u', password: '  12345  ', name: '', roleCode: 'r' }),
    ).resolves.toBe(false)
    expect(ElMessage.warning).toHaveBeenCalledWith('初始密码不能少于 6 位')
  })

  it('接口失败时为 false 且不重置表单', async () => {
    const error = new Error('x')
    createTeamMember.mockRejectedValueOnce(error)
    const { api, workspace } = setup({ teamStore: { selectedTeamId: 5 } })
    const form = { username: 'u', password: '123456', name: '', roleCode: 'r' }

    await expect(api.createMemberAccount(form)).resolves.toBe(false)

    expect(handleBusinessError).toHaveBeenCalledWith(error, '创建成员账号失败')
    expect(workspace.refreshTeamMemberContext).not.toHaveBeenCalled()
    expect(form.username).toBe('u')
  })
})

describe('useTeamManagement: searchUsers / startDirectChat', () => {
  it('searchUsers 成功为 true', async () => {
    const { api, teamStore } = setup()
    await expect(api.searchUsers('张')).resolves.toBe(true)
    expect(teamStore.searchUsers).toHaveBeenCalledWith('张')
  })

  it('searchUsers 失败为 false 并报错', async () => {
    const error = new Error('x')
    const { api } = setup({ teamStore: { searchUsers: vi.fn(() => Promise.reject(error)) } })
    await expect(api.searchUsers('张')).resolves.toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(error, '搜索用户失败')
  })

  it('startDirectChat 成功后创建私聊、关面板并跳转 chatHome', async () => {
    const close = vi.fn()
    const { api, chatStore, router } = setup({ close, teamStore: { selectedTeamId: 5 } })

    await expect(api.startDirectChat(42)).resolves.toBe(true)

    expect(chatStore.createDirectConversationAndOpen).toHaveBeenCalledWith(5, 42)
    expect(close).toHaveBeenCalledTimes(1)
    expect(router.push).toHaveBeenCalledWith({ name: 'chatHome' })
  })

  it('startDirectChat 无 router 时仍返回 true（只是不跳转）', async () => {
    const { api, chatStore } = setup({ router: null, teamStore: { selectedTeamId: 5 } })
    await expect(api.startDirectChat(42)).resolves.toBe(true)
    expect(chatStore.createDirectConversationAndOpen).toHaveBeenCalled()
  })

  it('startDirectChat 无选中团队时为 false', async () => {
    const { api, chatStore } = setup({ teamStore: { selectedTeamId: null } })
    await expect(api.startDirectChat(42)).resolves.toBe(false)
    expect(chatStore.createDirectConversationAndOpen).not.toHaveBeenCalled()
  })

  it('startDirectChat 失败时为 false 且不关面板', async () => {
    const error = new Error('x')
    const close = vi.fn()
    const { api } = setup({
      close,
      teamStore: { selectedTeamId: 5 },
      chatStore: { createDirectConversationAndOpen: vi.fn(() => Promise.reject(error)) },
    })

    await expect(api.startDirectChat(42)).resolves.toBe(false)

    expect(handleBusinessError).toHaveBeenCalledWith(error, '创建私聊失败')
    expect(close).not.toHaveBeenCalled()
  })
})

describe('useTeamManagement: 返回面', () => {
  it('对外暴露的键集合与文档约定一致', () => {
    const { api } = setup()
    for (const key of [
      'selectedTeamId',
      'currentUserId',
      'canAccessPermissionCenter',
      'canManageTeamSettings',
      'canUpdateTeam',
      'canCreateMember',
      'canInviteMember',
      'canAssignRole',
      'canRemoveMember',
      'canPublishAnnouncement',
      'canManageMute',
      'canManageInviteLink',
      'canReviewJoinRequests',
      'joinLinkText',
      'roleText',
      'displayName',
      'hasTeamPermission',
      'hasAnyTeamPermission',
      'currentTeamId',
      'loadTeamMembersSafe',
      'loadTeamManagementSafe',
      'openPermissionCenter',
      'saveTeamProfile',
      'publishAnnouncement',
      'muteMember',
      'unmuteMember',
      'createInviteLink',
      'approveJoinRequest',
      'rejectJoinRequest',
      'leaveTeam',
      'removeMember',
      'createMemberAccount',
      'searchUsers',
      'inviteUser',
      'startDirectChat',
    ]) {
      expect(key in api, `缺少导出键 ${key}`).toBe(true)
    }
  })

  it('权限相关的返回值都是可读的 computed', () => {
    const { api } = setup()
    expect(api.canUpdateTeam.value).toBe(false)
    expect(api.selectedTeamId.value).toBe(5)
    expect(api.currentUserId.value).toBe(100)
  })

  it('每次调用都得到独立的实例（依赖注入不共享状态）', () => {
    const a = setup({ teamStore: { selectedTeamId: 1 } })
    const b = setup({ teamStore: { selectedTeamId: 2 } })
    expect(a.api.selectedTeamId.value).toBe(1)
    expect(b.api.selectedTeamId.value).toBe(2)
    expect(computed(() => a.api.selectedTeamId.value).value).toBe(1)
  })
})
