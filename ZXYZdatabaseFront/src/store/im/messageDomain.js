import { triggerRef } from 'vue'

import {
  fetchConversationMessages,
  recallMessage,
  resolveMessageFileCard,
  searchConversationMessages,
} from '@/api/im'
import { STORED } from '@/constants/messageStatus'

import { compareMessages, normalizeMessage } from './normalizers'

const MAX_CACHED_CONVERSATIONS = 20
const MAX_RECENT_ACCESS_ENTRIES = 1000

export function createMessageDomain(state, deps = {}) {
  const {
    messagesByConversation,
    activeConversationId,
    chatViewActive,
    windowFocused,
    searchResults,
    lastWsError,
  } = state
  const { getConversations, isConversationEffectivelyVisible, scheduleReadSync } = deps
  const recentAccess = new Map()

  function touchAccess(conversationId) {
    if (!conversationId) return
    recentAccess.set(Number(conversationId), Date.now())
    if (recentAccess.size > MAX_RECENT_ACCESS_ENTRIES) {
      const oldest = [...recentAccess.entries()]
        .sort(([, a], [, b]) => a - b)
        .slice(0, recentAccess.size - MAX_RECENT_ACCESS_ENTRIES)
      for (const [key] of oldest) {
        recentAccess.delete(key)
      }
    }
  }

  function evictStaleBuckets() {
    const bucketKeys = Object.keys(messagesByConversation.value)
    if (bucketKeys.length <= MAX_CACHED_CONVERSATIONS) return

    const activeId = Number(activeConversationId.value)
    const sorted = bucketKeys
      .map((key) => Number(key))
      .filter((id) => {
        if (id === activeId) return false
        const bucket = messagesByConversation.value[id]
        if (bucket?.some((msg) => msg.status === 'SENDING')) return false
        return true
      })
      .sort((a, b) => (recentAccess.get(a) || 0) - (recentAccess.get(b) || 0))

    const evictCount = bucketKeys.length - MAX_CACHED_CONVERSATIONS
    const toEvict = sorted.slice(0, evictCount)
    if (!toEvict.length) return

    toEvict.forEach((id) => {
      delete messagesByConversation.value[id]
      recentAccess.delete(id)
    })
    triggerRef(messagesByConversation)
  }

  function pruneOrphanBuckets() {
    const conversations = getConversations()
    const allowedIds = new Set(conversations.map((item) => Number(item.id)))
    const currentKeys = Object.keys(messagesByConversation.value)
    const orphans = currentKeys.filter((key) => !allowedIds.has(Number(key)))
    if (!orphans.length) return
    orphans.forEach((id) => {
      delete messagesByConversation.value[id]
      recentAccess.delete(Number(id))
    })
    triggerRef(messagesByConversation)
  }

  function ensureMessageBucket(conversationId) {
    if (!conversationId) {
      return []
    }
    if (!messagesByConversation.value[conversationId]) {
      messagesByConversation.value[conversationId] = []
      triggerRef(messagesByConversation)
    }
    return messagesByConversation.value[conversationId]
  }

  function getConversationMessages(conversationId) {
    if (!conversationId) {
      return []
    }
    return messagesByConversation.value[conversationId] || []
  }

  function mergeConversationMessage(conversationId, messageLike) {
    if (!conversationId) {
      return null
    }
    touchAccess(conversationId)
    const normalized = normalizeMessage(messageLike, { conversationId })
    const bucket = ensureMessageBucket(conversationId)
    const index = bucket.findIndex((item) => {
      if (normalized.messageId && item.messageId) {
        return Number(item.messageId) === Number(normalized.messageId)
      }
      return (
        Boolean(normalized.clientMessageId) && normalized.clientMessageId === item.clientMessageId
      )
    })

    if (index >= 0) {
      const preservedFileCard =
        normalized.messageType === 'FILE_CARD' && !normalized.fileCard
          ? bucket[index].fileCard
          : normalized.fileCard
      const merged = {
        ...bucket[index],
        ...normalized,
        fileCard: preservedFileCard,
      }
      bucket.splice(index, 1, merged)
      bucket.sort(compareMessages)
      triggerRef(messagesByConversation)
      return merged
    }

    bucket.push(normalized)
    bucket.sort(compareMessages)
    triggerRef(messagesByConversation)
    return normalized
  }

  function replaceConversationMessages(conversationId, messages = []) {
    touchAccess(conversationId)
    const normalizedMessages = Array.isArray(messages)
      ? messages.map((item) => normalizeMessage(item, { conversationId, status: STORED }))
      : []
    messagesByConversation.value[conversationId] = normalizedMessages.sort(compareMessages)
    triggerRef(messagesByConversation)
    return messagesByConversation.value[conversationId]
  }

  function updatePendingMessageStatus(conversationId, clientMessageId, patch = {}) {
    const bucket = ensureMessageBucket(conversationId)
    const index = bucket.findIndex((item) => item.clientMessageId === clientMessageId)
    if (index < 0) {
      return null
    }
    const updated = {
      ...bucket[index],
      ...patch,
    }
    bucket.splice(index, 1, updated)
    bucket.sort(compareMessages)
    triggerRef(messagesByConversation)
    return updated
  }

  function markBucketChanged() {
    triggerRef(messagesByConversation)
  }

  function removeConversationBuckets(conversationId) {
    if (!conversationId) return
    delete messagesByConversation.value[conversationId]
    triggerRef(messagesByConversation)
    recentAccess.delete(Number(conversationId))
  }

  function removeTeamBuckets(allowedConversationIds) {
    for (const key of Object.keys(messagesByConversation.value)) {
      if (!allowedConversationIds.has(Number(key))) {
        delete messagesByConversation.value[key]
      }
    }
    triggerRef(messagesByConversation)
    for (const id of recentAccess.keys()) {
      if (!allowedConversationIds.has(id)) recentAccess.delete(id)
    }
  }

  async function loadConversationMessages(conversationId, params = {}) {
    const response = await fetchConversationMessages(conversationId, params)
    const items = Array.isArray(response?.data) ? response.data : []
    const hasAfterCursor = Boolean(params.afterMessageId || params.afterTime)
    const hasBeforeCursor = Boolean(params.beforeMessageId)

    if (!hasAfterCursor && !hasBeforeCursor) {
      const replaced = replaceConversationMessages(conversationId, items)
      if (isConversationEffectivelyVisible(conversationId)) {
        scheduleReadSync(conversationId)
      }
      return replaced
    }

    items.forEach((item) => {
      mergeConversationMessage(conversationId, { ...item, status: STORED })
    })
    return getConversationMessages(conversationId)
  }

  async function syncConversationMessages(conversationId = activeConversationId.value) {
    if (!conversationId) {
      return []
    }
    const bucket = getConversationMessages(conversationId)
    const lastStoredMessage = [...bucket]
      .filter((item) => item.messageId && item.createTime)
      .sort(compareMessages)
      .at(-1)

    return loadConversationMessages(conversationId, {
      afterMessageId: lastStoredMessage?.messageId,
      afterTime: lastStoredMessage?.createTime,
      limit: 100,
    })
  }

  async function searchMessages(conversationId, keyword) {
    const response = await searchConversationMessages(conversationId, { keyword, limit: 50 })
    searchResults.value = Array.isArray(response?.data)
      ? response.data.map((item) => normalizeMessage(item, { conversationId }))
      : []
    return searchResults.value
  }

  async function resolveFileCardMessage(messageId) {
    const response = await resolveMessageFileCard(messageId, { messageId })
    return response?.data || null
  }

  async function recallConversationMessage(messageId, payload = {}) {
    const response = await recallMessage(messageId, payload)
    return response?.data
  }

  function cleanup() {
    recentAccess.clear()
  }

  return {
    ensureMessageBucket,
    getConversationMessages,
    mergeConversationMessage,
    replaceConversationMessages,
    updatePendingMessageStatus,
    markBucketChanged,
    removeConversationBuckets,
    removeTeamBuckets,
    pruneOrphanBuckets,
    evictStaleBuckets,
    loadConversationMessages,
    syncConversationMessages,
    searchMessages,
    resolveFileCardMessage,
    recallConversationMessage,
    cleanup,
  }
}
