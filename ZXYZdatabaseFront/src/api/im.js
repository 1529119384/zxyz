// @ts-check
import imRequest from '@/utils/imRequest'

// ⚠️ 这里是**双前缀**，不是笔误（F8）：imRequest 的 baseURL 已经是 '/im-api'
// （见 utils/imRequest.js 的 requireViteEnv('VITE_IM_API_BASE_URL')），
// 而下述每条路径又自带 '/api/...' ⇒ 实际发出的是 '/im-api/api/im/...'。
//
// 它之所以成立，靠链路上三处同时成立（2026-09-21 逐处核对）：
//   ① 前端 baseURL = VITE_IM_API_BASE_URL（部署值为 '/im-api'）；
//   ② nginx `location /im-api { proxy_pass http://gateway:18000; }` —— **原样转发、不做 rewrite**
//      （deploy/nginx/snippets/proxy-locations.conf）；
//   ③ gateway 路由 `Path=/im-api/**` + `StripPrefix=1` 剥掉 '/im-api'
//      （zxyz-gateway/src/main/resources/application.yml）。
// ⇒ 到 im-service 时恰好还原成 '/api/im/...'。
//
// 只读本文件无法知道 '/im-api' 是"前缀"还是"另一个域"。改 baseURL、改 nginx 的 proxy_pass、
// 或改 gateway 的 StripPrefix 任意一处，都会让这里**静默 404**（不是编译错误）。
// 要动请三处一起看。
export const fetchImHealth = () => imRequest.get('/api/im/health')

/**
 * @param {Record<string, unknown>} [params] - 查询条件
 */
export const fetchMyConversations = (params = {}) =>
  imRequest.get('/api/im/conversations', { params })

/**
 * @param {string|number} conversationId - 会话 ID
 */
export const fetchConversation = (conversationId) =>
  imRequest.get(`/api/im/conversations/${conversationId}`)

/**
 * @param {Record<string, unknown>} payload - 单聊创建请求体
 */
export const createDirectConversation = (payload) =>
  imRequest.post('/api/im/direct-conversations', payload)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchTeamConversation = (teamId) =>
  imRequest.get(`/api/im/teams/${teamId}/conversation`)

/**
 * @param {string|number} conversationId - 会话 ID
 * @param {Record<string, unknown>} [params] - 分页等查询条件
 */
export const fetchConversationMessages = (conversationId, params = {}) =>
  imRequest.get(`/api/im/conversations/${conversationId}/messages`, {
    params,
  })

/**
 * @param {string|number} conversationId - 会话 ID
 * @param {Record<string, unknown>} [params] - 搜索条件
 */
export const searchConversationMessages = (conversationId, params = {}) =>
  imRequest.get(`/api/im/conversations/${conversationId}/messages/search`, {
    params,
  })

/**
 * @param {string|number} messageId - 消息 ID
 * @param {Record<string, unknown>} [payload] - 解析请求体
 */
export const resolveMessageFileCard = (messageId, payload = {}) =>
  imRequest.post(`/api/im/messages/${messageId}/file-card/resolve`, payload)

/**
 * @param {string|number} messageId - 消息 ID
 * @param {Record<string, unknown>} [payload] - 撤回请求体
 */
export const recallMessage = (messageId, payload = {}) =>
  imRequest.post(`/api/im/messages/${messageId}/recall`, payload)

/**
 * @param {string|number} conversationId - 会话 ID
 * @param {Record<string, unknown>} payload - 已读位点请求体
 */
export const updateConversationRead = (conversationId, payload) =>
  imRequest.post(`/api/im/conversations/${conversationId}/read`, payload)

/**
 * @param {Record<string, unknown>} [params] - 查询条件
 */
export const fetchSystemNotifications = (params = {}) =>
  imRequest.get('/api/im/system-notifications', {
    params,
  })

/**
 * @param {Record<string, unknown>} [params] - 查询条件
 */
export const fetchSystemNotificationUnreadCount = (params = {}) =>
  imRequest.get('/api/im/system-notifications/unread-count', { params })

export const fetchMyPresence = () => imRequest.get('/api/im/presence/me')

/**
 * @param {Array<string|number>} [userIds] - 用户 ID 列表
 */
export const fetchUserPresence = (userIds = []) =>
  imRequest.get('/api/im/presence/users', {
    params: { userIds: userIds.join(',') },
  })

/**
 * @param {string|number} notificationId - 通知 ID
 */
export const markSystemNotificationRead = (notificationId) =>
  imRequest.patch(`/api/im/system-notifications/${notificationId}/read`)
