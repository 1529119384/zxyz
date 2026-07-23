import { watch } from 'vue'
import { useWindowFocus, useDocumentVisibility } from '@vueuse/core'

import { logger } from '@/utils/logger'

export function useChatVisibilitySync({ imChat }) {
  function refreshVisibleConversation() {
    if (
      imChat.activeConversationId &&
      imChat.isConversationEffectivelyVisible(imChat.activeConversationId)
    ) {
      imChat
        .loadConversationMessages(imChat.activeConversationId)
        .catch((err) => logger.warn('Operation failed:', err))
    }
  }

  const windowFocused = useWindowFocus()
  const visibility = useDocumentVisibility()

  imChat.setChatViewActive(true)
  imChat.setWindowFocused(windowFocused.value)

  watch(windowFocused, (focused) => {
    imChat.setWindowFocused(focused)
    if (focused) {
      refreshVisibleConversation()
    }
  })

  watch(visibility, (state) => {
    const visible = state === 'visible'
    imChat.setWindowFocused(visible)
    if (visible) {
      refreshVisibleConversation()
    }
  })
}
