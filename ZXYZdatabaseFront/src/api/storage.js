import request from '@/utils/request'

export const listStorageProviders = () => {
  return request.get('/api/admin/storage-providers')
}

export const updateStorageProvider = (providerId, data) => {
  return request.patch(`/api/admin/storage-providers/${providerId}`, data)
}

export const checkStorageProviderHealth = (providerId) => {
  return request.get(`/api/admin/storage-providers/${providerId}/health`)
}
