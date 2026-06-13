import { computed } from 'vue'

import { DIRECT, SYSTEM, TEAM_NOTIFICATION } from '@/constants/conversationTypes'
import { formatWsStatus, getConversationTypeText } from '@/models/imPresentation'

export function useChatConversationView({ chatStore, activeConversation, pinnedConversationIds }) {
  const imChat = chatStore
  const wsStatusText = computed(() => formatWsStatus(imChat.wsStatus))
  const orderedConversations = computed(() => {
    const pinned = new Set(pinnedConversationIds.value)
    const withKey = imChat.conversations.map((item) => ({
      item,
      pinned: pinned.has(item.id) ? 1 : 0,
      time: item.updateTime ? new Date(item.updateTime).getTime() : 0,
    }))
    withKey.sort((a, b) => {
      if (a.pinned !== b.pinned) return b.pinned - a.pinned
      return b.time - a.time
    })
    return withKey.map((entry) => entry.item)
  })
  const headerTitle = computed(() =>
    activeConversation.value ? conversationTitle(activeConversation.value) : '聊天会话',
  )

  function conversationAvatar(conversation) {
    if (conversation.type === DIRECT) {
      return conversation.peerAvatar
    }
    return conversation.avatar
  }

  function conversationTitle(conversation) {
    if (conversation.type === SYSTEM) return '系统消息'
    if (conversation.type === TEAM_NOTIFICATION) return '团队消息'
    if (conversation.type === DIRECT) {
      return conversation.peerName || conversation.peerUsername || `用户 ${conversation.peerUserId}`
    }
    return conversation.name || `团队 ${conversation.teamId}`
  }

  function conversationTypeText(conversation) {
    return getConversationTypeText(conversation)
  }

  return {
    wsStatusText,
    orderedConversations,
    headerTitle,
    conversationAvatar,
    conversationTitle,
    conversationTypeText,
  }
}
