import { formatSize } from '@/utils/format'

const WS_STATUS_TEXT = {
  CONNECTED: '已连接',
  CONNECTING: '连接中',
  DISCONNECTED: '未连接',
  RECONNECTING: '重连中',
}

const CONVERSATION_TYPE_TEXT = {
  SYSTEM: '系统消息',
  TEAM_NOTIFICATION: '团队消息',
  DIRECT: '私聊',
  PROJECT: '项目群聊',
  TEAM: '团队群聊',
}

const MESSAGE_STATUS_TEXT = {
  SENDING: '发送中',
  FAILED: '发送失败',
  RECALLED: '已撤回',
}

export function formatWsStatus(status) {
  return WS_STATUS_TEXT[status] || '未知状态'
}

export function getConversationTypeText(conversation) {
  if (!conversation) return ''
  return CONVERSATION_TYPE_TEXT[conversation.type] || '会话'
}

export function getMessageStatusText(status) {
  return MESSAGE_STATUS_TEXT[status] || ''
}

export function parseStructuredMessagePayload(message = {}) {
  const rawContent = message.content || ''
  if (!rawContent) return {}
  try {
    const payload = JSON.parse(rawContent)
    return {
      ...payload,
      content: payload.content || '',
    }
  } catch {
    // 兼容历史纯文本消息，避免旧消息因非 JSON 内容无法展示。
    return { content: rawContent }
  }
}

export function parseAnnouncementPayload(message = {}) {
  return parseStructuredMessagePayload(message)
}

export function parseSystemNotificationPayload(message = {}) {
  return parseStructuredMessagePayload(message)
}

export function getStructuredMessageSearchContent(message = {}) {
  if (!['ANNOUNCEMENT', 'SYSTEM_NOTIFICATION'].includes(message.messageType)) {
    return message.content
  }

  const payload = parseStructuredMessagePayload(message)
  return payload.title
    ? `${payload.title} ${payload.content || ''}`.trim()
    : payload.content || message.content
}

export function getFileCardTitle(fileCard = {}) {
  if (fileCard.shareType === 'MULTI_FILE') return `共 ${fileCard.entryCount || 0} 项`
  return fileCard.entries?.[0]?.originalName || '文件卡片'
}

export function getFileCardSummary(fileCard = {}) {
  if (fileCard.shareType === 'SINGLE_FILE') return '文件'
  if (fileCard.shareType === 'SINGLE_FOLDER') return '文件夹'
  return `包含 ${fileCard.entryCount || 0} 个资源`
}

export function getFileCardPreviewEntries(fileCard = {}) {
  return (fileCard.entries || []).slice(0, 3)
}

export function parseProjectCreateRequestPayload(message = {}) {
  if (!message.content) return {}
  try {
    return JSON.parse(message.content)
  } catch {
    // 兼容异常消息体，审批卡片仍按待处理状态展示基础内容。
    return { content: message.content, status: 'PENDING' }
  }
}

export function formatProjectCreateRequestStatusText(payload = {}, formatTime = formatChatTime) {
  const reviewer = payload.reviewerUserId ? `，处理人 ${payload.reviewerUserId}` : ''
  const reviewTime = payload.reviewTime ? `，处理时间 ${formatTime(payload.reviewTime)}` : ''
  const reason = payload.reviewReason ? `，原因：${payload.reviewReason}` : ''
  return `${payload.status === 'APPROVED' ? '已同意' : '已拒绝'}${reviewer}${reviewTime}${reason}`
}

export function formatProjectQuotaText(value) {
  return value == null ? '无限' : formatSize(Number(value || 0))
}

export function formatChatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.getMonth() + 1}-${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
