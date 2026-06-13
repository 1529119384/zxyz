import request, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

export const fetchMyTeams = () => request.get('/api/teams/my')

export const updateTeam = (teamId, payload) => request.patch(`/api/teams/${teamId}`, payload)

export const getTeamAvatarUploadSign = (teamId, payload) =>
  request.post(`/api/teams/${teamId}/avatar/upload-sign`, payload, {
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })

export const fetchTeamMembers = (teamId) => request.get(`/api/teams/${teamId}/members`)

export const createTeamMember = (teamId, payload) =>
  request.post(`/api/teams/${teamId}/members`, payload)

export const updateTeamMemberStatus = (teamId, userId, payload) =>
  request.patch(`/api/teams/${teamId}/members/${userId}/status`, payload)

export const removeTeamMember = (teamId, userId) =>
  request.delete(`/api/teams/${teamId}/members/${userId}`)

export const leaveTeam = (teamId) => request.post(`/api/teams/${teamId}/leave`)

export const fetchTeamMembersStorage = (teamId) =>
  request.get(`/api/teams/${teamId}/members/storage`)

export const updateMemberStorageLimit = (teamId, userId, payload) =>
  request.patch(`/api/teams/${teamId}/members/${userId}/storage`, payload)
