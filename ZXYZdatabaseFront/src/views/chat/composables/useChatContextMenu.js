import { computed, reactive } from 'vue'
import { useEventListener } from '@vueuse/core'

export function useChatContextMenu({ activeConversation, pinnedConversationIds, togglePin }) {
  const contextMenu = reactive({ visible: false, x: 0, y: 0, conversation: null })
  const contextMenuStyle = computed(() => ({
    left: `${contextMenu.x}px`,
    top: `${contextMenu.y}px`,
  }))
  const contextMenuActionText = computed(() =>
    pinnedConversationIds.value.includes(contextMenu.conversation?.id) ? '取消置顶' : '置顶',
  )

  function openConversationMenu(event, conversation) {
    contextMenu.visible = true
    contextMenu.x = event.clientX
    contextMenu.y = event.clientY
    contextMenu.conversation = conversation
  }

  function openMessageMenu(event) {
    if (!activeConversation.value) return
    openConversationMenu(event, activeConversation.value)
  }

  function closeContextMenu() {
    contextMenu.visible = false
  }

  function togglePinConversation() {
    const id = contextMenu.conversation?.id
    if (!id) return
    togglePin(id)
    closeContextMenu()
  }

  useEventListener(window, 'click', closeContextMenu)

  return {
    contextMenu,
    contextMenuStyle,
    contextMenuActionText,
    openConversationMenu,
    openMessageMenu,
    closeContextMenu,
    togglePinConversation,
  }
}
