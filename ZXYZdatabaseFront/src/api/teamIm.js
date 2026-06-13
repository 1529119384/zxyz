import imRequest from '@/utils/imRequest'

export const publishTeamAnnouncement = (teamId, payload) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/announcements`, payload)

export const fetchTeamMutes = (teamId) =>
  imRequest.get(`/api/team-collaboration/teams/${teamId}/mutes`)

export const muteTeamMember = (teamId, payload) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/mutes`, payload)

export const unmuteTeamMember = (teamId, userId) =>
  imRequest.delete(`/api/team-collaboration/teams/${teamId}/mutes/${userId}`)

export const createTeamInviteLink = (teamId, payload = {}) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/invite-links`, payload)

export const fetchTeamJoinRequests = (teamId) =>
  imRequest.get(`/api/team-collaboration/teams/${teamId}/join-requests`)

export const submitTeamJoinRequest = (token) =>
  imRequest.post(`/api/team-collaboration/invite-links/${token}/join-requests`)

export const approveTeamJoinRequest = (requestId) =>
  imRequest.post(`/api/team-collaboration/join-requests/${requestId}/approve`)

export const rejectTeamJoinRequest = (requestId) =>
  imRequest.post(`/api/team-collaboration/join-requests/${requestId}/reject`)

export const searchTeamInviteCandidates = (keyword) =>
  imRequest.get('/api/team-collaboration/users/search', {
    params: { keyword },
  })

export const inviteTeamUser = (teamId, userId) =>
  imRequest.post(`/api/team-collaboration/teams/${teamId}/invitations`, { userId })

export const acceptTeamInvitation = (invitationId) =>
  imRequest.post(`/api/team-collaboration/team-invitations/${invitationId}/accept`)

export const rejectTeamInvitation = (invitationId) =>
  imRequest.post(`/api/team-collaboration/team-invitations/${invitationId}/reject`)

export const fetchTeamPermissions = (teamId) =>
  imRequest.get(`/api/permissions/teams/${teamId}/permissions`)

export const fetchTeamRoles = (teamId) => imRequest.get(`/api/permissions/teams/${teamId}/roles`)

export const createTeamRole = (teamId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/roles`, payload)

export const updateTeamRole = (teamId, roleId, payload) =>
  imRequest.patch(`/api/permissions/teams/${teamId}/roles/${roleId}`, payload)

export const deleteTeamRole = (teamId, roleId) =>
  imRequest.delete(`/api/permissions/teams/${teamId}/roles/${roleId}`)

export const assignTeamRolePermissions = (teamId, roleId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/roles/${roleId}/permissions`, payload)

export const assignTeamMemberRole = (teamId, payload) =>
  imRequest.post(`/api/permissions/teams/${teamId}/member-roles`, payload)

export const fetchTeamPermissionAudit = (teamId) =>
  imRequest.get(`/api/permissions/teams/${teamId}/audit`)
