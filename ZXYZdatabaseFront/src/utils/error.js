import { ElMessage } from 'element-plus'

import { getErrorMessage, isHandledByGlobalError } from '@/utils/errorModel'

export {
  createBusinessError,
  getErrorCode,
  getErrorDetail,
  getErrorMessage,
  isHandledByGlobalError,
  isImConversationAccessError,
  logUploadError,
  markGlobalErrorHandled,
} from '@/utils/errorModel'

export function handleBusinessError(error, fallbackMessage = '操作失败，请稍后重试') {
  if (isHandledByGlobalError(error)) {
    return null
  }

  const message = getErrorMessage(error, fallbackMessage)
  if (!message) {
    return null
  }

  ElMessage.error(message)
  return message
}
