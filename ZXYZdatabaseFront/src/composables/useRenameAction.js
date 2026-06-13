import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'

import { renameFile } from '@/api/files'
import { splitFileNameParts } from '@/models/file'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'

/**
 * @typedef {Object} UseRenameActionOptions
 * @property {Function} [onSuccess] - 重命名成功后的回调，参数为被重命名的条目对象。
 */

/**
 * 重命名操作 composable，管理重命名对话框及提交逻辑。
 *
 * @param {UseRenameActionOptions} options - 配置项。
 * @returns {{ renameDialogVisible: import('vue').Ref<boolean>, renameSubmitting: import('vue').Ref<boolean>, renameDefaultName: import('vue').ComputedRef<string>, renameTargetType: import('vue').ComputedRef<number>, openRenameDialog: Function, closeRenameDialog: Function, handleRenameDialogVisibleChange: Function, handleRenameSubmit: Function }} 重命名状态与操作方法。
 */
export function useRenameAction(options) {
  const { onSuccess } = options

  const renameDialogVisible = ref(false)
  const renameSubmitting = ref(false)
  const pendingRenameItem = ref(null)

  const renameTargetType = computed(() => pendingRenameItem.value?.type ?? 1)
  const renameDefaultName = computed(() => {
    const targetItem = pendingRenameItem.value
    if (!targetItem) {
      return ''
    }

    const { baseName, fullName } = splitFileNameParts(targetItem)
    return targetItem.type === 0 ? fullName : baseName
  })

  function openRenameDialog(item) {
    if (!item || item.id === undefined || item.type === undefined) {
      return false
    }

    pendingRenameItem.value = item
    renameDialogVisible.value = true
    return true
  }

  function closeRenameDialog() {
    if (renameSubmitting.value) {
      return
    }

    renameDialogVisible.value = false
    pendingRenameItem.value = null
  }

  function handleRenameDialogVisibleChange(visible) {
    if (visible) {
      renameDialogVisible.value = true
      return
    }

    closeRenameDialog()
  }

  async function handleRenameSubmit(newName) {
    const targetItem = pendingRenameItem.value
    if (!targetItem) {
      return
    }

    renameSubmitting.value = true

    try {
      await renameFile({
        fileId: targetItem.id,
        newName,
      })
      renameDialogVisible.value = false
      pendingRenameItem.value = null
      ElMessage.success('重命名成功')
      await onSuccess?.(targetItem)
    } catch (error) {
      logger.error('重命名失败:', error)
      handleBusinessError(error, '重命名失败，请稍后重试')
    } finally {
      renameSubmitting.value = false
    }
  }

  return {
    renameDialogVisible,
    renameSubmitting,
    renameDefaultName,
    renameTargetType,
    openRenameDialog,
    closeRenameDialog,
    handleRenameDialogVisibleChange,
    handleRenameSubmit,
  }
}
