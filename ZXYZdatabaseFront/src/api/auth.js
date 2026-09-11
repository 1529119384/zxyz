// @ts-check
import request from '@/utils/request'

/**
 * @param {Record<string, unknown>} payload - 登录请求体（账号/凭证）
 */
export const login = (payload) => {
  return request.post('/api/users/login', payload)
}

/**
 * @param {Record<string, unknown>} payload - 注册请求体
 */
export const register = (payload) => {
  return request.post('/api/users/register', payload)
}

export const fetchCurrentUser = () => {
  return request.get('/api/users/me')
}

export const logout = () => {
  return request.post('/api/users/logout')
}
