import {
  formatChatTime,
  getStructuredMessageSearchContent,
  getMessageStatusText,
  getSenderDisplayName,
  parseAnnouncementPayload,
  parseSystemNotificationPayload,
} from '@/models/imPresentation'

export function useChatMessageModel({ currentUserId }) {
  function messageStatusText(status) {
    return getMessageStatusText(status)
  }

  // 07-P1-7：实现提到 models/imPresentation 并显式接收 currentUserId，这里只做转发。
  function displayName(message) {
    return getSenderDisplayName(message, currentUserId.value)
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
