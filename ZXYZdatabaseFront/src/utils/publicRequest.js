import { createApiClient } from '@/utils/createApiClient'
import { requireViteEnv } from '@/utils/env'

function getApiBaseUrl() {
  return requireViteEnv('VITE_API_BASE_URL')
}

const publicRequest = createApiClient({
  baseURL: getApiBaseUrl(),
  timeout: 5000,
  onTokenExpired: 'silent',
})

export default publicRequest
