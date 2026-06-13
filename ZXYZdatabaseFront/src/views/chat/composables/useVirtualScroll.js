import { nextTick, ref } from 'vue'

export function useVirtualScroll({ messages, chatStore, listRef }) {
  const isNearBottom = ref(true)
  const isLoadingOlder = ref(false)
  const hasMoreOlder = ref(true)

  function scrollToBottom() {
    const list = listRef.value
    if (!list) return
    const lastIndex = messages.value.length - 1
    if (lastIndex < 0) return
    list.scrollToItem(lastIndex, 'end')
    isNearBottom.value = true
  }

  async function loadOlderMessages() {
    if (isLoadingOlder.value || !hasMoreOlder.value) return
    const firstMessage = messages.value[0]
    if (!firstMessage) return

    const anchorId = firstMessage.messageId || firstMessage.clientMessageId
    isLoadingOlder.value = true
    try {
      const beforeLen = messages.value.length
      await chatStore.loadConversationMessages(chatStore.activeConversationId, {
        beforeMessageId: firstMessage.messageId,
        limit: 30,
      })
      if (messages.value.length === beforeLen) {
        hasMoreOlder.value = false
        return
      }
      await nextTick()
      restoreScrollToMessage(anchorId)
    } finally {
      isLoadingOlder.value = false
    }
  }

  function restoreScrollToMessage(messageId) {
    const list = listRef.value
    if (!list || !messageId) return
    const index = messages.value.findIndex(
      (m) => m.messageId === messageId || m.clientMessageId === messageId,
    )
    if (index >= 0) {
      list.scrollToItem(index, 'start')
    }
  }

  function handleNewMessage() {
    if (isNearBottom.value) {
      scrollToBottom()
    }
  }

  function resetForConversation() {
    hasMoreOlder.value = true
    isLoadingOlder.value = false
    isNearBottom.value = true
  }

  return {
    isNearBottom,
    isLoadingOlder,
    hasMoreOlder,
    scrollToBottom,
    loadOlderMessages,
    handleNewMessage,
    resetForConversation,
  }
}
