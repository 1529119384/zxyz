import imRequest from '@/utils/imRequest'

export const fetchImHealth = () => imRequest.get('/api/im/health')

export const fetchMyConversations = (params = {}) =>
  imRequest.get('/api/im/conversations', { params })

export const fetchConversation = (conversationId) =>
  imRequest.get(`/api/im/conversations/${conversationId}`)

export const createDirectConversation = (payload) =>
  imRequest.post('/api/im/direct-conversations', payload)

export const fetchTeamConversation = (teamId) =>
  imRequest.get(`/api/im/teams/${teamId}/conversation`)

export const fetchConversationMessages = (conversationId, params = {}) =>
  imRequest.get(`/api/im/conversations/${conversationId}/messages`, {
    params,
  })

export const searchConversationMessages = (conversationId, params = {}) =>
  imRequest.get(`/api/im/conversations/${conversationId}/messages/search`, {
    params,
  })

export const resolveMessageFileCard = (messageId, payload = {}) =>
  imRequest.post(`/api/im/messages/${messageId}/file-card/resolve`, payload)

export const recallMessage = (messageId, payload = {}) =>
  imRequest.post(`/api/im/messages/${messageId}/recall`, payload)

export const updateConversationRead = (conversationId, payload) =>
  imRequest.post(`/api/im/conversations/${conversationId}/read`, payload)

export const fetchSystemNotifications = (params = {}) =>
  imRequest.get('/api/im/system-notifications', {
    params,
  })

export const fetchSystemNotificationUnreadCount = (params = {}) =>
  imRequest.get('/api/im/system-notifications/unread-count', { params })

export const fetchMyPresence = () => imRequest.get('/api/im/presence/me')

export const fetchUserPresence = (userIds = []) =>
  imRequest.get('/api/im/presence/users', {
    params: { userIds: userIds.join(',') },
  })

export const markSystemNotificationRead = (notificationId) =>
  imRequest.patch(`/api/im/system-notifications/${notificationId}/read`)
