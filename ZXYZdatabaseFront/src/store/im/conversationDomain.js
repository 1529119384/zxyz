import {
  createDirectConversation,
  fetchConversation,
  fetchMyConversations,
  fetchTeamConversation,
  updateConversationRead,
} from '@/api/im'
import { DIRECT } from '@/constants/conversationTypes'
import { normalizePositiveId } from '@/utils/id'
import { getErrorCode } from '@/utils/errorModel'

import {
  compareMessages,
  IM_ACCESS_DENIED_CODES,
  normalizeConversation,
  READ_SYNC_DELAY_MS,
  requireTeamId,
} from './normalizers'

export function createConversationDomain(state, deps = {}) {
  const { selectedTeamId, conversations, activeConversationId, lastWsError } = state
  const {
    messageDomain,
    resolveTeamScopedParams,
    setSelectedTeam = (teamId) => {
      selectedTeamId.value = normalizePositiveId(teamId)
    },
    handleTeamAccessRevoked,
  } = deps
  const readSyncTimers = new Map()

  function isConversationEffectivelyVisible(conversationId) {
    return Boolean(
      state.chatViewActive.value &&
      state.windowFocused.value &&
      Number(activeConversationId.value) === Number(conversationId),
    )
  }

  function setChatViewActive(active) {
    state.chatViewActive.value = Boolean(active)
  }

  function setWindowFocused(focused) {
    state.windowFocused.value = Boolean(focused)
  }

  function clearActiveConversation() {
    activeConversationId.value = null
  }

  function upsertConversation(rawConversation) {
    const normalized = normalizeConversation(rawConversation)
    if (!normalized.id) {
      return null
    }
    const index = conversations.value.findIndex((item) => item.id === normalized.id)
    if (index >= 0) {
      const merged = {
        ...conversations.value[index],
        ...normalized,
      }
      conversations.value.splice(index, 1, merged)
      return merged
    }
    conversations.value.unshift(normalized)
    return normalized
  }

  function updateConversationUnread(conversationId, unreadValue) {
    const index = conversations.value.findIndex((item) => item.id === conversationId)
    if (index < 0) {
      return
    }
    conversations.value.splice(index, 1, {
      ...conversations.value[index],
      unreadCount: Math.max(0, Number(unreadValue || 0)),
    })
  }

  function touchConversation(conversationId) {
    const index = conversations.value.findIndex((item) => item.id === conversationId)
    if (index < 0) {
      return
    }
    conversations.value.splice(index, 1, {
      ...conversations.value[index],
      updateTime: new Date().toISOString(),
    })
  }

  function isConversationAccessError(error) {
    return IM_ACCESS_DENIED_CODES.has(getErrorCode(error))
  }

  function removeConversationLocally(conversationId) {
    if (!conversationId) {
      return
    }
    conversations.value = conversations.value.filter(
      (item) => Number(item.id) !== Number(conversationId),
    )
    messageDomain.removeConversationBuckets(conversationId)
    if (Number(activeConversationId.value) === Number(conversationId)) {
      activeConversationId.value = null
    }
  }

  function handleConversationAccessRevoked({ conversationId = null, teamId = null } = {}) {
    if (conversationId) {
      removeConversationLocally(conversationId)
    }
    if (teamId) {
      handleTeamAccessRevoked(teamId)
      // 必须先判断激活会话是否属于该团队，再过滤：过滤后数组已不含该团队会话，
      // 若放到 filter 之后判断（旧实现），activeConversationId 会残留指向已被移除的会话，
      // 导致 UI 停留在空白聊天窗。id 比较统一用 Number 转换，避免数字/字符串类型不一致。
      const activeConversation = conversations.value.find(
        (item) => Number(item.id) === Number(activeConversationId.value),
      )
      const shouldClearActive = Boolean(
        activeConversation && Number(activeConversation.teamId) === Number(teamId),
      )
      conversations.value = conversations.value.filter(
        (item) => Number(item.teamId) !== Number(teamId),
      )
      const allowedConversationIds = new Set(conversations.value.map((item) => Number(item.id)))
      messageDomain.removeTeamBuckets(allowedConversationIds)
      if (shouldClearActive) {
        activeConversationId.value = null
      }
    }
  }

  async function loadConversations(teamId = selectedTeamId.value) {
    const response = await fetchMyConversations(resolveTeamScopedParams(teamId))
    conversations.value = Array.isArray(response?.data)
      ? response.data.map((item) => normalizeConversation(item))
      : []
    messageDomain.pruneOrphanBuckets()
    return conversations.value
  }

  async function ensureConversation(conversationId) {
    let conversation = conversations.value.find((item) => item.id === conversationId) || null
    if (conversation) {
      return conversation
    }
    const response = await fetchConversation(conversationId)
    conversation = response?.data ? upsertConversation(response.data) : null
    return conversation
  }

  async function loadTeamConversation(teamId) {
    const normalizedTeamId = requireTeamId(teamId)
    const response = await fetchTeamConversation(normalizedTeamId)
    const conversation = upsertConversation(
      response?.data
        ? {
            id: response.data.conversationId,
            type: response.data.type,
            teamId: response.data.teamId,
            name: response.data.teamName,
            avatar: response.data.teamAvatar,
            unreadCount: 0,
            updateTime: null,
          }
        : null,
    )
    return conversation
  }

  async function createDirectConversationAndOpen(teamId, targetUserId) {
    const normalizedTeamId = requireTeamId(teamId)
    const normalizedTargetUserId = normalizePositiveId(targetUserId)
    const existing = conversations.value.find(
      (conversation) =>
        conversation.type === DIRECT &&
        normalizePositiveId(conversation.teamId) === normalizedTeamId &&
        normalizePositiveId(conversation.peerUserId) === normalizedTargetUserId,
    )
    if (existing?.id) {
      activeConversationId.value = existing.id
      updateConversationUnread(existing.id, 0)
      messageDomain
        .loadConversationMessages(existing.id)
        .then(() => scheduleReadSync(existing.id))
        .catch((error) => {
          lastWsError.value = error
        })
      return existing
    }

    const response = await createDirectConversation({ teamId: normalizedTeamId, targetUserId })
    const conversation = upsertConversation(response?.data || null)
    if (conversation?.id) {
      activeConversationId.value = conversation.id
      messageDomain
        .loadConversationMessages(conversation.id)
        .then(() => scheduleReadSync(conversation.id))
        .catch((error) => {
          lastWsError.value = error
        })
    }
    return conversation
  }

  async function updateReadPosition(conversationId, lastReadMessageId) {
    if (!conversationId || !lastReadMessageId) {
      return null
    }
    const response = await updateConversationRead(conversationId, { lastReadMessageId })
    updateConversationUnread(conversationId, 0)
    return response?.data || null
  }

  function scheduleReadSync(conversationId = activeConversationId.value) {
    if (!conversationId || !isConversationEffectivelyVisible(conversationId)) {
      return
    }
    const bucket = messageDomain.getConversationMessages(conversationId)
    const lastVisibleMessage = [...bucket]
      .filter((item) => item.messageId)
      .sort(compareMessages)
      .at(-1)
    if (!lastVisibleMessage?.messageId) {
      return
    }

    if (readSyncTimers.has(conversationId)) {
      clearTimeout(readSyncTimers.get(conversationId))
    }
    const timer = setTimeout(() => {
      readSyncTimers.delete(conversationId)
      updateReadPosition(conversationId, lastVisibleMessage.messageId).catch((error) => {
        lastWsError.value = error
      })
    }, READ_SYNC_DELAY_MS)
    readSyncTimers.set(conversationId, timer)
  }

  function clearReadSyncTimers() {
    readSyncTimers.forEach((timer) => clearTimeout(timer))
    readSyncTimers.clear()
  }

  async function openConversation(conversationId) {
    const conversation = await ensureConversation(conversationId)
    activeConversationId.value = conversation?.id || null
    if (activeConversationId.value) {
      messageDomain
        .loadConversationMessages(activeConversationId.value)
        .then(() => scheduleReadSync(activeConversationId.value))
        .catch((error) => {
          lastWsError.value = error
        })
    }
    messageDomain.evictStaleBuckets()
    return conversation
  }

  async function openTeamChat(teamId) {
    const normalizedTeamId = requireTeamId(teamId)
    setSelectedTeam(normalizedTeamId)
    const conversation = await loadTeamConversation(normalizedTeamId)
    if (conversation?.id) {
      await openConversation(conversation.id)
    }
    return conversation
  }

  async function openDirectChat(conversationId) {
    return openConversation(conversationId)
  }

  function cleanup() {
    clearReadSyncTimers()
    messageDomain.cleanup()
  }

  return {
    isConversationEffectivelyVisible,
    setChatViewActive,
    setWindowFocused,
    clearActiveConversation,
    upsertConversation,
    ensureMessageBucket: messageDomain.ensureMessageBucket,
    getConversationMessages: messageDomain.getConversationMessages,
    mergeConversationMessage: messageDomain.mergeConversationMessage,
    replaceConversationMessages: messageDomain.replaceConversationMessages,
    updatePendingMessageStatus: messageDomain.updatePendingMessageStatus,
    updateConversationUnread,
    touchConversation,
    isConversationAccessError,
    handleConversationAccessRevoked,
    loadConversations,
    ensureConversation,
    loadTeamConversation,
    createDirectConversationAndOpen,
    loadConversationMessages: messageDomain.loadConversationMessages,
    syncConversationMessages: messageDomain.syncConversationMessages,
    updateReadPosition,
    scheduleReadSync,
    clearReadSyncTimers,
    cleanup,
    openConversation,
    openTeamChat,
    openDirectChat,
    searchMessages: messageDomain.searchMessages,
    resolveFileCardMessage: messageDomain.resolveFileCardMessage,
    recallConversationMessage: messageDomain.recallConversationMessage,
    markBucketChanged: messageDomain.markBucketChanged,
  }
}
