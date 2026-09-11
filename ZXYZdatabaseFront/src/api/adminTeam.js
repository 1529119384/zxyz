// @ts-check
import request from '@/utils/request'

/**
 * @param {Record<string, unknown>} payload - 团队创建请求体
 */
export const createAdminTeam = (payload) => request.post('/api/admin/teams', payload)

export const fetchAdminTeams = () => request.get('/api/admin/teams')

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 配额请求体
 */
export const updateAdminTeamQuota = (teamId, payload) =>
  request.patch(`/api/admin/teams/${teamId}/quota`, payload)

/**
 * @param {Record<string, unknown>} payload - 系统消息请求体
 */
export const broadcastSystemMessage = (payload) =>
  request.post('/api/admin/teams/system-messages', payload)

/**
 * @param {Record<string, unknown>} payload - 邮件批次请求体
 */
export const scheduleSystemEmailBatch = (payload) =>
  request.post('/api/admin/teams/system-emails/scheduled-batches', payload)
