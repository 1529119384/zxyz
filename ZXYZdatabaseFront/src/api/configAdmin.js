import request from '@/utils/request'

export const fetchAllConfigs = () => request.get('/api/admin/configs')

export const fetchConfig = (key) => request.get(`/api/admin/configs/${key}`)

export const createConfig = (payload) => request.post('/api/admin/configs', payload)

export const updateConfig = (key, value) =>
  request.put(`/api/admin/configs/${key}`, { value })

export const fetchAuditLogs = () => request.get('/api/admin/configs/audit')
