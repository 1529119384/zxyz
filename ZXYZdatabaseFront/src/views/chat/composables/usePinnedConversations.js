import { useLocalStorage } from '@vueuse/core'

export function usePinnedConversations() {
  const pinnedConversationIds = useLocalStorage('pinnedConversationIds', [])

  function togglePin(conversationId) {
    if (!conversationId) {
      return
    }

    pinnedConversationIds.value = pinnedConversationIds.value.includes(conversationId)
      ? pinnedConversationIds.value.filter((item) => item !== conversationId)
      : [conversationId, ...pinnedConversationIds.value]
  }

  return {
    pinnedConversationIds,
    togglePin,
  }
}
