/**
 * 认证工具模块。
 *
 * 当前架构采用后端 HttpOnly Cookie 进行身份认证，前端不再存储或管理 token。
 * Sa-Token 的 session cookie 由后端自动设置，浏览器请求时自动携带，
 * 因此前端无需手动注入 Authorization Header。
 *
 * 仅保留登出时的本地状态清理函数。
 */
const LOGIN_USER_KEY = 'loginUser'

export function clearToken() {
  localStorage.removeItem(LOGIN_USER_KEY)
}

export function clearLoginUser() {
  clearToken()
}
