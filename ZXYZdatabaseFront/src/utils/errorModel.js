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

// 54 号审计 §2.6：曾在此导出的 isImConversationAccessError 已删除 ——
// 全仓 0 个生产消费点（只有本模块自己的测试在续命），且 IM 专属谓词不该住在通用
// 错误工具里。同一份判定逻辑的**唯一现存副本**在 IM 域内：
// store/im/conversationDomain.js 的 isConversationAccessError（数据源
// store/im/normalizers.js 的 IM_ACCESS_DENIED_CODES），后续改动请去那里。

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
