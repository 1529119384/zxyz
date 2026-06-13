import { TEAM } from '@/constants/conversationTypes'
import { STORED } from '@/constants/messageStatus'
import { normalizePositiveId } from '@/utils/id'

export const READ_SYNC_DELAY_MS = 300
export const IM_ACCESS_DENIED_CODES = new Set([4030, 4400, 4401])
export const DEFAULT_TEAM_ID_KEY = 'defaultTeamId'

export function requireTeamId(value) {
  const teamId = normalizePositiveId(value)
  if (!teamId) {
    throw new Error('请先选择团队')
  }
  return teamId
}

export function normalizeConversation(raw = {}) {
  return {
    id: raw.id ?? raw.conversationId ?? null,
    type: raw.type || TEAM,
    teamId: raw.teamId ?? null,
    projectId: raw.projectId ?? null,
    name: raw.name || raw.teamName || '',
    avatar: raw.avatar || raw.teamAvatar || '',
    unreadCount: Number(raw.unreadCount || 0),
    peerUserId: raw.peerUserId ?? null,
    peerUsername: raw.peerUsername || '',
    peerName: raw.peerName || '',
    peerAvatar: raw.peerAvatar || '',
    updateTime: raw.updateTime || null,
  }
}

export function normalizeTeam(raw = {}) {
  return {
    id: raw.id ?? null,
    name: raw.name || '',
    avatar: raw.avatar || '',
    description: raw.description || '',
    ownerUserId: raw.ownerUserId ?? null,
    myRoleCode: raw.myRoleCode || raw.myRole || '',
    myPermissions: Array.isArray(raw.myPermissions) ? raw.myPermissions : [],
    createTime: raw.createTime || null,
  }
}

export function normalizeTeamMember(raw = {}) {
  return {
    userId: raw.userId ?? null,
    username: raw.username || '',
    name: raw.name || '',
    avatar: raw.avatar || '',
    roleCode: raw.roleCode || raw.role || '',
    joinTime: raw.joinTime || null,
  }
}

export function normalizeMessage(raw = {}, overrides = {}) {
  return {
    messageId: raw.messageId ?? raw.id ?? null,
    conversationId: raw.conversationId ?? null,
    senderUserId: raw.senderUserId ?? null,
    senderUsername: raw.senderUsername || '',
    senderName: raw.senderName || '',
    senderAvatar: raw.senderAvatar || '',
    messageType: raw.messageType || 'TEXT',
    content: raw.content || '',
    mentions: Array.isArray(raw.mentions) ? raw.mentions : [],
    fileCard: raw.fileCard || null,
    clientMessageId: raw.clientMessageId || null,
    readByPeer: Boolean(raw.readByPeer),
    readCount: Number(raw.readCount || 0),
    recallByUserId: raw.recallByUserId ?? null,
    recallTime: raw.recallTime || null,
    recallReason: raw.recallReason || '',
    createTime: raw.createTime || new Date().toISOString(),
    status: raw.status || STORED,
    ...overrides,
  }
}

export function compareMessages(left, right) {
  const leftTime = left?.createTime ? new Date(left.createTime).getTime() : 0
  const rightTime = right?.createTime ? new Date(right.createTime).getTime() : 0
  if (leftTime !== rightTime) {
    return leftTime - rightTime
  }
  const leftId = Number(left?.messageId || 0)
  const rightId = Number(right?.messageId || 0)
  if (leftId !== rightId) {
    return leftId - rightId
  }
  return String(left?.clientMessageId || '').localeCompare(String(right?.clientMessageId || ''))
}
