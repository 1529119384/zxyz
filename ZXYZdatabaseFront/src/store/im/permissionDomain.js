import {
  assignTeamMemberRole,
  assignTeamRolePermissions,
  createTeamRole,
  deleteTeamRole,
  fetchTeamPermissionAudit,
  fetchTeamPermissions,
  fetchTeamRoles,
  updateTeamRole,
} from '@/api/teamIm'
import { normalizePositiveId } from '@/utils/id'

import { requireTeamId } from './normalizers'

export function createPermissionDomain(state, deps) {
  const { teamPermissions, teamRoles, teamPermissionAudit } = state
  const { emitter } = deps

  function clearTeamPermissionCenter() {
    teamPermissions.value = []
    teamRoles.value = []
    teamPermissionAudit.value = []
  }

  function toResponseDataList(response) {
    return Array.isArray(response?.data) ? response.data : []
  }

  async function loadTeamPermissionCenter(teamId, options = {}) {
    const { includePermissionCenter = true, includeAudit = true, throwOnFailure = true } = options
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      clearTeamPermissionCenter()
      return {
        permissions: teamPermissions.value,
        roles: teamRoles.value,
        audit: teamPermissionAudit.value,
      }
    }
    const tasks = [
      includePermissionCenter
        ? fetchTeamPermissions(normalizedTeamId)
        : Promise.resolve({ data: [] }),
      includePermissionCenter ? fetchTeamRoles(normalizedTeamId) : Promise.resolve({ data: [] }),
      includeAudit ? fetchTeamPermissionAudit(normalizedTeamId) : Promise.resolve({ data: [] }),
    ]

    if (throwOnFailure) {
      const [permissionsResponse, rolesResponse, auditResponse] = await Promise.all(tasks)
      teamPermissions.value = toResponseDataList(permissionsResponse)
      teamRoles.value = toResponseDataList(rolesResponse)
      teamPermissionAudit.value = toResponseDataList(auditResponse)
      return {
        permissions: teamPermissions.value,
        roles: teamRoles.value,
        audit: teamPermissionAudit.value,
      }
    }

    const [permissionsResult, rolesResult, auditResult] = await Promise.allSettled(tasks)
    // 权限页允许局部接口失败，失败分区在 store 内置空，页面只负责触发加载。
    teamPermissions.value =
      permissionsResult.status === 'fulfilled' ? toResponseDataList(permissionsResult.value) : []
    teamRoles.value =
      rolesResult.status === 'fulfilled' ? toResponseDataList(rolesResult.value) : []
    teamPermissionAudit.value =
      auditResult.status === 'fulfilled' ? toResponseDataList(auditResult.value) : []
    return {
      permissions: teamPermissions.value,
      roles: teamRoles.value,
      audit: teamPermissionAudit.value,
    }
  }

  async function saveTeamRole(teamId, payload, roleId = null) {
    const normalizedTeamId = requireTeamId(teamId)
    const response = roleId
      ? await updateTeamRole(normalizedTeamId, roleId, payload)
      : await createTeamRole(normalizedTeamId, payload)
    await loadTeamPermissionCenter(normalizedTeamId)
    return response?.data
  }

  async function removeTeamRole(teamId, roleId) {
    const normalizedTeamId = requireTeamId(teamId)
    await deleteTeamRole(normalizedTeamId, roleId)
    await loadTeamPermissionCenter(normalizedTeamId)
  }

  async function updateTeamRolePermissions(teamId, roleId, permissionCodes = []) {
    const normalizedTeamId = requireTeamId(teamId)
    await assignTeamRolePermissions(normalizedTeamId, roleId, { permissionCodes })
    await loadTeamPermissionCenter(normalizedTeamId)
  }

  async function updateTeamMemberRole(teamId, userId, roleCode) {
    const normalizedTeamId = requireTeamId(teamId)
    await assignTeamMemberRole(normalizedTeamId, { userId, roleCode })
    await loadTeamPermissionCenter(normalizedTeamId)
    // 通过事件通知团队域刷新，避免直接调用 → 打破循环依赖。
    if (emitter) {
      emitter.emit('teamMembersNeedReload', normalizedTeamId)
      emitter.emit('teamsNeedReload')
    }
  }

  return {
    clearTeamPermissionCenter,
    loadTeamPermissionCenter,
    saveTeamRole,
    removeTeamRole,
    updateTeamRolePermissions,
    updateTeamMemberRole,
  }
}
