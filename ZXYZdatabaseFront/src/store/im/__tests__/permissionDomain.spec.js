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

    it('throwOnFailure=true 全部成功时写入并回传三个分区', async () => {
      const { state, domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: ['p1', 'p2'] })
      fetchTeamRoles.mockResolvedValue({ data: [{ id: 1 }] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: ['a1'] })

      const result = await domain.loadTeamPermissionCenter(7, { throwOnFailure: true })
      expect(result).toEqual({
        permissions: ['p1', 'p2'],
        roles: [{ id: 1 }],
        audit: ['a1'],
      })
      expect(state.teamPermissions.value).toEqual(['p1', 'p2'])
      expect(state.teamRoles.value).toEqual([{ id: 1 }])
      expect(state.teamPermissionAudit.value).toEqual(['a1'])
    })

    it('includePermissionCenter=false 时不请求权限与角色接口', async () => {
      const { domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: ['p1'] })
      fetchTeamRoles.mockResolvedValue({ data: ['r1'] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: ['a1'] })

      const result = await domain.loadTeamPermissionCenter(7, {
        includePermissionCenter: false,
        throwOnFailure: false,
      })
      expect(fetchTeamPermissions).not.toHaveBeenCalled()
      expect(fetchTeamRoles).not.toHaveBeenCalled()
      expect(fetchTeamPermissionAudit).toHaveBeenCalledWith(7)
      expect(result.permissions).toEqual([])
      expect(result.roles).toEqual([])
      expect(result.audit).toEqual(['a1'])
    })

    it('includeAudit=false 时不请求审计接口', async () => {
      const { domain } = createDomain()
      const { fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      fetchTeamPermissions.mockResolvedValue({ data: ['p1'] })
      fetchTeamRoles.mockResolvedValue({ data: ['r1'] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: ['a1'] })

      const result = await domain.loadTeamPermissionCenter(7, {
        includeAudit: false,
        throwOnFailure: false,
      })
      expect(fetchTeamPermissionAudit).not.toHaveBeenCalled()
      expect(result.permissions).toEqual(['p1'])
      expect(result.audit).toEqual([])
    })
  })

  describe('saveTeamRole', () => {
    it('有 roleId 时走 updateTeamRole 并回传 data', async () => {
      const { domain } = createDomain()
      const { updateTeamRole, createTeamRole, fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      updateTeamRole.mockResolvedValue({ data: { id: 5, name: '改后' } })
      createTeamRole.mockResolvedValue({ data: { id: 9 } })
      fetchTeamPermissions.mockResolvedValue({ data: [] })
      fetchTeamRoles.mockResolvedValue({ data: [] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [] })

      const result = await domain.saveTeamRole(7, { name: '改后' }, 5)
      expect(updateTeamRole).toHaveBeenCalledWith(7, 5, { name: '改后' })
      expect(createTeamRole).not.toHaveBeenCalled()
      expect(result).toEqual({ id: 5, name: '改后' })
    })

    it('无 roleId 时走 createTeamRole 并回传 data', async () => {
      const { domain } = createDomain()
      const { updateTeamRole, createTeamRole, fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      updateTeamRole.mockResolvedValue({ data: { id: 5 } })
      createTeamRole.mockResolvedValue({ data: { id: 9, name: '新角色' } })
      fetchTeamPermissions.mockResolvedValue({ data: [] })
      fetchTeamRoles.mockResolvedValue({ data: [] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [] })

      const result = await domain.saveTeamRole(7, { name: '新角色' })
      expect(createTeamRole).toHaveBeenCalledWith(7, { name: '新角色' })
      expect(updateTeamRole).not.toHaveBeenCalled()
      expect(result).toEqual({ id: 9, name: '新角色' })
    })

    it('teamId 非法时抛错', async () => {
      const { domain } = createDomain()
      await expect(domain.saveTeamRole(null, {})).rejects.toThrow()
    })
  })

  describe('removeTeamRole', () => {
    it('调用 deleteTeamRole 后刷新权限中心', async () => {
      const { domain } = createDomain()
      const {
        deleteTeamRole,
        updateTeamRole,
        fetchTeamPermissions,
        fetchTeamRoles,
        fetchTeamPermissionAudit,
      } = await import('@/api/teamIm')
      deleteTeamRole.mockResolvedValue({})
      fetchTeamPermissions.mockResolvedValue({ data: ['p1'] })
      fetchTeamRoles.mockResolvedValue({ data: [] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [] })

      await domain.removeTeamRole(7, 5)
      expect(deleteTeamRole).toHaveBeenCalledWith(7, 5)
      // 刷新后权限分区已重新拉取
      expect(fetchTeamPermissions).toHaveBeenCalledWith(7)
    })
  })

  describe('updateTeamRolePermissions', () => {
    it('传 permissionCodes 并刷新权限中心', async () => {
      const { domain } = createDomain()
      const { assignTeamRolePermissions, fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      assignTeamRolePermissions.mockResolvedValue({})
      fetchTeamPermissions.mockResolvedValue({ data: [] })
      fetchTeamRoles.mockResolvedValue({ data: [] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [] })

      await domain.updateTeamRolePermissions(7, 5, ['file:read', 'file:write'])
      expect(assignTeamRolePermissions).toHaveBeenCalledWith(7, 5, {
        permissionCodes: ['file:read', 'file:write'],
      })
      expect(fetchTeamRoles).toHaveBeenCalledWith(7)
    })

    it('permissionCodes 省略时默认为空数组', async () => {
      const { domain } = createDomain()
      const { assignTeamRolePermissions, fetchTeamPermissions, fetchTeamRoles, fetchTeamPermissionAudit } =
        await import('@/api/teamIm')
      assignTeamRolePermissions.mockResolvedValue({})
      fetchTeamPermissions.mockResolvedValue({ data: [] })
      fetchTeamRoles.mockResolvedValue({ data: [] })
      fetchTeamPermissionAudit.mockResolvedValue({ data: [] })

      await domain.updateTeamRolePermissions(7, 5)
      expect(assignTeamRolePermissions).toHaveBeenCalledWith(7, 5, { permissionCodes: [] })
    })
  })

  describe('updateTeamMemberRole', () => {
    async function setupMocks() {
      const api = await import('@/api/teamIm')
      api.assignTeamMemberRole.mockResolvedValue({})
      api.fetchTeamPermissions.mockResolvedValue({ data: [] })
      api.fetchTeamRoles.mockResolvedValue({ data: [] })
      api.fetchTeamPermissionAudit.mockResolvedValue({ data: [] })
      return api
    }

    it('调用接口并触发团队域刷新事件（打破循环依赖）', async () => {
      const { deps, domain } = createDomain()
      const api = await setupMocks()
      await domain.updateTeamMemberRole(7, 42, 'ADMIN')
      expect(api.assignTeamMemberRole).toHaveBeenCalledWith(7, { userId: 42, roleCode: 'ADMIN' })
      expect(deps.emitter.emit).toHaveBeenCalledWith('teamMembersNeedReload', 7)
      expect(deps.emitter.emit).toHaveBeenCalledWith('teamsNeedReload')
    })

    it('无 emitter 时跳过事件通知且不报错', async () => {
      const state = { teamPermissions: ref([]), teamRoles: ref([]), teamPermissionAudit: ref([]) }
      const domain = createPermissionDomain(state, {})
      await setupMocks()
      await expect(
        domain.updateTeamMemberRole(7, 42, 'MEMBER'),
      ).resolves.toBeUndefined()
    })
  })
})
