import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import { useChatStore } from '@/store/chat'
import { handleBusinessError } from '@/utils/error'

/**
 * @typedef {Object} UseSendToConversationOptions
 * @property {Object} [chatStore] - Pinia 聊天 Store 实例，默认使用 useChatStore()。
 */

/**
 * 发送文件卡片到 IM 会话，管理会话选择器的显隐和待发送文件列表。
 *
 * @param {UseSendToConversationOptions} [options={}] - 配置项。
 * @returns {{ conversationPickerVisible: import('vue').Ref<boolean>, handleConversationPickerVisibleChange: Function, openSendToConversation: Function, handleConversationSelect: Function }} 发送到会话状态与操作方法。
 */
export function useSendToConversation({ chatStore = useChatStore() } = {}) {
  const imChat = chatStore
  const conversationPickerVisible = ref(false)
  const pendingConversationShareItems = ref([])

  function resetPendingItems() {
    pendingConversationShareItems.value = []
  }

  function handleConversationPickerVisibleChange(visible) {
    conversationPickerVisible.value = visible
    if (!visible) {
      resetPendingItems()
    }
  }

  async function openSendToConversation(items = []) {
    if (!items.length) {
      ElMessage.warning('请选择要发送的文件或文件夹')
      return false
    }

    try {
      await imChat.loadConversations()
      pendingConversationShareItems.value = items
      conversationPickerVisible.value = true
      return true
    } catch (error) {
      handleBusinessError(error, '加载会话失败')
      return false
    }
  }

  function handleConversationSelect(conversation) {
    if (!pendingConversationShareItems.value.length) {
      ElMessage.warning('请选择要发送的文件或文件夹')
      return false
    }

    try {
      imChat.sendFileCardMessage(conversation.id, pendingConversationShareItems.value)
      conversationPickerVisible.value = false
      resetPendingItems()
      ElMessage.success('文件卡片已发送')
      return true
    } catch (error) {
      handleBusinessError(error, '发送到会话失败')
      return false
    }
  }

  return {
    conversationPickerVisible,
    handleConversationPickerVisibleChange,
    openSendToConversation,
    handleConversationSelect,
  }
}
