import request from '@/utils/request'

const EMAIL_CONNECTIVITY_TIMEOUT = 20000

export const fetchEmailServerConfigs = () => request.get('/api/admin/email/server-configs')

export const fetchCurrentEmailServerConfig = () =>
  request.get('/api/admin/email/server-configs/current')

export const fetchEmailRuntimeStatus = () => request.get('/api/admin/email/runtime-status')

export const createEmailServerConfig = (payload) =>
  request.post('/api/admin/email/server-configs', payload)

export const updateEmailServerConfig = (id, payload) =>
  request.put(`/api/admin/email/server-configs/${id}`, payload)

export const testEmailServerConfig = (id) =>
  request.post(
    `/api/admin/email/server-configs/${id}/test`,
    {},
    { timeout: EMAIL_CONNECTIVITY_TIMEOUT },
  )

export const activateEmailServerConfig = (id) =>
  request.post(
    `/api/admin/email/server-configs/${id}/activate`,
    {},
    { timeout: EMAIL_CONNECTIVITY_TIMEOUT },
  )

export const fetchEmailRecords = (params = {}) =>
  request.get('/api/admin/email/records', { params })

export const fetchEmailRecordDetail = (id) => request.get(`/api/admin/email/records/${id}`)
