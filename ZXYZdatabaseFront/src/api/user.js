// @ts-check
import request, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

export const fetchUserSettings = () => request.get('/api/users/settings')

/**
 * @param {Record<string, unknown>} data - 设置请求体
 */
export const updateUserSettings = (data) => request.patch('/api/users/settings', data)

/**
 * @param {Record<string, unknown>} data - 上传签名请求体
 */
export const getUserAvatarUploadSign = (data) =>
  request.post('/api/users/avatar/upload-sign', data, {
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })

/**
 * @param {Record<string, unknown>} data - 改密请求体
 */
export const changePassword = (data) => request.patch('/api/users/password', data)

/**
 * @param {Record<string, unknown>} data - 邮箱绑定请求体
 */
export const bindEmail = (data) => request.patch('/api/users/email', data)

/**
 * @param {Record<string, unknown>} data - 手机绑定请求体
 */
export const bindPhone = (data) => request.patch('/api/users/phone', data)

/**
 * @param {Record<string, unknown>} data - 默认团队请求体
 */
export const setDefaultTeam = (data) => request.patch('/api/users/default-team', data)

/**
 * @param {string} keyword - 搜索关键词
 */
export const searchUsers = (keyword) =>
  request.get('/api/users/search', {
    params: { keyword },
  })
