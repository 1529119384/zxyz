import { createApiClient } from '@/utils/createApiClient'
import { requireViteEnv } from '@/utils/env'

function getImApiBaseUrl() {
  return requireViteEnv('VITE_IM_API_BASE_URL')
}

const imRequest = createApiClient({
  baseURL: getImApiBaseUrl(),
  timeout: 10000,
  onTokenExpired: 'silent',
  errorMessagePrefix: 'IM ',
})

export default imRequest
