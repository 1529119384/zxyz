import { ElMessage } from 'element-plus'
import { nextTick, ref } from 'vue'

import { handleBusinessError } from '@/utils/error'

import { useChatFileCardActions } from './useChatFileCardActions'
import { useChatMessageModel } from './useChatMessageModel'
import { useChatMembers } from './useChatMembers'
import { useChatProjectCreateRequests } from './useChatProjectCreateRequests'

export function useChatPageActions(options) {
  const {
    chatStore,
    teamStore,
    router,
    currentUserStore,
    currentUserId,
    activeConversation,
    moreDrawerVisible,
    scrollToBottom,
    resetForConversation,
    filePickerVisible,
  } = options

  const { displayName, displaySearchContent, formatTime } = useChatMessageModel({ currentUserId })

  const { fileCardTitle, handleFileCardAction } = useChatFileCardActions({
    chatStore,
    teamStore,
    router,
    activeConversation,
  })

  const reviewingApplicationId = ref(null)

  const { reviewProjectCreateRequest } = useChatProjectCreateRequests({
    chatStore,
    reviewingApplicationId,
    formatTime,
  })

  const {
    isMemberListConversation,
    visibleGroupMembers,
    canExpandGroupMembers,
    displayMemberName,
    mentionName,
    memberCardVisible,
    memberCardVirtualRef,
    selectedMember,
    openMemberCard,
    expandMembers,
    resetMemberPanel,
    startDirectChatFromMember,
  } = useChatMembers({
    chatStore,
    teamStore,
    activeConversation,
    moreDrawerVisible,
    scrollToBottom,
  })

  async function openConversation(conversation) {
    try {
      await chatStore.openConversation(conversation.id)
      await nextTick()
      scrollToBottom()
    } catch (error) {
      handleBusinessError(error, '打开会话失败')
    }
  }

  function resetConversationState() {
    resetMemberPanel()
    resetForConversation()
  }

  async function submitMessage(draft) {
    const content = draft.trim()
    if (!content || !chatStore.activeConversationId) return false
    try {
      chatStore.sendTextMessage(chatStore.activeConversationId, content, [])
      await nextTick()
      scrollToBottom()
      return true
    } catch (error) {
      handleBusinessError(error, '发送消息失败')
      return false
    }
  }

  async function searchMessages(keyword) {
    if (!chatStore.activeConversationId || !keyword) return
    try {
      await chatStore.searchMessages(chatStore.activeConversationId, keyword)
    } catch (error) {
      handleBusinessError(error, '搜索消息失败')
    }
  }

  function openFilePickerFromMore(isReadonly) {
    if (isReadonly) return
    moreDrawerVisible.value = false
    filePickerVisible.value = true
  }

  async function recallMessageItem(message) {
    try {
      await chatStore.recallConversationMessage(message.messageId, { reason: '用户撤回' })
      ElMessage.success('消息已撤回')
    } catch (error) {
      handleBusinessError(error, '撤回消息失败')
    }
  }

  function handleFilePickerConfirm(items) {
    try {
      chatStore.sendFileCardMessage(chatStore.activeConversationId, items)
      filePickerVisible.value = false
    } catch (error) {
      handleBusinessError(error, '发送文件卡片失败')
    }
  }

  return {
    displayName,
    displaySearchContent,
    formatTime,
    fileCardTitle,
    handleFileCardAction,
    reviewingApplicationId,
    reviewProjectCreateRequest,
    isMemberListConversation,
    visibleGroupMembers,
    canExpandGroupMembers,
    displayMemberName,
    mentionName,
    memberCardVisible,
    memberCardVirtualRef,
    selectedMember,
    openMemberCard,
    expandMembers,
    resetMemberPanel,
    startDirectChatFromMember,
    openConversation,
    resetConversationState,
    submitMessage,
    searchMessages,
    openFilePickerFromMore,
    recallMessageItem,
    handleFilePickerConfirm,
  }
}
