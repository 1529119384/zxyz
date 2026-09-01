import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

import { createTeamDomain } from '@/store/im/teamDomain'

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
})
