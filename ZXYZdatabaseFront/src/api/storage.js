// @ts-check
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
 * 更新存储提供者配置。
 *
 * 07-A-3 复核（2026-09-21）：**后端端点真实存在** —— `zxyz-file-service` 的
 * `StorageProviderController#update`（`@PatchMapping("/{providerId}")`），
 * 网关路由 id `file-service-storage-providers` 覆盖 `/api/admin/storage-providers/**`。
 * 同文件的 `listStorageProviders` / `checkStorageProviderHealth` 也**都没有前端消费者**，
 * 即整个存储提供者管理面（后端 + 网关）已就绪、UI 尚未接。刻意保留而非删除。
 *
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
