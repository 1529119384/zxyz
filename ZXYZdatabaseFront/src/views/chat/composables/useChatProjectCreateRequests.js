import { ElMessage } from 'element-plus'

import { approveProjectCreateRequest, rejectProjectCreateRequest } from '@/api/project'
import {
  formatProjectQuotaText,
  formatProjectCreateRequestStatusText,
  parseProjectCreateRequestPayload,
} from '@/models/imPresentation'
import { handleBusinessError } from '@/utils/error'

export function useChatProjectCreateRequests({ chatStore, reviewingApplicationId, formatTime }) {
  const imChat = chatStore

  function projectCreateRequestPayload(message = {}) {
    return parseProjectCreateRequestPayload(message)
  }

  function projectCreateRequestStatusText(payload = {}) {
    return formatProjectCreateRequestStatusText(payload, formatTime)
  }

  function formatQuota(value) {
    return formatProjectQuotaText(value)
  }

  async function reviewProjectCreateRequest(message, approved) {
    const payload = projectCreateRequestPayload(message)
    const applicationId = Number(payload.applicationId)
    if (!Number.isSafeInteger(applicationId) || applicationId <= 0) {
      ElMessage.warning('项目组申请数据异常')
      return
    }
    reviewingApplicationId.value = applicationId
    try {
      if (approved) {
        await approveProjectCreateRequest(applicationId)
      } else {
        await rejectProjectCreateRequest(applicationId)
      }
      ElMessage.success(approved ? '已同意项目组申请' : '已拒绝项目组申请')
      await Promise.all([
        imChat
          .loadConversationMessages(message.conversationId || imChat.activeConversationId)
          .catch((err) => console.warn('Operation failed:', err)),
        imChat.loadConversations().catch((err) => console.warn('Operation failed:', err)),
      ])
    } catch (error) {
      handleBusinessError(error, '处理项目组申请失败')
    } finally {
      reviewingApplicationId.value = null
    }
  }

  return {
    projectCreateRequestPayload,
    projectCreateRequestStatusText,
    formatQuota,
    reviewProjectCreateRequest,
  }
}
