// @ts-check
import request from '@/utils/request'

/**
 * @param {Record<string, unknown>} payload - 团队创建请求体
 */
export const createAdminTeam = (payload) => request.post('/api/admin/teams', payload)

/**
 * 分页拉取管理端团队列表。
 *
 * @param {{page?: number, pageSize?: number}} [params] - 分页参数（默认 1 / 20，后端上限 200）
 */
export const fetchAdminTeams = (params) => request.get('/api/admin/teams', { params })

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
