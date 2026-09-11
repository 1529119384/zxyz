// @ts-check
import request from '@/utils/request'

export const fetchSystemPermissions = () => request.get('/api/permissions')

export const fetchSystemRoles = () => request.get('/api/permissions/roles')

/**
 * @param {Record<string, unknown>} payload - 角色创建请求体
 */
export const createSystemRole = (payload) => request.post('/api/permissions/roles', payload)

/**
 * @param {string|number} roleId - 角色 ID
 * @param {Record<string, unknown>} payload - 角色更新请求体
 */
export const updateSystemRole = (roleId, payload) =>
  request.patch(`/api/permissions/roles/${roleId}`, payload)

/**
 * @param {string|number} roleId - 角色 ID
 */
export const deleteSystemRole = (roleId) => request.delete(`/api/permissions/roles/${roleId}`)

/**
 * @param {string|number} roleId - 角色 ID
 * @param {Record<string, unknown>} payload - 权限分配请求体
 */
export const assignSystemRolePermissions = (roleId, payload) =>
  request.post(`/api/permissions/roles/${roleId}/permissions`, payload)

/**
 * @param {string|number} userId - 用户 ID
 * @param {Record<string, unknown>} payload - 角色分配请求体
 */
export const assignUserRole = (userId, payload) =>
  request.post(`/api/permissions/users/${userId}/roles`, payload)

/**
 * @param {Record<string, unknown>} [params] - 查询条件
 */
export const fetchSystemPermissionAudit = (params = {}) =>
  request.get('/api/permissions/audit', { params })
