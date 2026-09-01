import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import { copyFiles, moveFiles } from '@/api/files'
import { useBatchFeedback } from '@/composables/useBatchFeedback'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'
import { resolveActionTargets } from '@/utils/selection'

/**
 * @typedef {Object} UseMoveCopyActionOptions
 * @property {Function} [onSuccess] - 移动/复制成功后的回调，参数为请求载荷对象。
 * @property {Object} [spaceContext] - 空间上下文，用于解析请求的空间参数。
 * @property {Function} [getTeamId] - 获取当前团队 ID。
 * @property {Function} [getSpaceType] - 获取当前空间类型。
 * @property {Function} [getProjectId] - 获取当前项目 ID。
 */

/**
 * 移动/复制操作 composable，管理移动复制对话框及提交逻辑。
 *
 * @param {UseMoveCopyActionOptions} options - 配置项。
 * @returns {{ moveCopyDialogVisible: import('vue').Ref<boolean>, moveCopyDialogMode: import('vue').Ref<string>, moveCopyItems: import('vue').Ref<Array>, moveCopySourcePath: import('vue').Ref<string>, resetMoveCopyDialog: Function, openMoveCopyDialog: Function, handleMoveCopyDialogVisibleChange: Function, handleMoveCopySubmit: Function }} 移动复制状态与操作方法。
 */
export function useMoveCopyAction(options) {
  const { onSuccess, spaceContext, getTeamId, getSpaceType, getProjectId } = options
  const { showFeedback } = useBatchFeedback()

  const moveCopyDialogVisible = ref(false)
  const moveCopyDialogMode = ref('move')
  const moveCopyItems = ref([])
  const moveCopySourcePath = ref('')

  function resetMoveCopyDialog() {
    moveCopyDialogVisible.value = false
    moveCopyDialogMode.value = 'move'
    moveCopyItems.value = []
    moveCopySourcePath.value = ''
  }

  function openMoveCopyDialog(mode, payload = {}) {
    const targets = resolveActionTargets(payload)

    if (!targets.length) {
      ElMessage.warning(`请选择要${mode === 'copy' ? '复制' : '移动'}的文件或文件夹`)
      return false
    }

    moveCopyDialogMode.value = mode
    moveCopyItems.value = targets
    moveCopySourcePath.value = payload.currentPath || ''
    moveCopyDialogVisible.value = true
    return true
  }

  function handleMoveCopyDialogVisibleChange(visible) {
    if (!visible) {
      resetMoveCopyDialog()
      return
    }

    moveCopyDialogVisible.value = true
  }

  async function handleMoveCopySubmit(payload) {
    const isCopy = payload.mode === 'copy'
    const successMessage = isCopy ? '复制成功' : '移动成功'
    const fallbackMessage = isCopy ? '复制失败，请稍后重试' : '移动失败，请稍后重试'

    try {
      const spaceParams = resolveSpaceRequestParams(
        spaceContext,
        {
          teamId: getTeamId?.(),
          spaceType: getSpaceType?.(),
          projectId: getProjectId?.(),
        },
        {
          teamId: payload.teamId,
          spaceType: payload.spaceType,
          projectId: payload.projectId,
        },
      )
      const requestPayload = {
        ...payload,
        ...spaceParams,
      }
      // moveFiles/copyFiles 返回完整信封 { code, msg, data }，业务结果在 data 中。
      const { data: result } = isCopy
        ? await copyFiles(requestPayload)
        : await moveFiles(requestPayload)

      showFeedback(result, {
        actionName: isCopy ? '复制' : '移动',
        fallbackMessage: successMessage,
      })
      // 跳转目标目录属于页面行为，交给页面层在成功回调中决定，方便后续复用到其他场景。
      await onSuccess?.(requestPayload)
    } catch (error) {
      logger.error(`${isCopy ? '复制' : '移动'}文件失败:`, error)
      handleBusinessError(error, fallbackMessage)
    }
  }

  return {
    moveCopyDialogVisible,
    moveCopyDialogMode,
    moveCopyItems,
    moveCopySourcePath,
    resetMoveCopyDialog,
    openMoveCopyDialog,
    handleMoveCopyDialogVisibleChange,
    handleMoveCopySubmit,
  }
}
