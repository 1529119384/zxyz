// @ts-check
import { formatSize } from '@/utils/format'

/**
 * 后端 IM 消息原始记录（宽松描述：只列本模块读取的字段）。
 *
 * `senderUserId` 写成 `number|string`：`getSenderDisplayName` 要拿它跟当前用户 ID 比，
 * 而后端 JSON 里 ID 可能是数字也可能是字符串（前端各处都用 `Number()` 兜过一道）。
 *
 * @typedef {object} RawImMessage
 * @property {string} [content]
 * @property {string} [messageType]
 * @property {number|string} [senderUserId]
 * @property {string} [senderName]
 * @property {string} [senderUsername]
 */

/**
 * 结构化消息体（公告/系统通知：content 字段里是 JSON 字符串）。
 * @typedef {object} StructuredMessagePayload
 * @property {string} [title]
 * @property {string} [content]
 */

/**
 * 文件卡片消息体。
 * @typedef {object} FileCardPayload
 * @property {string} [shareType]
 * @property {number} [entryCount]
 * @property {Array<{ originalName?: string }>} [entries]
 */

/**
 * 项目创建申请卡片体。
 * @typedef {object} ProjectCreateRequestPayload
 * @property {string} [status]
 * @property {string} [content]
 * @property {string|number} [reviewerUserId]
 * @property {string} [reviewTime]
 * @property {string} [reviewReason]
 */

/**
 * 聊天时间格式化函数签名（默认实现 formatChatTime，便于测试注入桩）。
 * @typedef {(value: string|number|Date|null) => string|number|Date} ChatTimeFormatter
 */

/**
 * 状态码 → 文案。JS 取对象属性恒按字符串匹配，查找处统一 String() 归一：
 * 未知状态仍会落到不存在的键，由 `|| 兜底文案` 接手。
 * @type {Readonly<Record<string, string>>}
 */
const WS_STATUS_TEXT = {
  CONNECTED: '已连接',
  CONNECTING: '连接中',
  DISCONNECTED: '未连接',
  RECONNECTING: '重连中',
}

/** @type {Readonly<Record<string, string>>} */
const CONVERSATION_TYPE_TEXT = {
  SYSTEM: '系统消息',
  TEAM_NOTIFICATION: '团队消息',
  DIRECT: '私聊',
  PROJECT: '项目群聊',
  TEAM: '团队群聊',
}

/** @type {Readonly<Record<string, string>>} */
const MESSAGE_STATUS_TEXT = {
  SENDING: '发送中',
  FAILED: '发送失败',
  RECALLED: '已撤回',
}

/**
 * @param {string} [status]
 * @returns {string}
 */
export function formatWsStatus(status) {
  return WS_STATUS_TEXT[String(status)] || '未知状态'
}

/**
 * @param {{ type?: string }|null} [conversation]
 * @returns {string}
 */
export function getConversationTypeText(conversation) {
  if (!conversation) return ''
  return CONVERSATION_TYPE_TEXT[String(conversation.type)] || '会话'
}

/**
 * @param {string} [status]
 * @returns {string}
 */
export function getMessageStatusText(status) {
  return MESSAGE_STATUS_TEXT[String(status)] || ''
}

/**
 * @param {RawImMessage} [message]
 * @returns {StructuredMessagePayload}
 */
