import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(),
}))

vi.mock('@/store/team', () => ({
  useTeamStore: vi.fn(),
}))

vi.mock('@/constants/teamPermissions', () => ({
  TEAM_PERMISSION_CENTER_CODES: ['team_permission_center'],
}))

import { requireSystemAdminRole, requirePermissionCenter } from '@/router/guards/permission'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'

describe('路由守卫', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('requireSystemAdminRole', () => {
    it('应允许系统管理员访问', () => {
      useCurrentUserStore.mockReturnValue({ isAdmin: true })
      const guard = requireSystemAdminRole()
      expect(guard()).toBe(true)
    })

    it('应拒绝非系统管理员并重定向', () => {
      useCurrentUserStore.mockReturnValue({ isAdmin: false })
      const guard = requireSystemAdminRole()
      const result = guard()
      expect(result).toEqual({ name: 'accountSettings' })
    })
  })

  describe('requirePermissionCenter', () => {
    it('应允许有系统权限中心权限的用户', () => {
      useCurrentUserStore.mockReturnValue({ canReadSystemPermissionCenter: true })
      useTeamStore.mockReturnValue({ selectedTeamId: null, teams: [], hasTeamPermission: vi.fn() })
      const result = requirePermissionCenter({})
      expect(result).toBe(true)
    })

    it('应拒绝无权限的用户并重定向', () => {
      useCurrentUserStore.mockReturnValue({ canReadSystemPermissionCenter: false })
      useTeamStore.mockReturnValue({
        selectedTeamId: null,
        teams: [],
        hasTeamPermission: vi.fn(() => false),
      })
      const result = requirePermissionCenter({})
      expect(result).toEqual({ name: 'accountSettings' })
    })

    it('应允许有团队权限的用户', () => {
      useCurrentUserStore.mockReturnValue({ canReadSystemPermissionCenter: false })
      useTeamStore.mockReturnValue({
        selectedTeamId: 10,
        teams: [],
        hasTeamPermission: vi.fn(() => true),
      })
      const result = requirePermissionCenter({ query: { teamId: 10 } })
      expect(result).toBe(true)
    })
  })
})
