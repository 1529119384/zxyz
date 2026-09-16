// @ts-check

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

/**
 * 权限域的 state 切片（由 `store/team.js` 组装后传入）。
 * @typedef {object} PermissionDomainState
 * @property {import('vue').Ref<any[]>} teamPermissions 团队可用权限字典
 * @property {import('vue').Ref<any[]>} teamRoles 团队角色列表
 * @property {import('vue').Ref<any[]>} teamPermissionAudit 团队权限审计记录
 */

/**
 * 域间事件总线（`@/utils/eventEmitter` 的产物）。
 * 事件名是无类型字符串，这里不为它造一层伪类型，只约束「能被调用」的最小形状。
 * @typedef {{ emit: (event: string, ...args: any[]) => void }} DomainEmitter
 */

/**
 * 权限域的依赖（由 `store/team.js` 延迟绑定注入）。
 * @typedef {object} PermissionDomainDeps
 * @property {DomainEmitter} [emitter] 用于通知 teamDomain 刷新；省略时跳过事件通知（单测可直接传 `{}`）
 */

/**
 * `loadTeamPermissionCenter` 的分区开关。
 * @typedef {object} LoadPermissionCenterOptions
 * @property {boolean} [includePermissionCenter] 是否拉取权限字典与角色
 * @property {boolean} [includeAudit] 是否拉取审计
 * @property {boolean} [throwOnFailure] `true` 任一接口失败即抛；`false` 失败分区兜底为空
 */

/**
 * @param {PermissionDomainState} state
 * @param {PermissionDomainDeps} deps
 */
export function createPermissionDomain(state, deps) {
  const { teamPermissions, teamRoles, teamPermissionAudit } = state
  const { emitter } = deps

  function clearTeamPermissionCenter() {
    teamPermissions.value = []
    teamRoles.value = []
    teamPermissionAudit.value = []
  }

  /** @param {any} response */
  function toResponseDataList(response) {
    return Array.isArray(response?.data) ? response.data : []
  }

  /**
   * @param {any} teamId
   * @param {LoadPermissionCenterOptions} [options]
   */
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

  /**
   * @param {any} teamId
   * @param {any} payload
   * @param {any} [roleId]
   */
  async function saveTeamRole(teamId, payload, roleId = null) {
    const normalizedTeamId = requireTeamId(teamId)
    const response = roleId
      ? await updateTeamRole(normalizedTeamId, roleId, payload)
      : await createTeamRole(normalizedTeamId, payload)
    await loadTeamPermissionCenter(normalizedTeamId)
    return response?.data
  }

  /**
   * @param {any} teamId
   * @param {any} roleId
   */
  async function removeTeamRole(teamId, roleId) {
    const normalizedTeamId = requireTeamId(teamId)
    await deleteTeamRole(normalizedTeamId, roleId)
    await loadTeamPermissionCenter(normalizedTeamId)
  }

  /**
   * @param {any} teamId
   * @param {any} roleId
   * @param {any[]} [permissionCodes]
   */
  async function updateTeamRolePermissions(teamId, roleId, permissionCodes = []) {
    const normalizedTeamId = requireTeamId(teamId)
    await assignTeamRolePermissions(normalizedTeamId, roleId, { permissionCodes })
    await loadTeamPermissionCenter(normalizedTeamId)
  }

  /**
   * @param {any} teamId
   * @param {any} userId
   * @param {any} roleCode
   */
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
