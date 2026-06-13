import axios from 'axios'

import { clearToken } from '@/utils/auth'
import { useCurrentUserStore } from '@/store/currentUser'
import { createBusinessError, markGlobalErrorHandled } from '@/utils/errorModel'
import { sanitizeRedirectPath } from '@/utils/sanitizeRedirect'

function normalizeErrorText(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

/**
 * 检测是否为认证失败响应。
 * 优先通过 HTTP 状态码和业务错误码判断，文本匹配作为兜底。
 *
 * @param {Object} payload - 响应 body（可能为 undefined）
 * @param {number} [httpStatus] - HTTP 状态码（可选，非 2xx 响应时传入）
 * @returns {boolean}
 */
function isAuthFailurePayload(payload, httpStatus) {
  // 1. HTTP 401 判定为认证失败（排除 4100 用户名密码错误，让业务层处理）
  if (httpStatus === 401 && payload?.code !== 4100) return true

  // 2. 业务错误码 4010（后端统一的未登录码）
  const code = payload?.code
  if (code === 4010) return true

  // 3. 文本关键词兜底
  const message = normalizeErrorText(payload?.msg || payload?.message)
  if (!message) return false

  return [
    'no_login',
    '未登录',
    '未登陆',
    'token无效',
    'token 已失效',
    'token已失效',
    'token 失效',
    '登录失效',
    'invalid token',
    'token expired',
  ].some((keyword) => message.includes(keyword))
}

function getBasePath() {
  const rawBasePath = import.meta.env.BASE_URL || '/'
  const normalizedBasePath = rawBasePath.startsWith('/') ? rawBasePath : `/${rawBasePath}`
  return normalizedBasePath.endsWith('/') ? normalizedBasePath : `${normalizedBasePath}/`
}

function buildLoginUrl() {
  if (typeof window === 'undefined') return ''

  const basePath = getBasePath()
  const loginPath = new URL('login', `${window.location.origin}${basePath}`).pathname
  const rawPath =
    `${window.location.pathname}${window.location.search}${window.location.hash}` || '/index'
  const currentPath = sanitizeRedirectPath(rawPath)
  const searchParams = new URLSearchParams({ redirect: currentPath })

  return `${loginPath}?${searchParams.toString()}`
}

function handleAuthFailure(onTokenExpired) {
  // 无论 onTokenExpired 策略如何，认证失败时都清除本地用户状态
  try {
    useCurrentUserStore().clearProfile()
  } catch (_) {
    // Pinia 未初始化时忽略
  }
  if (onTokenExpired === 'redirect') {
    redirectToLogin()
  }
}

function redirectToLogin() {
  clearToken()

  // 清除本地用户状态
  try {
    useCurrentUserStore().clearProfile()
  } catch (_) {
    // Pinia 未初始化时忽略
  }

  if (typeof window === 'undefined') return

  const loginUrl = buildLoginUrl()
  const loginPath = loginUrl.split('?')[0]

  if (window.location.pathname === loginPath) return

  window.location.replace(loginUrl)
}

/**
 * 创建统一的 Axios 实例，内置鉴权注入、业务码解析、网络错误标准化等拦截逻辑。
 *
 * @param {Object} options
 * @param {string} options.baseURL - API 基础地址
 * @param {number} [options.timeout=5000] - 请求超时时间（毫秒）
 * @param {'redirect'|'silent'} [options.onTokenExpired='redirect'] - Token 失效时的处理策略
 *   - 'redirect'：清除 token 并跳转登录页（适用于主服务）
 *   - 'silent'：仅标记错误已处理，不跳转（适用于附属服务）
 * @param {boolean} [options.attachAuth=true] - 是否自动注入登录鉴权 Header
 * @param {string} [options.errorMessagePrefix=''] - 错误消息前缀，如 'IM '
 * @param {boolean} [options.enableRawBlob=false] - 是否支持原始 Blob 响应透传
 * @returns {import('axios').AxiosInstance}
 */
export function createApiClient(options = {}) {
  const {
    baseURL,
    timeout = 5000,
    onTokenExpired = 'redirect',
    attachAuth = true,
    errorMessagePrefix = '',
    enableRawBlob = false,
    withCredentials = true,
  } = options

  const client = axios.create({ baseURL, timeout, withCredentials })

  // 请求拦截器：认证通过 HttpOnly Cookie 自动携带（withCredentials: true），
  // 无需手动注入 Authorization Header。
  client.interceptors.request.use(
    (config) => config,
    (error) => Promise.reject(error),
  )

  // 响应拦截器
  client.interceptors.response.use(
    // HTTP 2xx 响应
    (response) => {
      if (enableRawBlob && response.config?.rawBlob) {
        return response
      }

      const payload = response.data

      if (payload?.code === 1) {
        return payload
      }

      if (isAuthFailurePayload(payload)) {
        handleAuthFailure(onTokenExpired)
        return Promise.reject(
          markGlobalErrorHandled(
            createBusinessError(payload?.msg || payload?.message || 'NO_LOGIN', response, {
              code: payload?.code,
              msg: payload?.msg || payload?.message,
              data: payload?.data,
            }),
          ),
        )
      }

      return Promise.reject(
        createBusinessError(
          payload?.msg || payload?.message || `${errorMessagePrefix}请求失败`,
          response,
          { code: payload?.code, msg: payload?.msg || payload?.message, data: payload?.data },
        ),
      )
    },
    // HTTP 非 2xx 响应 / 网络错误
    (error) => {
      const status = error.response?.status
      const payload = error.response?.data

      if (isAuthFailurePayload(payload, status)) {
        handleAuthFailure(onTokenExpired)
        return Promise.reject(markGlobalErrorHandled(error))
      }

      if (!error.response) {
        error.message =
          error.code === 'ECONNABORTED'
            ? '请求超时，请检查网络或稍后重试'
            : '网络未连接或服务异常，请稍后重试'
        return Promise.reject(error)
      }

      // 503 服务不可用：可能是后端服务未启动或 Nacos 注册失败
      if (status === 503) {
        const serverMsg = payload?.msg || payload?.message
        // 检查是否是 Spring 默认错误格式（有 error 字段但没有 code 字段）
        const isGatewayError =
          payload && typeof payload === 'object' && 'error' in payload && !('code' in payload)
        const message = isGatewayError
          ? '服务暂时不可用，请稍后重试'
          : serverMsg || '服务暂时不可用，请稍后重试'
        return Promise.reject(
          createBusinessError(message, error.response, {
            code: payload?.code || 5000,
            msg: message,
            data: null,
          }),
        )
      }

      // Gateway 可能返回 HTML 错误页面（非 JSON），此时 payload 不是对象
      if (status >= 400 && (!payload || typeof payload !== 'object')) {
        // 503 已在上方提前处理，此处仅需区分 5xx 与 4xx
        const message = status >= 500 ? '服务器异常，请稍后重试' : '请求失败'
        return Promise.reject(
          createBusinessError(message, error.response, {
            code: status >= 500 ? 5000 : 4000,
            msg: message,
            data: null,
          }),
        )
      }

      if (status >= 500) {
        const serverMsg = payload?.msg || payload?.message
        return Promise.reject(
          createBusinessError(serverMsg || '服务器异常，请稍后重试', error.response, {
            code: payload?.code,
            msg: serverMsg,
            data: payload?.data,
          }),
        )
      }

      if (payload && typeof payload === 'object') {
        return Promise.reject(
          createBusinessError(
            payload?.msg || payload?.message || `${errorMessagePrefix}请求失败`,
            error.response,
            { code: payload?.code, msg: payload?.msg || payload?.message, data: payload?.data },
          ),
        )
      }

      return Promise.reject(error)
    },
  )

  return client
}
