import { onActivated, onDeactivated, watch } from 'vue'
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

  // ChatHome 被 layout 的 keep-alive 缓存（keepAliveNames 含 'ChatHome'）：切走路由时组件
  // 只是停用、不会卸载，仅靠 setup 里的一次置位会让 chatViewActive 永久为 true ——
  // isConversationEffectivelyVisible() 对激活会话恒真，后台收到的消息被「自动已读」落库，
  // 用户没看的未读被静默清零（J-5）。必须在停用/恢复两个钩子里同步可见性。
  onActivated(() => {
    imChat.setChatViewActive(true)
    refreshVisibleConversation()
  })

  onDeactivated(() => {
    imChat.setChatViewActive(false)
    imChat.clearReadSyncTimers()
  })

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
