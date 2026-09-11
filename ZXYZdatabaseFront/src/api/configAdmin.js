// @ts-check
import request from '@/utils/request'

export const fetchAllConfigs = () => request.get('/api/admin/configs')

/**
 * @param {string} key - 配置键
 */
export const fetchConfig = (key) => request.get(`/api/admin/configs/${key}`)

/**
 * @param {Record<string, unknown>} payload - 配置创建请求体
 */
export const createConfig = (payload) => request.post('/api/admin/configs', payload)

/**
 * @param {string} key - 配置键
 * @param {unknown} value - 配置值
 */
export const updateConfig = (key, value) => request.put(`/api/admin/configs/${key}`, { value })

export const fetchAuditLogs = () => request.get('/api/admin/configs/audit')
