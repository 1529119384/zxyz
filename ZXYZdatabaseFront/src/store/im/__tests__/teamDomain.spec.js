import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'

import { createAdminTeam } from '@/api/adminTeam'
import {
  fetchMyTeams,
  fetchTeamMembers,
  leaveTeam,
  removeTeamMember,
  updateTeam,
} from '@/api/team'
import {
  acceptTeamInvitation,
  approveTeamJoinRequest,
  createTeamInviteLink,
  fetchTeamJoinRequests,
  fetchTeamMutes,
  inviteTeamUser,
  muteTeamMember,
  publishTeamAnnouncement,
  rejectTeamInvitation,
  rejectTeamJoinRequest,
  searchTeamInviteCandidates,
  submitTeamJoinRequest,
  unmuteTeamMember,
} from '@/api/teamIm'
import * as currentUserModule from '@/store/currentUser'
import { createTeamDomain } from '@/store/im/teamDomain'

// useCurrentUserStore 的 mock 是普通函数（非 vi.fn），只能通过 spyOn 覆盖其返回值。
let profileSpy = null

function mockProfileDefaultTeamId(teamId) {
  profileSpy?.mockRestore()
  profileSpy = vi
    .spyOn(currentUserModule, 'useCurrentUserStore')
    .mockReturnValue(teamId === undefined ? {} : { profile: { defaultTeamId: teamId } })
}

function restoreProfileSpy() {
  profileSpy?.mockRestore()
  profileSpy = null
}

vi.mock('@/api/adminTeam', () => ({
  createAdminTeam: vi.fn(),
}))
vi.mock('@/api/team', () => ({
  fetchMyTeams: vi.fn(),
  fetchTeamMembers: vi.fn(),
  leaveTeam: vi.fn(),
  removeTeamMember: vi.fn(),
  updateTeam: vi.fn(),
}))
vi.mock('@/api/teamIm', () => ({
  acceptTeamInvitation: vi.fn(),
  approveTeamJoinRequest: vi.fn(),
  createTeamInviteLink: vi.fn(),
  fetchTeamJoinRequests: vi.fn(),
  fetchTeamMutes: vi.fn(),
  inviteTeamUser: vi.fn(),
  muteTeamMember: vi.fn(),
  publishTeamAnnouncement: vi.fn(),
  rejectTeamInvitation: vi.fn(),
  rejectTeamJoinRequest: vi.fn(),
  searchTeamInviteCandidates: vi.fn(),
  submitTeamJoinRequest: vi.fn(),
  unmuteTeamMember: vi.fn(),
}))
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: () => ({ profile: { defaultTeamId: null } }),
}))

function createDomain() {
  const state = {
    teams: ref([]),
    selectedTeamId: ref(null),
    defaultTeamId: ref(null),
    teamMembers: ref([]),
    teamMutes: ref([]),
    joinRequests: ref([]),
    inviteLink: ref(null),
    userSearchResults: ref([]),
  }
  const deps = {
    clearActiveConversation: vi.fn(),
    loadConversations: vi.fn(() => Promise.resolve([])),
    loadNotifications: vi.fn(() => Promise.resolve([])),
    emitter: { emit: vi.fn() },
  }
  const domain = createTeamDomain(state, deps)
  return { state, deps, domain }
}

