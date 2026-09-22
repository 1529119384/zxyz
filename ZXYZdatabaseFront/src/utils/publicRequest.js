import { createApiClient } from '@/utils/createApiClient'
import { resolveApiBaseUrl } from '@/utils/env'

// 基地址由 utils/env.js 统一提供（原先本文件与 request.js 各存一份逐字相同的
// getApiBaseUrl() —— F7）。
const publicRequest = createApiClient({
  baseURL: resolveApiBaseUrl(),
  timeout: 5000,
  onTokenExpired: 'silent',
})

export default publicRequest
