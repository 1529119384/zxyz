import request from '@/utils/request'

const DATABASE_MAINTENANCE_TIMEOUT = 30 * 60 * 1000

export const fetchDatabaseMaintenanceStatus = () =>
  request.get('/api/admin/database/maintenance/status')

export const exportDatabaseArchive = () =>
  request.get('/api/admin/database/exports', {
    rawBlob: true,
    responseType: 'blob',
    timeout: DATABASE_MAINTENANCE_TIMEOUT,
  })

export const importDatabaseArchive = (file, confirmationText) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('confirmationText', confirmationText)
  return request.post('/api/admin/database/imports', formData, {
    timeout: DATABASE_MAINTENANCE_TIMEOUT,
  })
}
