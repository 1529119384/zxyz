import request from '@/utils/request'

export const fetchSystemPermissions = () => request.get('/api/permissions')

export const fetchSystemRoles = () => request.get('/api/permissions/roles')

export const createSystemRole = (payload) => request.post('/api/permissions/roles', payload)

export const updateSystemRole = (roleId, payload) =>
  request.patch(`/api/permissions/roles/${roleId}`, payload)

export const deleteSystemRole = (roleId) => request.delete(`/api/permissions/roles/${roleId}`)

export const assignSystemRolePermissions = (roleId, payload) =>
  request.post(`/api/permissions/roles/${roleId}/permissions`, payload)

export const assignUserRole = (userId, payload) =>
  request.post(`/api/permissions/users/${userId}/roles`, payload)

export const fetchSystemPermissionAudit = (params = {}) =>
  request.get('/api/permissions/audit', { params })
