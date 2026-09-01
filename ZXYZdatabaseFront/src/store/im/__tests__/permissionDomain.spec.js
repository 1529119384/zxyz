import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

import { createPermissionDomain } from '@/store/im/permissionDomain'

vi.mock('@/api/teamIm', () => ({
  assignTeamMemberRole: vi.fn(),
  assignTeamRolePermissions: vi.fn(),
  createTeamRole: vi.fn(),
  deleteTeamRole: vi.fn(),
  fetchTeamPermissionAudit: vi.fn(),
  fetchTeamPermissions: vi.fn(),
  fetchTeamRoles: vi.fn(),
  updateTeamRole: vi.fn(),
}))

function createDomain() {
  const state = {
    teamPermissions: ref([]),
    teamRoles: ref([]),
    teamPermissionAudit: ref([]),
  }
  const deps = { emitter: { emit: vi.fn() } }
  const domain = createPermissionDomain(state, deps)
  return { state, deps, domain }
}

describe('permissionDomain', () => {
  describe('loadTeamPermissionCenter', () => {
    it('throwOnFailure=false 时单个接口失败不影响其它分区', async () => {
      const { state, domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockRejectedValue(new Error('permissions 接口挂了'))
      fetchTeamRoles.mockResolvedValue({ data: [{ id: 'role-1', name: '管理员' }] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [{ id: 'audit-1' }] })

      const result = await domain.loadTeamPermissionCenter(7, { throwOnFailure: false })

      expect(result.permissions).toEqual([])
      expect(result.roles).toEqual([{ id: 'role-1', name: '管理员' }])
      expect(result.audit).toEqual([{ id: 'audit-1' }])
      expect(state.teamPermissions.value).toEqual([])
      expect(state.teamRoles.value).toEqual([{ id: 'role-1', name: '管理员' }])
      expect(state.teamPermissionAudit.value).toEqual([{ id: 'audit-1' }])
    })

    it('throwOnFailure=false 全部成功时正常写入', async () => {
      const { state, domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: ['p1'] })
      fetchTeamRoles.mockResolvedValue({ data: ['r1'] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: ['a1'] })

      const result = await domain.loadTeamPermissionCenter(7, { throwOnFailure: false })
      expect(result).toEqual({
        permissions: ['p1'],
        roles: ['r1'],
        audit: ['a1'],
      })
    })

    it('接口返回空 data 时分区置空数组而非崩溃', async () => {
      const { state, domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: null })
      fetchTeamRoles.mockResolvedValue({ data: undefined })
      fetchTeamPermissionAudit.mockResolvedValue({})

      const result = await domain.loadTeamPermissionCenter(7, { throwOnFailure: false })
      expect(result.permissions).toEqual([])
      expect(result.roles).toEqual([])
      expect(result.audit).toEqual([])
    })

    it('throwOnFailure 默认 true 时接口失败整体抛出', async () => {
      const { state, domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: ['p1'] })
      fetchTeamRoles.mockRejectedValue(new Error('roles 挂了'))
      fetchTeamPermissionAudit.mockResolvedValue({ data: ['a1'] })

      await expect(domain.loadTeamPermissionCenter(7)).rejects.toThrow('roles 挂了')
      // 整体失败不写入任何分区
      expect(state.teamPermissions.value).toEqual([])
      expect(state.teamRoles.value).toEqual([])
      expect(state.teamPermissionAudit.value).toEqual([])
    })

    it('teamId 非法时清空权限中心并返回空', async () => {
      const { state, domain } = createDomain()
      state.teamPermissions.value = ['stale']
      state.teamRoles.value = ['stale']
      state.teamPermissionAudit.value = ['stale']
      const result = await domain.loadTeamPermissionCenter(null)
      expect(result).toEqual({ permissions: [], roles: [], audit: [] })
      expect(state.teamPermissions.value).toEqual([])
      expect(state.teamRoles.value).toEqual([])
      expect(state.teamPermissionAudit.value).toEqual([])
    })
  })
})
