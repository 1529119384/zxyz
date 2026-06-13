import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { createShare } from '@/api/share'
import { buildShareMessage } from '@/models/share'
import { copyText } from '@/utils/clipboard'
import { handleBusinessError } from '@/utils/error'

/**
 * 创建分享操作 composable，管理创建分享对话框、提交逻辑和分享结果展示。
 *
 * @returns {{ createShareDialogVisible: import('vue').Ref<boolean>, createShareSubmitting: import('vue').Ref<boolean>, shareTargets: import('vue').Ref<Array>, shareSuccessDialogVisible: import('vue').Ref<boolean>, shareSuccessMessage: import('vue').ComputedRef<string>, openCreateShareDialog: Function, handleCreateShareDialogVisibleChange: Function, handleCreateShareSubmit: Function, handleShareSuccessDialogVisibleChange: Function, copyLatestShareMessage: Function }} 创建分享状态与操作方法。
 */
export function useShareCreateAction() {
  const createShareDialogVisible = ref(false)
  const createShareSubmitting = ref(false)
  const shareSuccessDialogVisible = ref(false)
  const shareTargets = ref([])
  const latestShareResult = ref(null)

  const shareSuccessMessage = computed(() => {
    const shareUrl = latestShareResult.value?.shareUrl || ''
    const password = latestShareResult.value?.password || ''
    return shareUrl ? buildShareMessage(shareUrl, password) : ''
  })

  function openCreateShareDialog(items = []) {
    if (!items.length) {
      ElMessage.warning('请选择要分享的文件或文件夹')
      return false
    }

    shareTargets.value = items
    createShareDialogVisible.value = true
    return true
  }

  function handleCreateShareDialogVisibleChange(visible) {
    if (!createShareSubmitting.value) {
      createShareDialogVisible.value = visible
    }

    if (!visible && !createShareSubmitting.value) {
      shareTargets.value = []
    }
  }

  function handleShareSuccessDialogVisibleChange(visible) {
    shareSuccessDialogVisible.value = visible
  }

  async function handleCreateShareSubmit(payload) {
    if (!shareTargets.value.length) {
      ElMessage.warning('请选择要分享的文件或文件夹')
      return
    }

    createShareSubmitting.value = true

    try {
      const response = await createShare({
        fileIds: shareTargets.value.map((item) => item.id),
        ...payload,
      })

      latestShareResult.value = response?.data || null
      createShareDialogVisible.value = false
      shareSuccessDialogVisible.value = true
      ElMessage.success('分享创建成功')
    } catch (error) {
      handleBusinessError(error, '创建分享失败，请稍后重试')
    } finally {
      createShareSubmitting.value = false
    }
  }

  async function copyLatestShareMessage() {
    const message = shareSuccessMessage.value
    if (!message) {
      ElMessage.warning('暂无可复制的分享文案')
      return
    }

    await copyText(message)
    ElMessage.success('分享文案已复制')
  }

  return {
    createShareDialogVisible,
    createShareSubmitting,
    shareTargets,
    shareSuccessDialogVisible,
    shareSuccessMessage,
    openCreateShareDialog,
    handleCreateShareDialogVisibleChange,
    handleCreateShareSubmit,
    handleShareSuccessDialogVisibleChange,
    copyLatestShareMessage,
  }
}
