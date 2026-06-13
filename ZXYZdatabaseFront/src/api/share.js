import { mapMyShareRecords, mapShareFileEntries } from '@/models/share'
import request from '@/utils/request'
import publicRequest from '@/utils/publicRequest'

const getShareRequestConfig = (config = {}) => ({
  withCredentials: true,
  ...config,
})

export const createShare = (payload) => request.post('/api/shares', payload)

export const fetchMyShareList = async (params = {}) => {
  const response = await request.get('/api/shares', {
    params,
  })

  return {
    ...response,
    data: mapMyShareRecords(response?.data),
  }
}

export const cancelMyShare = (shareId) => request.patch(`/api/shares/${shareId}`, { status: 1 })

export const fetchPublicShareInfo = (shareKey) =>
  publicRequest.get(`/api/public/shares/${shareKey}`, getShareRequestConfig())

export const verifySharePassword = (shareKey, password) =>
  publicRequest.post(
    `/api/public/shares/${shareKey}/accesses`,
    { password },
    getShareRequestConfig(),
  )

export const fetchPublicShareFiles = async (shareKey, path = '') => {
  const response = await publicRequest.get(
    `/api/public/shares/${shareKey}/files`,
    getShareRequestConfig({
      params: path ? { path } : {},
    }),
  )

  return {
    ...response,
    data: mapShareFileEntries(response?.data),
  }
}

export const getPublicShareDownloadUrl = (shareKey, fileId) =>
  publicRequest.get(
    `/api/public/shares/${shareKey}/files/${fileId}/download-url`,
    getShareRequestConfig(),
  )
