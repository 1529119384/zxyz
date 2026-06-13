import { createApiClient } from '@/utils/createApiClient'
import { requireViteEnv } from '@/utils/env'

export const DEFAULT_REQUEST_TIMEOUT = 15000
export const UPLOAD_REQUEST_TIMEOUT = 30000

function getApiBaseUrl() {
  return requireViteEnv('VITE_API_BASE_URL')
}

const request = createApiClient({
  baseURL: getApiBaseUrl(),
  timeout: DEFAULT_REQUEST_TIMEOUT,
  onTokenExpired: 'redirect',
  enableRawBlob: true,
})

export default request
