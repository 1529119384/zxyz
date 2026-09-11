// @ts-check
import imRequest from '@/utils/imRequest'

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
