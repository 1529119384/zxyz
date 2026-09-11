// @ts-check
import request from '@/utils/request'

const EMAIL_CONNECTIVITY_TIMEOUT = 20000

export const fetchEmailServerConfigs = () => request.get('/api/admin/email/server-configs')

export const fetchCurrentEmailServerConfig = () =>
  request.get('/api/admin/email/server-configs/current')

export const fetchEmailRuntimeStatus = () => request.get('/api/admin/email/runtime-status')

/**
 * @param {Record<string, unknown>} payload - 服务器配置请求体
 */
export const createEmailServerConfig = (payload) =>
  request.post('/api/admin/email/server-configs', payload)

/**
 * @param {string|number} id - 配置 ID
 * @param {Record<string, unknown>} payload - 服务器配置请求体
 */
export const updateEmailServerConfig = (id, payload) =>
  request.put(`/api/admin/email/server-configs/${id}`, payload)

/**
 * @param {string|number} id - 配置 ID
 */
export const testEmailServerConfig = (id) =>
  request.post(
    `/api/admin/email/server-configs/${id}/test`,
    {},
    { timeout: EMAIL_CONNECTIVITY_TIMEOUT },
  )

/**
 * @param {string|number} id - 配置 ID
 */
export const activateEmailServerConfig = (id) =>
  request.post(
    `/api/admin/email/server-configs/${id}/activate`,
    {},
    { timeout: EMAIL_CONNECTIVITY_TIMEOUT },
  )

/**
 * @param {Record<string, unknown>} [params] - 查询条件
 */
export const fetchEmailRecords = (params = {}) =>
  request.get('/api/admin/email/records', { params })

/**
 * @param {string|number} id - 记录 ID
 */
export const fetchEmailRecordDetail = (id) => request.get(`/api/admin/email/records/${id}`)
