import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import { deleteFilesForever, restoreFiles } from '@/api/files'
import { useDeleteDialog } from '@/composables/useDeleteDialog'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'
import { resolveActionTargets } from '@/utils/selection'

/**
 * @typedef {Object} UseRecycleBinActionsOptions
 * @property {Function} [onRefresh] - 刷新列表的回调。
 * @property {Function} [onClearSelection] - 清除选中状态的回调。
 */

/**
 * 回收站操作 composable，管理回收站中的恢复和彻底删除操作。
 *
 * @param {UseRecycleBinActionsOptions} options - 配置项。
 * @returns {{ selectedRows: import('vue').Ref<Array>, deleteDialogVisible: import('vue').Ref<boolean>, deleteSubmitting: import('vue').Ref<boolean>, deleteDialogOptions: import('vue').Ref<Object>, openDeleteDialog: Function, closeDeleteDialog: Function, handleDeleteDialogVisibleChange: Function, handleDeleteForeverSubmit: Function, refresh: Function, handleSelectionChange: Function, handleBatchRestore: Function, handleBatchDeleteForever: Function, handleContextAction: Function, handleRowAction: Function }} 回收站操作状态与方法。
 */
export function useRecycleBinActions(options) {
  const { onRefresh, onClearSelection } = options
  // 由 composable 统一持有选中态，避免页面和行为层分散管理同一份状态。
  const selectedRows = ref([])

  function refresh() {
    return onRefresh?.()
  }

  function clearSelection() {
    onClearSelection?.()
  }

  function handleSelectionChange(payload) {
    selectedRows.value = payload?.rows || []
  }

  function getFileIds(rows) {
    return rows.map((item) => item.id)
  }

  const deleteAction = useDeleteDialog({
    tipText: '删除后将无法恢复，请谨慎操作。',
    confirmText: '确认彻底删除',
    getBatchMessage: () => '选中的文件将被彻底删除，删除后不可恢复。是否继续？',
    deleteRequest: (rows) => {
      const fileIds = getFileIds(rows)
      return deleteFilesForever(fileIds)
    },
    getSuccessMessage: (rows) => (rows.length > 1 ? '批量彻底删除成功' : '彻底删除成功'),
    getFallbackMessage: (rows) =>
      rows.length > 1 ? '批量彻底删除失败，请稍后重试' : '彻底删除失败，请稍后重试',
    onSuccess: async () => {
      await refresh()
      clearSelection()
    },
  })

  async function executeRestore(rows) {
    const fileIds = getFileIds(rows)
    if (!fileIds.length) {
      return
    }

    try {
      await restoreFiles(fileIds)
      ElMessage.success(fileIds.length > 1 ? '批量取消删除成功' : '取消删除成功')
      await refresh()
      clearSelection()
    } catch (error) {
      logger.error('恢复回收站文件失败:', error)
      handleBusinessError(
        error,
        fileIds.length > 1 ? '批量取消删除失败，请稍后重试' : '取消删除失败，请稍后重试',
      )
    }
  }

  async function handleBatchRestore() {
    await executeRestore(selectedRows.value)
  }

  async function handleBatchDeleteForever() {
    deleteAction.openDeleteDialog(selectedRows.value)
  }

  async function handleContextAction(payload) {
    const targets = resolveActionTargets(payload)

    if (payload.action === 'refresh') {
      await refresh()
      return
    }

    if (payload.action === 'restore' || payload.action === 'restoreSelected') {
      await executeRestore(targets)
      return
    }

    if (payload.action === 'deleteForever' || payload.action === 'deleteForeverSelected') {
      deleteAction.openDeleteDialog(targets)
    }
  }

  async function handleRowAction({ action, row }) {
    if (action === 'restore') {
      await executeRestore([row])
      return
    }

    if (action === 'deleteForever') {
      deleteAction.openDeleteDialog([row])
    }
  }

  return {
    ...deleteAction,
    selectedRows,
    handleDeleteForeverSubmit: deleteAction.handleDeleteSubmit,
    refresh,
    handleSelectionChange,
    handleBatchRestore,
    handleBatchDeleteForever,
    handleContextAction,
    handleRowAction,
  }
}
