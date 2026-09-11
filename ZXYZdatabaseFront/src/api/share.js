// @ts-check
import { mapMyShareRecords, mapShareFileEntries } from '@/models/share'
import request from '@/utils/request'
import publicRequest from '@/utils/publicRequest'

/**
 * @param {Record<string, unknown>} payload - 分享创建请求体
 */
export const createShare = (payload) => request.post('/api/shares', payload)

/**
 * @param {Record<string, unknown>} [params] - 分页等查询条件
 */
export const fetchMyShareList = async (params = {}) => {
  const response = await request.get('/api/shares', {
    params,
  })

  return {
    ...response,
    data: mapMyShareRecords(response?.data),
  }
}

/**
 * @param {string|number} shareId - 分享 ID
 */
export const cancelMyShare = (shareId) => request.patch(`/api/shares/${shareId}`, { status: 1 })

/**
 * @param {string} shareKey - 分享口令
 */
export const fetchPublicShareInfo = (shareKey) =>
  publicRequest.get(`/api/public/shares/${shareKey}`)

/**
 * @param {string} shareKey - 分享口令
 * @param {string} password - 访问密码
 */
export const verifySharePassword = (shareKey, password) =>
  publicRequest.post(`/api/public/shares/${shareKey}/accesses`, { password })

/**
 * @param {string} shareKey - 分享口令
 * @param {string} [path] - 目录路径
 */
export const fetchPublicShareFiles = async (shareKey, path = '') => {
  const response = await publicRequest.get(`/api/public/shares/${shareKey}/files`, {
    params: path ? { path } : {},
  })

  return {
    ...response,
    data: mapShareFileEntries(response?.data),
  }
}

/**
 * @param {string} shareKey - 分享口令
 * @param {string|number} fileId - 文件 ID
 */
export const getPublicShareDownloadUrl = (shareKey, fileId) =>
  publicRequest.get(`/api/public/shares/${shareKey}/files/${fileId}/download-url`)