describe('teamDomain', () => {
  describe('hasTeamPermission', () => {
    it('缺 teamId 且未选中团队时返回 false', () => {
      const { domain } = createDomain()
      expect(domain.hasTeamPermission({ teamId: null, code: 'manage' })).toBe(false)
      expect(domain.hasTeamPermission({ teamId: undefined, code: 'manage' })).toBe(false)
    })

    it('缺 code 时返回 false', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: ['manage'] }]
      expect(domain.hasTeamPermission({ teamId: 1, code: '' })).toBe(false)
      expect(domain.hasTeamPermission({ teamId: 1, code: null })).toBe(false)
    })

    it('teamId 命中团队且权限包含 code 时返回 true', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: ['manage', 'upload'] }]
      expect(domain.hasTeamPermission({ teamId: 1, code: 'manage' })).toBe(true)
    })

    it('权限列表不含 code 时返回 false', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: ['upload'] }]
      expect(domain.hasTeamPermission({ teamId: 1, code: 'manage' })).toBe(false)
    })

    it('未传 teamId 时回落到选中团队', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: ['manage'] }]
      state.selectedTeamId.value = 1
      expect(domain.hasTeamPermission({ code: 'manage' })).toBe(true)
    })

    it('团队不存在时返回 false', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: ['manage'] }]
      expect(domain.hasTeamPermission({ teamId: 999, code: 'manage' })).toBe(false)
    })

    it('myPermissions 缺失时返回 false', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1 }]
      expect(domain.hasTeamPermission({ teamId: 1, code: 'manage' })).toBe(false)
    })
  })

  describe('selectedTeam', () => {
    it('命中选中团队时返回该团队对象', () => {
      const { state, domain } = createDomain()
      const team = { id: 3, name: 'T3' }
      state.teams.value = [{ id: 1, name: 'T1' }, team]
      state.selectedTeamId.value = 3
      expect(domain.selectedTeam.value).toEqual(team)
    })

    it('团队 id 为数字字符串时也能命中', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: '3', name: 'T3' }]
      state.selectedTeamId.value = 3
      expect(domain.selectedTeam.value).toEqual({ id: '3', name: 'T3' })
    })

    it('未命中或未选中团队时返回 null', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1 }]
      state.selectedTeamId.value = 2
      expect(domain.selectedTeam.value).toBeNull()
      state.selectedTeamId.value = null
      expect(domain.selectedTeam.value).toBeNull()
    })
  })

  describe('currentTeamPermissions', () => {
    it('选中团队带权限数组时返回该数组', () => {
      const { state, domain } = createDomain()
      const permissions = ['manage', 'upload']
      state.teams.value = [{ id: 1, myPermissions: permissions }]
      state.selectedTeamId.value = 1
      expect(domain.currentTeamPermissions.value).toEqual(permissions)
    })

    it('myPermissions 缺失或非数组时返回空数组', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, myPermissions: 'manage' }]
      state.selectedTeamId.value = 1
      expect(domain.currentTeamPermissions.value).toEqual([])
      state.teams.value = [{ id: 1 }]
      expect(domain.currentTeamPermissions.value).toEqual([])
    })

    it('没有选中团队时返回空数组', () => {
      const { domain } = createDomain()
      expect(domain.currentTeamPermissions.value).toEqual([])
    })
  })

  describe('hasTeams / needsTeamSwitcher', () => {
    it('hasTeams 反映团队列表是否非空', () => {
      const { state, domain } = createDomain()
      expect(domain.hasTeams.value).toBe(false)
      state.teams.value = [{ id: 1 }]
      expect(domain.hasTeams.value).toBe(true)
    })

    it('needsTeamSwitcher 在团队数 >= 2 时为 true', () => {
      const { state, domain } = createDomain()
      expect(domain.needsTeamSwitcher.value).toBe(false)
      state.teams.value = [{ id: 1 }]
      expect(domain.needsTeamSwitcher.value).toBe(false)
      state.teams.value = [{ id: 1 }, { id: 2 }]
      expect(domain.needsTeamSwitcher.value).toBe(true)
    })
  })

  describe('setSelectedTeam / setDefaultTeam', () => {
    it('setSelectedTeam 写入合法 id 并归一化非法值', () => {
      const { state, domain } = createDomain()
      domain.setSelectedTeam(12)
      expect(state.selectedTeamId.value).toBe(12)
      domain.setSelectedTeam('7')
      expect(state.selectedTeamId.value).toBe(7)
      domain.setSelectedTeam(0)
      expect(state.selectedTeamId.value).toBeNull()
      domain.setSelectedTeam('abc')
      expect(state.selectedTeamId.value).toBeNull()
      domain.setSelectedTeam(null)
      expect(state.selectedTeamId.value).toBeNull()
    })

    it('setDefaultTeam 写入合法 id 并归一化非法值', () => {
      const { state, domain } = createDomain()
      domain.setDefaultTeam('9')
      expect(state.defaultTeamId.value).toBe(9)
      domain.setDefaultTeam(-1)
      expect(state.defaultTeamId.value).toBeNull()
      domain.setDefaultTeam(undefined)
      expect(state.defaultTeamId.value).toBeNull()
    })
  })

  describe('syncDefaultTeamFromProfile', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      mockProfileDefaultTeamId(null)
    })
    afterEach(restoreProfileSpy)

    it('profile 带默认团队时写入并返回该 id', () => {
      mockProfileDefaultTeamId(42)
      const { state, domain } = createDomain()
      expect(domain.syncDefaultTeamFromProfile()).toBe(42)
      expect(state.defaultTeamId.value).toBe(42)
    })

    it('profile 无默认但已有默认团队时清空默认团队', () => {
      const { state, domain } = createDomain()
      state.defaultTeamId.value = 5
      expect(domain.syncDefaultTeamFromProfile()).toBeNull()
      expect(state.defaultTeamId.value).toBeNull()
    })

    it('profile 与当前默认值都为空时保持 null', () => {
      const { state, domain } = createDomain()
      expect(domain.syncDefaultTeamFromProfile()).toBeNull()
      expect(state.defaultTeamId.value).toBeNull()
    })

    it('profile 缺失时按空处理', () => {
      mockProfileDefaultTeamId(undefined)
      const { state, domain } = createDomain()
      expect(domain.syncDefaultTeamFromProfile()).toBeNull()
      expect(state.defaultTeamId.value).toBeNull()
    })
  })

  describe('resolveTeamScopedParams', () => {
    it('teamId 合法时返回带 teamId 的参数对象', () => {
      const { domain } = createDomain()
      expect(domain.resolveTeamScopedParams(8)).toEqual({ teamId: 8 })
      expect(domain.resolveTeamScopedParams('8')).toEqual({ teamId: 8 })
    })

    it('teamId 非法时返回空对象', () => {
      const { domain } = createDomain()
      expect(domain.resolveTeamScopedParams(0)).toEqual({})
      expect(domain.resolveTeamScopedParams('x')).toEqual({})
      expect(domain.resolveTeamScopedParams(null)).toEqual({})
    })

    it('未传 teamId 时使用当前选中团队', () => {
      const { state, domain } = createDomain()
      expect(domain.resolveTeamScopedParams()).toEqual({})
      state.selectedTeamId.value = 6
      expect(domain.resolveTeamScopedParams()).toEqual({ teamId: 6 })
    })
  })

  describe('clearTeamMembers / clearTeamManagement', () => {
    it('清空成员、禁言与入团申请列表', () => {
      const { state, domain } = createDomain()
      state.teamMembers.value = [{ userId: 1 }]
      state.teamMutes.value = [{ userId: 2 }]
      state.joinRequests.value = [{ id: 3 }]
      domain.clearTeamMembers()
      domain.clearTeamManagement()
      expect(state.teamMembers.value).toEqual([])
      expect(state.teamMutes.value).toEqual([])
      expect(state.joinRequests.value).toEqual([])
    })
  })

  describe('loadTeams', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      mockProfileDefaultTeamId(null)
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [{ userId: 1, name: 'u1' }] })
    })
    afterEach(restoreProfileSpy)

    it('返回数据非数组时清空团队并重置选中状态', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: null })
      state.selectedTeamId.value = 3
      state.teamMembers.value = [{ userId: 9 }]
      const result = await domain.loadTeams()
      expect(result).toEqual([])
      expect(state.teams.value).toEqual([])
      expect(state.selectedTeamId.value).toBeNull()
      expect(state.teamMembers.value).toEqual([])
      expect(fetchTeamMembers).not.toHaveBeenCalled()
    })

    it('过滤掉无合法 id 的团队并做归一化', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({
        data: [{ id: 0, name: 'bad' }, null, { id: 4, name: 'T4', myRole: 'OWNER' }],
      })
      await domain.loadTeams()
      expect(state.teams.value).toHaveLength(1)
      expect(state.teams.value[0]).toMatchObject({ id: 4, name: 'T4', myRoleCode: 'OWNER', myPermissions: [] })
    })

    it('恰好 1 个团队时自动选中该团队并加载成员', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 7, name: 'T7' }] })
      await domain.loadTeams()
      expect(state.selectedTeamId.value).toBe(7)
      expect(fetchTeamMembers).toHaveBeenCalledWith(7)
      expect(state.teamMembers.value).toEqual([
        { userId: 1, username: '', name: 'u1', avatar: '', roleCode: '', joinTime: null },
      ])
    })

    it('有默认团队且存在时选中默认团队', async () => {
      mockProfileDefaultTeamId(2)
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 1 }, { id: 2, name: 'T2' }] })
      await domain.loadTeams()
      expect(state.defaultTeamId.value).toBe(2)
      expect(state.selectedTeamId.value).toBe(2)
      expect(fetchTeamMembers).toHaveBeenCalledWith(2)
    })

    it('当前团队与默认团队都不存在时清空选中并重置默认团队', async () => {
      mockProfileDefaultTeamId(9)
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 1 }, { id: 2 }] })
      state.selectedTeamId.value = 5
      state.teamMembers.value = [{ userId: 9 }]
      state.teamMutes.value = [{ userId: 9 }]
      state.joinRequests.value = [{ id: 9 }]
      await domain.loadTeams()
      expect(state.selectedTeamId.value).toBeNull()
      expect(state.defaultTeamId.value).toBeNull()
      expect(state.teamMembers.value).toEqual([])
      expect(state.teamMutes.value).toEqual([])
      expect(state.joinRequests.value).toEqual([])
      expect(fetchTeamMembers).not.toHaveBeenCalled()
    })

    it('当前团队仍存在时保持不变且不加载默认团队', async () => {
      mockProfileDefaultTeamId(2)
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 1, name: 'T1' }, { id: 2 }] })
      state.selectedTeamId.value = 1
      await domain.loadTeams()
      expect(state.selectedTeamId.value).toBe(1)
      expect(fetchTeamMembers).toHaveBeenCalledWith(1)
    })

    it('返回团队列表供调用方使用', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 11 }] })
      const result = await domain.loadTeams()
      expect(result).toBe(state.teams.value)
    })
  })

  describe('loadTeamMembers', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('teamId 非法时清空成员并返回空数组', async () => {
      const { state, domain } = createDomain()
      state.teamMembers.value = [{ userId: 1 }]
      await expect(domain.loadTeamMembers(0)).resolves.toEqual([])
      expect(state.teamMembers.value).toEqual([])
      expect(fetchTeamMembers).not.toHaveBeenCalled()
    })

    it('加载并归一化成员，同时同步选中团队', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamMembers).mockResolvedValue({
        data: [{ userId: 5, name: 'n5', role: 'ADMIN' }],
      })
      const result = await domain.loadTeamMembers(5)
      expect(fetchTeamMembers).toHaveBeenCalledWith(5)
      expect(state.selectedTeamId.value).toBe(5)
      expect(result).toEqual([
        { userId: 5, username: '', name: 'n5', avatar: '', roleCode: 'ADMIN', joinTime: null },
      ])
      expect(result).toBe(state.teamMembers.value)
    })

    it('返回数据非数组时成员为空数组', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: null })
      await domain.loadTeamMembers(2)
      expect(state.teamMembers.value).toEqual([])
    })

    it('未传 teamId 时使用当前选中团队', async () => {
      const { state, domain } = createDomain()
      state.selectedTeamId.value = 4
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
      await domain.loadTeamMembers()
      expect(fetchTeamMembers).toHaveBeenCalledWith(4)
    })
  })

  describe('loadTeamMembersSafe', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('teamId 非法时清空成员并返回空数组', async () => {
      const { state, domain } = createDomain()
      state.teamMembers.value = [{ userId: 1 }]
      await expect(domain.loadTeamMembersSafe('bad')).resolves.toEqual([])
      expect(state.teamMembers.value).toEqual([])
      expect(fetchTeamMembers).not.toHaveBeenCalled()
    })

    it('正常时委托给 loadTeamMembers', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [{ userId: 3 }] })
      const result = await domain.loadTeamMembersSafe(3)
      expect(fetchTeamMembers).toHaveBeenCalledWith(3)
      expect(state.teamMembers.value).toHaveLength(1)
      expect(result).toBe(state.teamMembers.value)
    })

    it('请求失败时吞掉异常并清空成员', async () => {
      const { state, domain } = createDomain()
      state.teamMembers.value = [{ userId: 1 }]
      vi.mocked(fetchTeamMembers).mockRejectedValue(new Error('boom'))
      await expect(domain.loadTeamMembersSafe(6)).resolves.toEqual([])
      expect(state.teamMembers.value).toEqual([])
    })
  })

  describe('loadTeamManagement', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('teamId 非法时清空管理态数据且不请求接口', async () => {
      const { state, domain } = createDomain()
      state.teamMutes.value = [{ userId: 1 }]
      state.joinRequests.value = [{ id: 1 }]
      await expect(domain.loadTeamManagement(null)).resolves.toBeUndefined()
      expect(state.teamMutes.value).toEqual([])
      expect(state.joinRequests.value).toEqual([])
      expect(fetchTeamMutes).not.toHaveBeenCalled()
      expect(fetchTeamJoinRequests).not.toHaveBeenCalled()
    })

    it('并行拉取禁言列表与入团申请', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [{ userId: 2 }] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [{ id: 3 }] })
      await domain.loadTeamManagement(8)
      expect(fetchTeamMutes).toHaveBeenCalledWith(8)
      expect(fetchTeamJoinRequests).toHaveBeenCalledWith(8)
      expect(state.teamMutes.value).toEqual([{ userId: 2 }])
      expect(state.joinRequests.value).toEqual([{ id: 3 }])
    })

    it('接口返回非数组时回落到空数组', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: null })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({})
      await domain.loadTeamManagement(8)
      expect(state.teamMutes.value).toEqual([])
      expect(state.joinRequests.value).toEqual([])
    })

    it('未传 teamId 时使用当前选中团队', async () => {
      const { state, domain } = createDomain()
      state.selectedTeamId.value = 10
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [] })
      await domain.loadTeamManagement()
      expect(fetchTeamMutes).toHaveBeenCalledWith(10)
    })
  })

  describe('createNewTeam', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
    })

    it('创建成功后选中新团队并加载其成员', async () => {
      const { state, deps, domain } = createDomain()
      vi.mocked(createAdminTeam).mockResolvedValue({ data: { id: 21, name: 'new' } })
      const result = await domain.createNewTeam({ name: 'new' })
      expect(createAdminTeam).toHaveBeenCalledWith({ name: 'new' })
      expect(fetchMyTeams).toHaveBeenCalled()
      expect(deps.loadConversations).toHaveBeenCalled()
      expect(state.selectedTeamId.value).toBe(21)
      expect(fetchTeamMembers).toHaveBeenCalledWith(21)
      expect(result).toEqual({ id: 21, name: 'new' })
    })

    it('未返回新团队 id 时按当前选中团队加载成员', async () => {
      const { state, domain } = createDomain()
      vi.mocked(createAdminTeam).mockResolvedValue({ data: null })
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [{ id: 4 }] })
      state.selectedTeamId.value = 4
      const result = await domain.createNewTeam({ name: 'x' })
      expect(result).toBeNull()
      expect(state.selectedTeamId.value).toBe(4)
      expect(fetchTeamMembers).toHaveBeenCalledWith(4)
    })

    it('未返回 id 且没有选中团队时不加载成员', async () => {
      const { state, domain } = createDomain()
      vi.mocked(createAdminTeam).mockResolvedValue({ data: {} })
      await domain.createNewTeam({ name: 'x' })
      expect(state.selectedTeamId.value).toBeNull()
      expect(fetchTeamMembers).not.toHaveBeenCalled()
    })
  })

  describe('updateSelectedTeam', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('未选中团队时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.updateSelectedTeam({ name: 'x' })).rejects.toThrow('请先选择团队')
      expect(updateTeam).not.toHaveBeenCalled()
    })

    it('团队已在列表中时原地合并更新', async () => {
      const { state, domain } = createDomain()
      state.teams.value = [
        { id: 1, name: 'old', description: 'keep', myPermissions: ['upload'] },
        { id: 2, name: 'B' },
      ]
      state.selectedTeamId.value = 1
      vi.mocked(updateTeam).mockResolvedValue({
        data: { id: 1, name: 'new', myPermissions: ['manage'] },
      })
      const result = await domain.updateSelectedTeam({ name: 'new' })
      expect(updateTeam).toHaveBeenCalledWith(1, { name: 'new' })
      expect(state.teams.value).toHaveLength(2)
      expect(state.teams.value[0]).toMatchObject({ id: 1, name: 'new', myPermissions: ['manage'] })
      expect(state.teams.value[1]).toEqual({ id: 2, name: 'B' })
      expect(result).toEqual({ id: 1, name: 'new', myPermissions: ['manage'] })
    })

    it('团队不在列表中时保持列表不变', async () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 2, name: 'B' }]
      state.selectedTeamId.value = 1
      vi.mocked(updateTeam).mockResolvedValue({ data: { id: 99, name: 'C' } })
      const result = await domain.updateSelectedTeam({ name: 'C' })
      expect(state.teams.value).toEqual([{ id: 2, name: 'B' }])
      expect(result).toEqual({ id: 99, name: 'C' })
    })

    it('接口无返回数据时返回 null 且不改动列表', async () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1, name: 'A' }]
      state.selectedTeamId.value = 1
      vi.mocked(updateTeam).mockResolvedValue({ data: null })
      await expect(domain.updateSelectedTeam({ name: 'x' })).resolves.toBeNull()
      expect(state.teams.value).toEqual([{ id: 1, name: 'A' }])
    })
  })

  describe('searchUsers', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('写入搜索结果并返回', async () => {
      const { state, domain } = createDomain()
      vi.mocked(searchTeamInviteCandidates).mockResolvedValue({ data: [{ userId: 1 }] })
      const result = await domain.searchUsers('ab')
      expect(searchTeamInviteCandidates).toHaveBeenCalledWith('ab')
      expect(state.userSearchResults.value).toEqual([{ userId: 1 }])
      expect(result).toBe(state.userSearchResults.value)
    })

    it('返回非数组时回落到空数组', async () => {
      const { state, domain } = createDomain()
      vi.mocked(searchTeamInviteCandidates).mockResolvedValue({ data: null })
      await expect(domain.searchUsers('')).resolves.toEqual([])
      expect(state.userSearchResults.value).toEqual([])
    })
  })

  describe('inviteUser', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('邀请成功并返回接口数据', async () => {
      const { domain } = createDomain()
      vi.mocked(inviteTeamUser).mockResolvedValue({ data: { success: true } })
      await expect(domain.inviteUser(3, 7)).resolves.toEqual({ success: true })
      expect(inviteTeamUser).toHaveBeenCalledWith(3, 7)
    })

    it('teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.inviteUser(null, 7)).rejects.toThrow('请先选择团队')
      expect(inviteTeamUser).not.toHaveBeenCalled()
    })
  })

  describe('leaveSelectedTeam', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
    })

    it('退出后清空选中团队、会话并重载数据', async () => {
      const { state, deps, domain } = createDomain()
      state.selectedTeamId.value = 5
      vi.mocked(leaveTeam).mockResolvedValue({ data: true })
      await domain.leaveSelectedTeam()
      expect(leaveTeam).toHaveBeenCalledWith(5)
      expect(state.selectedTeamId.value).toBeNull()
      expect(deps.clearActiveConversation).toHaveBeenCalled()
      expect(fetchMyTeams).toHaveBeenCalled()
      expect(deps.loadConversations).toHaveBeenCalled()
    })

    it('teamId 非法时抛错且不调用退出接口', async () => {
      const { deps, domain } = createDomain()
      await expect(domain.leaveSelectedTeam(0)).rejects.toThrow('请先选择团队')
      expect(leaveTeam).not.toHaveBeenCalled()
      expect(deps.clearActiveConversation).not.toHaveBeenCalled()
    })
  })

  describe('removeMember', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [] })
    })

    it('移除成员后重载成员、管理态与会话', async () => {
      const { deps, domain } = createDomain()
      vi.mocked(removeTeamMember).mockResolvedValue({ data: true })
      await domain.removeMember(3, 7)
      expect(removeTeamMember).toHaveBeenCalledWith(3, 7)
      expect(fetchTeamMembers).toHaveBeenCalledWith(3)
      expect(fetchTeamMutes).toHaveBeenCalledWith(3)
      expect(fetchTeamJoinRequests).toHaveBeenCalledWith(3)
      expect(deps.loadConversations).toHaveBeenCalled()
    })

    it('teamId 非法时抛错且不调用移除接口', async () => {
      const { domain } = createDomain()
      await expect(domain.removeMember('x', 7)).rejects.toThrow('请先选择团队')
      expect(removeTeamMember).not.toHaveBeenCalled()
    })
  })

  describe('acceptInvitation / rejectInvitation', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
    })

    it('接受邀请后重载通知、团队与会话', async () => {
      const { deps, domain } = createDomain()
      vi.mocked(acceptTeamInvitation).mockResolvedValue({ data: { teamId: 1 } })
      await expect(domain.acceptInvitation(11)).resolves.toEqual({ teamId: 1 })
      expect(acceptTeamInvitation).toHaveBeenCalledWith(11)
      expect(deps.loadNotifications).toHaveBeenCalled()
      expect(fetchMyTeams).toHaveBeenCalled()
      expect(deps.loadConversations).toHaveBeenCalled()
    })

    it('拒绝邀请后只重载通知', async () => {
      const { deps, domain } = createDomain()
      vi.mocked(rejectTeamInvitation).mockResolvedValue({ data: { ok: true } })
      await expect(domain.rejectInvitation(12)).resolves.toEqual({ ok: true })
      expect(rejectTeamInvitation).toHaveBeenCalledWith(12)
      expect(deps.loadNotifications).toHaveBeenCalled()
      expect(fetchMyTeams).not.toHaveBeenCalled()
      expect(deps.loadConversations).not.toHaveBeenCalled()
    })

    it('接口异常时向外抛出', async () => {
      const { domain } = createDomain()
      vi.mocked(acceptTeamInvitation).mockRejectedValue(new Error('fail'))
      await expect(domain.acceptInvitation(11)).rejects.toThrow('fail')
    })
  })

  describe('publishAnnouncement', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('发布公告后重载会话与通知并返回数据', async () => {
      const { deps, domain } = createDomain()
      vi.mocked(publishTeamAnnouncement).mockResolvedValue({ data: { id: 9 } })
      await expect(domain.publishAnnouncement(4, { content: 'hi' })).resolves.toEqual({ id: 9 })
      expect(publishTeamAnnouncement).toHaveBeenCalledWith(4, { content: 'hi' })
      expect(deps.loadConversations).toHaveBeenCalled()
      expect(deps.loadNotifications).toHaveBeenCalled()
    })

    it('teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.publishAnnouncement(null, { content: 'hi' })).rejects.toThrow(
        '请先选择团队',
      )
      expect(publishTeamAnnouncement).not.toHaveBeenCalled()
    })
  })

  describe('muteMember / unmuteMember', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [] })
    })

    it('禁言后重载管理态并返回数据', async () => {
      const { domain } = createDomain()
      vi.mocked(muteTeamMember).mockResolvedValue({ data: { muted: true } })
      await expect(domain.muteMember(2, { userId: 7, minutes: 10 })).resolves.toEqual({ muted: true })
      expect(muteTeamMember).toHaveBeenCalledWith(2, { userId: 7, minutes: 10 })
      expect(fetchTeamMutes).toHaveBeenCalledWith(2)
      expect(fetchTeamJoinRequests).toHaveBeenCalledWith(2)
    })

    it('禁言 teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.muteMember(0, { userId: 7 })).rejects.toThrow('请先选择团队')
      expect(muteTeamMember).not.toHaveBeenCalled()
    })

    it('解除禁言后重载管理态', async () => {
      const { domain } = createDomain()
      vi.mocked(unmuteTeamMember).mockResolvedValue({ data: true })
      await expect(domain.unmuteMember(2, 7)).resolves.toBeUndefined()
      expect(unmuteTeamMember).toHaveBeenCalledWith(2, 7)
      expect(fetchTeamMutes).toHaveBeenCalledWith(2)
    })

    it('解除禁言 teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.unmuteMember(null, 7)).rejects.toThrow('请先选择团队')
      expect(unmuteTeamMember).not.toHaveBeenCalled()
    })
  })

  describe('createInviteLink', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('写入邀请链接并返回', async () => {
      const { state, domain } = createDomain()
      vi.mocked(createTeamInviteLink).mockResolvedValue({ data: { token: 'abc' } })
      await expect(domain.createInviteLink(3)).resolves.toEqual({ token: 'abc' })
      expect(createTeamInviteLink).toHaveBeenCalledWith(3, {})
      expect(state.inviteLink.value).toEqual({ token: 'abc' })
    })

    it('透传自定义 payload', async () => {
      const { state, domain } = createDomain()
      vi.mocked(createTeamInviteLink).mockResolvedValue({ data: { token: 'def' } })
      await domain.createInviteLink(3, { expireHours: 24 })
      expect(createTeamInviteLink).toHaveBeenCalledWith(3, { expireHours: 24 })
      expect(state.inviteLink.value).toEqual({ token: 'def' })
    })

    it('接口无数据时写入 null', async () => {
      const { state, domain } = createDomain()
      vi.mocked(createTeamInviteLink).mockResolvedValue({})
      await expect(domain.createInviteLink(3)).resolves.toBeNull()
      expect(state.inviteLink.value).toBeNull()
    })

    it('teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.createInviteLink('bad')).rejects.toThrow('请先选择团队')
      expect(createTeamInviteLink).not.toHaveBeenCalled()
    })
  })

  describe('submitJoinRequest', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('提交入团申请并返回结果', async () => {
      const { domain } = createDomain()
      vi.mocked(submitTeamJoinRequest).mockResolvedValue({ data: { requestId: 5 } })
      await expect(domain.submitJoinRequest('token-1')).resolves.toEqual({ requestId: 5 })
      expect(submitTeamJoinRequest).toHaveBeenCalledWith('token-1')
    })

    it('接口异常时向外抛出', async () => {
      const { domain } = createDomain()
      vi.mocked(submitTeamJoinRequest).mockRejectedValue(new Error('invalid token'))
      await expect(domain.submitJoinRequest('bad')).rejects.toThrow('invalid token')
    })
  })

  describe('approveJoinRequest', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [] })
    })

    it('未选中团队时只重载团队与会话', async () => {
      const { deps, domain } = createDomain()
      vi.mocked(approveTeamJoinRequest).mockResolvedValue({ data: { ok: true } })
      await expect(domain.approveJoinRequest(6)).resolves.toEqual({ ok: true })
      expect(approveTeamJoinRequest).toHaveBeenCalledWith(6)
      expect(fetchMyTeams).toHaveBeenCalled()
      expect(deps.loadConversations).toHaveBeenCalled()
      expect(fetchTeamMutes).not.toHaveBeenCalled()
    })

    it('已选中团队时额外重载管理态', async () => {
      const { state, domain } = createDomain()
      state.selectedTeamId.value = 3
      vi.mocked(approveTeamJoinRequest).mockResolvedValue({ data: { ok: true } })
      await domain.approveJoinRequest(6)
      expect(fetchTeamMutes).toHaveBeenCalledWith(3)
      expect(fetchTeamJoinRequests).toHaveBeenCalledWith(3)
    })
  })

  describe('rejectJoinRequest', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchTeamMutes).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamJoinRequests).mockResolvedValue({ data: [{ id: 6 }] })
    })

    it('未选中团队时不重载管理态', async () => {
      const { state, domain } = createDomain()
      vi.mocked(rejectTeamJoinRequest).mockResolvedValue({ data: { ok: true } })
      await expect(domain.rejectJoinRequest(6)).resolves.toEqual({ ok: true })
      expect(rejectTeamJoinRequest).toHaveBeenCalledWith(6)
      expect(fetchTeamMutes).not.toHaveBeenCalled()
      expect(state.joinRequests.value).toEqual([])
    })

    it('已选中团队时重载管理态', async () => {
      const { state, domain } = createDomain()
      state.selectedTeamId.value = 2
      vi.mocked(rejectTeamJoinRequest).mockResolvedValue({ data: { ok: true } })
      await domain.rejectJoinRequest(6)
      expect(fetchTeamMutes).toHaveBeenCalledWith(2)
      expect(fetchTeamJoinRequests).toHaveBeenCalledWith(2)
      expect(state.joinRequests.value).toEqual([{ id: 6 }])
    })
  })

  describe('refreshTeamPermissionCenter', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('存在 emitter 时以当前选中团队触发刷新事件', async () => {
      const { state, deps, domain } = createDomain()
      state.selectedTeamId.value = 4
      await domain.refreshTeamPermissionCenter()
      expect(deps.emitter.emit).toHaveBeenCalledWith('permissionCenterNeedsReload', 4)
    })

    it('存在 emitter 时透传指定 teamId', async () => {
      const { deps, domain } = createDomain()
      await domain.refreshTeamPermissionCenter(9)
      expect(deps.emitter.emit).toHaveBeenCalledWith('permissionCenterNeedsReload', 9)
    })

    it('没有 emitter 时静默跳过', async () => {
      const state = {
        teams: ref([]),
        selectedTeamId: ref(null),
        defaultTeamId: ref(null),
        teamMembers: ref([]),
        teamMutes: ref([]),
        joinRequests: ref([]),
        inviteLink: ref(null),
        userSearchResults: ref([]),
      }
      const domain = createTeamDomain(state, {
        clearActiveConversation: vi.fn(),
        loadConversations: vi.fn(() => Promise.resolve([])),
        loadNotifications: vi.fn(() => Promise.resolve([])),
      })
      await expect(domain.refreshTeamPermissionCenter(1)).resolves.toBeUndefined()
    })
  })

  describe('handleTeamAccessRevoked', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    it('teamId 非法时不做任何处理', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1 }, { id: 2 }]
      state.selectedTeamId.value = 1
      domain.handleTeamAccessRevoked(null)
      domain.handleTeamAccessRevoked(0)
      domain.handleTeamAccessRevoked('x')
      expect(state.teams.value).toHaveLength(2)
      expect(state.selectedTeamId.value).toBe(1)
    })

    it('被移除的是当前选中团队时清空选中及团队数据', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1 }, { id: 2 }]
      state.selectedTeamId.value = 2
      state.teamMembers.value = [{ userId: 1 }]
      state.teamMutes.value = [{ userId: 1 }]
      state.joinRequests.value = [{ id: 1 }]
      domain.handleTeamAccessRevoked(2)
      expect(state.teams.value).toEqual([{ id: 1 }])
      expect(state.selectedTeamId.value).toBeNull()
      expect(state.teamMembers.value).toEqual([])
      expect(state.teamMutes.value).toEqual([])
      expect(state.joinRequests.value).toEqual([])
    })

    it('被移除的不是当前选中团队时只过滤列表', () => {
      const { state, domain } = createDomain()
      state.teams.value = [{ id: 1 }, { id: 2 }]
      state.selectedTeamId.value = 1
      state.teamMembers.value = [{ userId: 1 }]
      domain.handleTeamAccessRevoked('2')
      expect(state.teams.value).toEqual([{ id: 1 }])
      expect(state.selectedTeamId.value).toBe(1)
      expect(state.teamMembers.value).toEqual([{ userId: 1 }])
    })
  })

  describe('默认 deps 兜底', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      vi.mocked(fetchMyTeams).mockResolvedValue({ data: [] })
      vi.mocked(fetchTeamMembers).mockResolvedValue({ data: [] })
    })

    it('未传入 deps 时使用内置空实现且不报错', async () => {
      const state = {
        teams: ref([]),
        selectedTeamId: ref(null),
        defaultTeamId: ref(null),
        teamMembers: ref([]),
        teamMutes: ref([]),
        joinRequests: ref([]),
        inviteLink: ref(null),
        userSearchResults: ref([]),
      }
      const domain = createTeamDomain(state)
      vi.mocked(acceptTeamInvitation).mockResolvedValue({ data: null })
      await expect(domain.acceptInvitation(1)).resolves.toBeNull()
      expect(fetchMyTeams).toHaveBeenCalled()

      state.selectedTeamId.value = 1
      vi.mocked(leaveTeam).mockResolvedValue({ data: true })
      await expect(domain.leaveSelectedTeam()).resolves.toBeUndefined()
      expect(leaveTeam).toHaveBeenCalledWith(1)
      expect(state.selectedTeamId.value).toBeNull()
    })
  })
})
