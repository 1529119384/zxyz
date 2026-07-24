/**
 * @typedef {Object} StorageProviderVO
 * @property {string}  providerId              - 提供者标识（如 "oss", "local"）
 * @property {string}  displayName             - 显示名称（如 "阿里云 OSS", "本地磁盘"）
 * @property {boolean} enabled                 - 是否启用
 * @property {boolean} isDefault               - 是否为默认提供者
 * @property {boolean} supportsPresignedUpload - 是否支持预签名直传上传
 * @property {boolean} supportsPresignedDownload - 是否支持预签名直传下载
 */

/**
 * @typedef {Object} HealthCheckResult
 * @property {string}  providerId - 提供者标识
 * @property {boolean} healthy    - 健康状态
 * @property {string}  message    - 健康信息描述
 */

import request from '@/utils/request'

/**
 * 获取所有存储提供者
 * @returns {Promise<{code: number, data: StorageProviderVO[]}>}
 */
export const listStorageProviders = () => {
  return request.get('/api/admin/storage-providers')
}

/**
 * 更新存储提供者配置
 * @param {string} providerId - 提供者标识
 * @param {{displayName?: string, enabled?: boolean, isDefault?: boolean, configJson?: string}} data - 配置数据
 * @returns {Promise<{code: number}>}
 */
export const updateStorageProvider = (providerId, data) => {
  return request.patch(`/api/admin/storage-providers/${providerId}`, data)
}

/**
 * 检查存储提供者健康状态
 * @param {string} providerId - 提供者标识
 * @returns {Promise<{code: number, data: HealthCheckResult}>}
 */
export const checkStorageProviderHealth = (providerId) => {
  return request.get(`/api/admin/storage-providers/${providerId}/health`)
}