function parseStructuredMessagePayload(message = {}) {
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

/**
 * @param {RawImMessage} [message]
 * @returns {StructuredMessagePayload}
 */
export function parseAnnouncementPayload(message = {}) {
  return parseStructuredMessagePayload(message)
}

/**
 * @param {RawImMessage} [message]
 * @returns {StructuredMessagePayload}
 */
export function parseSystemNotificationPayload(message = {}) {
  return parseStructuredMessagePayload(message)
}

/**
 * @param {RawImMessage} [message]
 */
export function getStructuredMessageSearchContent(message = {}) {
  if (!['ANNOUNCEMENT', 'SYSTEM_NOTIFICATION'].includes(String(message.messageType))) {
    return message.content
  }

  const payload = parseStructuredMessagePayload(message)
  return payload.title
    ? `${payload.title} ${payload.content || ''}`.trim()
    : payload.content || message.content
}

/**
 * 消息发送者的展示名。
 *
 * 07-P1-7 从 `views/chat/composables/useChatMessageModel.js` 的 `displayName` 提出，
 * 并把 `currentUserId` 变成**显式参数** —— 原本它是闭包（靠 composable 的入参捕获），
 * 想复用就只能把整个函数当 prop 传给子组件。进到这里之后 ChatMoreDrawer 直接 import。
 *
 * @param {RawImMessage} [message] - 消息行。
 * @param {number|string|null} [currentUserId] - 当前登录用户 ID。
 * @returns {string}
 */
export function getSenderDisplayName(message = {}, currentUserId = null) {
  if (message.messageType === 'SYSTEM_NOTIFICATION' || message.senderUserId == null) {
    return '系统消息'
  }
  if (message.senderUserId === currentUserId) return '我'
  return message.senderName || message.senderUsername || `用户 ${message.senderUserId}`
}

/**
 * 群成员的展示名：名字 → 用户名 → `用户 <id>` 三级回落。
 *
 * 07-P1-7 从 `views/chat/composables/useChatMembers.js` 提出，供成员列表与 @提及共用。
 *
 * @param {{ name?: string, username?: string, userId?: number|string }} member - 成员行。
 * @returns {string}
 */
export function getMemberDisplayName(member) {
  return member.name || member.username || `用户 ${member.userId}`
}

/**
 * @param {FileCardPayload} [fileCard]
 * @returns {string}
 */
export function getFileCardTitle(fileCard = {}) {
  if (fileCard.shareType === 'MULTI_FILE') return `共 ${fileCard.entryCount || 0} 项`
  return fileCard.entries?.[0]?.originalName || '文件卡片'
}

/**
 * @param {FileCardPayload} [fileCard]
 * @returns {string}
 */
export function getFileCardSummary(fileCard = {}) {
  if (fileCard.shareType === 'SINGLE_FILE') return '文件'
  if (fileCard.shareType === 'SINGLE_FOLDER') return '文件夹'
  return `包含 ${fileCard.entryCount || 0} 个资源`
}

/**
 * @param {FileCardPayload} [fileCard]
 * @returns {Array<{ originalName?: string }>}
 */
export function getFileCardPreviewEntries(fileCard = {}) {
  return (fileCard.entries || []).slice(0, 3)
}

/**
 * @param {RawImMessage} [message]
 * @returns {ProjectCreateRequestPayload}
 */
export function parseProjectCreateRequestPayload(message = {}) {
  if (!message.content) return {}
  try {
    return JSON.parse(message.content)
  } catch {
    // 兼容异常消息体，审批卡片仍按待处理状态展示基础内容。
    return { content: message.content, status: 'PENDING' }
  }
}

/**
 * @param {ProjectCreateRequestPayload} [payload]
 * @param {ChatTimeFormatter} [formatTime]
 * @returns {string}
 */
export function formatProjectCreateRequestStatusText(payload = {}, formatTime = formatChatTime) {
  const reviewer = payload.reviewerUserId ? `，处理人 ${payload.reviewerUserId}` : ''
  const reviewTime = payload.reviewTime ? `，处理时间 ${formatTime(payload.reviewTime)}` : ''
  const reason = payload.reviewReason ? `，原因：${payload.reviewReason}` : ''
  return `${payload.status === 'APPROVED' ? '已同意' : '已拒绝'}${reviewer}${reviewTime}${reason}`
}

/**
 * @param {number|string|null} [value]
 * @returns {string}
 */
export function formatProjectQuotaText(value) {
  return value == null ? '无限' : formatSize(Number(value || 0))
}

/**
 * @param {string|number|Date|null} [value]
 * @returns {string|number|Date}
 */
export function formatChatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.getMonth() + 1}-${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
