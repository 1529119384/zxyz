import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'

const DEFAULT_DIALOG_OPTIONS = {
  fileName: '当前文件',
  type: 1,
  message: '',
  tip: '',
  confirmText: '确认删除',
}

/**
 * @typedef {Object} UseDeleteDialogOptions
 * @property {string} [tipText] - 弹窗提示文案。
 * @property {string} [confirmText='确认删除'] - 确认按钮文案。
 * @property {Function} [getBatchMessage] - 批量删除时的提示信息，参数为目标条目数组。
 * @property {Function} deleteRequest - 执行删除的请求函数，参数为目标条目数组。
 * @property {Function} getSuccessMessage - 删除成功提示文案，参数为目标条目数组。
 * @property {Function} getFallbackMessage - 删除失败提示文案，参数为目标条目数组。
 * @property {Function} [onSuccess] - 删除成功后的回调，参数为目标条目数组。
 */

/**
 * 通用删除确认弹窗 composable，管理删除对话框状态及确认删除逻辑。
 *
 * @param {UseDeleteDialogOptions} options - 配置项。
 * @returns {{ deleteDialogVisible: import('vue').Ref<boolean>, deleteSubmitting: import('vue').Ref<boolean>, deleteDialogOptions: import('vue').Ref<Object>, openDeleteDialog: Function, closeDeleteDialog: Function, handleDeleteDialogVisibleChange: Function, handleDeleteSubmit: Function }} 删除对话框状态与操作方法。
 */
export function useDeleteDialog(options) {
  const {
    tipText,
    confirmText = DEFAULT_DIALOG_OPTIONS.confirmText,
    getBatchMessage,
    deleteRequest,
    getSuccessMessage,
    getFallbackMessage,
    onSuccess,
  } = options

  const deleteDialogVisible = ref(false)
  const deleteSubmitting = ref(false)
  const pendingDeleteItems = ref([])
  const deleteDialogOptions = ref({ ...DEFAULT_DIALOG_OPTIONS })

  function openDeleteDialog(items = []) {
    const targetItems = Array.isArray(items) ? items.filter(Boolean) : []
    if (!targetItems.length) {
      return false
    }

    const firstItem = targetItems[0]
    const isBatch = targetItems.length > 1

    pendingDeleteItems.value = targetItems

    // 将待删项与弹窗文案同时缓存，确认时不再依赖界面二次拼装。
    deleteDialogOptions.value = {
      ...DEFAULT_DIALOG_OPTIONS,
      fileName: firstItem?.fileName || DEFAULT_DIALOG_OPTIONS.fileName,
      type: firstItem?.type ?? DEFAULT_DIALOG_OPTIONS.type,
      message: isBatch ? getBatchMessage?.(targetItems) || '' : '',
      tip: tipText || DEFAULT_DIALOG_OPTIONS.tip,
      confirmText,
    }
    deleteDialogVisible.value = true
    return true
  }

  function closeDeleteDialog() {
    if (deleteSubmitting.value) {
      return
    }

    deleteDialogVisible.value = false
    pendingDeleteItems.value = []
  }

  function handleDeleteDialogVisibleChange(visible) {
    if (visible) {
      deleteDialogVisible.value = true
      return
    }

    // 关闭弹窗时同步清理缓存，避免上一次操作残留到下一次确认。
    closeDeleteDialog()
  }

  async function handleDeleteSubmit() {
    const targetItems = pendingDeleteItems.value.filter(Boolean)
    if (!targetItems.length) {
      return
    }

    deleteSubmitting.value = true

    try {
      await deleteRequest(targetItems)
      deleteDialogVisible.value = false
      pendingDeleteItems.value = []
      ElMessage.success(getSuccessMessage(targetItems))
      await onSuccess?.(targetItems)
    } catch (error) {
      logger.error('删除操作失败:', error)
      handleBusinessError(error, getFallbackMessage(targetItems))
    } finally {
      deleteSubmitting.value = false
    }
  }

  return {
    deleteDialogVisible,
    deleteSubmitting,
    deleteDialogOptions,
    openDeleteDialog,
    closeDeleteDialog,
    handleDeleteDialogVisibleChange,
    handleDeleteSubmit,
  }
}
