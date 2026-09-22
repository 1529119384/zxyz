import { createApiClient } from '@/utils/createApiClient'
import { resolveApiBaseUrl } from '@/utils/env'

const DEFAULT_REQUEST_TIMEOUT = 15000
export const UPLOAD_REQUEST_TIMEOUT = 30000

// 基地址由 utils/env.js 统一提供（原先是本文件与 publicRequest.js 各存一份逐字相同的
// getApiBaseUrl()，改一处漏一处就会出现两套基地址 —— F7）。
const request = createApiClient({
  baseURL: resolveApiBaseUrl(),
  timeout: DEFAULT_REQUEST_TIMEOUT,
  onTokenExpired: 'redirect',
  enableRawBlob: true,
})

export default request
