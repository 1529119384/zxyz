// @ts-check
import request, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

export const fetchMyTeams = () => request.get('/api/teams/my')

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 团队更新请求体
 */
export const updateTeam = (teamId, payload) => request.patch(`/api/teams/${teamId}`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 上传签名请求体
 */
export const getTeamAvatarUploadSign = (teamId, payload) =>
  request.post(`/api/teams/${teamId}/avatar/upload-sign`, payload, {
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamMembers = (teamId) => request.get(`/api/teams/${teamId}/members`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 成员创建请求体
 */
export const createTeamMember = (teamId, payload) =>
  request.post(`/api/teams/${teamId}/members`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} userId - 用户 ID
 * @param {Record<string, unknown>} payload - 状态更新请求体
 */
export const updateTeamMemberStatus = (teamId, userId, payload) =>
  request.patch(`/api/teams/${teamId}/members/${userId}/status`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} userId - 用户 ID
 */
export const removeTeamMember = (teamId, userId) =>
  request.delete(`/api/teams/${teamId}/members/${userId}`)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const leaveTeam = (teamId) => request.post(`/api/teams/${teamId}/leave`)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamMembersStorage = (teamId) =>
  request.get(`/api/teams/${teamId}/members/storage`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} userId - 用户 ID
 * @param {Record<string, unknown>} payload - 容量上限请求体
 */
export const updateMemberStorageLimit = (teamId, userId, payload) =>
  request.patch(`/api/teams/${teamId}/members/${userId}/storage`, payload)
