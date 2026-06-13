import request from '@/utils/request'

export const createAdminTeam = (payload) => request.post('/api/admin/teams', payload)

export const fetchAdminTeams = () => request.get('/api/admin/teams')

export const updateAdminTeamQuota = (teamId, payload) =>
  request.patch(`/api/admin/teams/${teamId}/quota`, payload)

export const broadcastSystemMessage = (payload) =>
  request.post('/api/admin/teams/system-messages', payload)

export const scheduleSystemEmailBatch = (payload) =>
  request.post('/api/admin/teams/system-emails/scheduled-batches', payload)
