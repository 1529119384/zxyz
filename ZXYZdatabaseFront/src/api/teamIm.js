// @ts-check
import imRequest from '@/utils/imRequest'

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 公告请求体
 */
export const publishTeamAnnouncement = (teamId, payload) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/announcements`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamMutes = (teamId) =>
  imRequest.get(`/api/team-collaboration/teams/${teamId}/mutes`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 禁言请求体
 */
export const muteTeamMember = (teamId, payload) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/mutes`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} userId - 用户 ID
 */
export const unmuteTeamMember = (teamId, userId) =>
  imRequest.delete(`/api/team-collaboration/teams/${teamId}/mutes/${userId}`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} [payload] - 邀请链接请求体
 */
export const createTeamInviteLink = (teamId, payload = {}) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/invite-links`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamJoinRequests = (teamId) =>
  imRequest.get(`/api/team-collaboration/teams/${teamId}/join-requests`)

/**
 * @param {string} token - 邀请令牌
 */
export const submitTeamJoinRequest = (token) =>
  imRequest.post(`/api/team-collaboration/invite-links/${token}/join-requests`)

/**
 * @param {string|number} requestId - 入群申请 ID
 */
export const approveTeamJoinRequest = (requestId) =>
  imRequest.post(`/api/team-collaboration/join-requests/${requestId}/approve`)

/**
 * @param {string|number} requestId - 入群申请 ID
 */
export const rejectTeamJoinRequest = (requestId) =>
  imRequest.post(`/api/team-collaboration/join-requests/${requestId}/reject`)

/**
 * @param {string} keyword - 搜索关键词
 */
export const searchTeamInviteCandidates = (keyword) =>
  imRequest.get('/api/team-collaboration/users/search', {
    params: { keyword },
  })

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} userId - 用户 ID
 */
export const inviteTeamUser = (teamId, userId) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/invitations`, { userId })

/**
 * @param {string|number} invitationId - 邀请 ID
 */
export const acceptTeamInvitation = (invitationId) =>
  imRequest.post(`/api/team-collaboration/team-invitations/${invitationId}/accept`)

/**
 * @param {string|number} invitationId - 邀请 ID
 */
export const rejectTeamInvitation = (invitationId) =>
  imRequest.post(`/api/team-collaboration/team-invitations/${invitationId}/reject`)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamPermissions = (teamId) =>
  imRequest.get(`/api/permissions/teams/${teamId}/permissions`)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamRoles = (teamId) => imRequest.get(`/api/permissions/teams/${teamId}/roles`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 角色创建请求体
 */
export const createTeamRole = (teamId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/roles`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} roleId - 角色 ID
 * @param {Record<string, unknown>} payload - 角色更新请求体
 */
export const updateTeamRole = (teamId, roleId, payload) =>
  imRequest.patch(`/api/permissions/teams/${teamId}/roles/${roleId}`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} roleId - 角色 ID
 */
export const deleteTeamRole = (teamId, roleId) =>
  imRequest.delete(`/api/permissions/teams/${teamId}/roles/${roleId}`)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {string|number} roleId - 角色 ID
 * @param {Record<string, unknown>} payload - 权限分配请求体
 */
export const assignTeamRolePermissions = (teamId, roleId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/roles/${roleId}/permissions`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 成员角色请求体
 */
export const assignTeamMemberRole = (teamId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/member-roles`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamPermissionAudit = (teamId) =>
  imRequest.get(`/api/permissions/teams/${teamId}/audit`)
