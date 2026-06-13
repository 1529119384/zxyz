import { logger } from '@/utils/logger'

const GLOBAL_ERROR_HANDLED_FLAG = '__globalErrorHandled__'

function normalizeMessage(value) {
  return typeof value === 'string' ? value.trim() : ''
}

export function createBusinessError(message, response, extra = {}) {
  const error = new Error(message || '请求失败')
  error.response = response
  Object.assign(error, extra)
  return error
}

export function markGlobalErrorHandled(error) {
  if (error && typeof error === 'object') {
    error[GLOBAL_ERROR_HANDLED_FLAG] = true
  }

  return error
}

export function isHandledByGlobalError(error) {
  return Boolean(error?.[GLOBAL_ERROR_HANDLED_FLAG])
}

export function getErrorMessage(error, fallbackMessage = '操作失败，请稍后重试') {
  const responseData = error?.response?.data
  const responseText = normalizeMessage(responseData)

  if (responseText) {
    return responseText
  }

  const responseMsg = normalizeMessage(responseData?.msg)
  if (responseMsg) {
    return responseMsg
  }

  const responseMessage = normalizeMessage(responseData?.message)
  if (responseMessage) {
    return responseMessage
  }

  const errorMessage = normalizeMessage(error?.message)
  if (errorMessage) {
    return errorMessage
  }

  return fallbackMessage
}

export function getErrorDetail(error, fallbackMessage = '上传失败，请稍后重试') {
  return getErrorMessage(error, fallbackMessage)
}

export function getErrorCode(error) {
  const code = error?.response?.data?.code
  const normalized = Number(code)
  return Number.isFinite(normalized) ? normalized : null
}

export function isImConversationAccessError(error) {
  const code = getErrorCode(error)
  return code === 4030 || code === 4400 || code === 4401
}

export function logUploadError(stage, file, error, extra = {}) {
  logger.error(`[upload failed] ${stage}`, {
    fileName: file?.name,
    fileSize: file?.size,
    status: error?.response?.status,
    message: error?.message,
    responseData: error?.response?.data,
    ...extra,
  })
}
