import {
  formatChatTime,
  getStructuredMessageSearchContent,
  getMessageStatusText,
  parseAnnouncementPayload,
  parseSystemNotificationPayload,
} from '@/models/imPresentation'

export function useChatMessageModel({ currentUserId }) {
  function messageStatusText(status) {
    return getMessageStatusText(status)
  }

  function displayName(message) {
    if (message.messageType === 'SYSTEM_NOTIFICATION' || message.senderUserId == null)
      return '系统消息'
    if (message.senderUserId === currentUserId.value) return '我'
    return message.senderName || message.senderUsername || `用户 ${message.senderUserId}`
  }

  function recallText(message) {
    return message.recallByUserId === currentUserId.value
      ? '你撤回了一条消息'
      : '对方撤回了一条消息'
  }

  function announcementPayload(message = {}) {
    return parseAnnouncementPayload(message)
  }

  function systemNotificationPayload(message = {}) {
    return parseSystemNotificationPayload(message)
  }

  function displaySearchContent(message) {
    return getStructuredMessageSearchContent(message)
  }

  function formatTime(value) {
    return formatChatTime(value)
  }

  return {
    messageStatusText,
    displayName,
    recallText,
    announcementPayload,
    systemNotificationPayload,
    displaySearchContent,
    formatTime,
  }
}
