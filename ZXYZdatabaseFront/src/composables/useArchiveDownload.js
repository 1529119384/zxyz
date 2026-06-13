import { ref } from 'vue'

import { runArchiveDownload } from '@/utils/archive/archiveDownloadRunner'

/**
 * @typedef {Object} UseArchiveDownloadOptions
 * @property {Function} [resolveOpenState] - 解析打包下载的打开状态，接收 payload，返回 { opened, context?, defaultName? }。
 * @property {Function} [buildRunnerOptions] - 构建下载运行器参数，接收 (archiveName, context)，返回运行器配置。
 * @property {Function} [resetOnSuccess] - 下载成功后的重置回调。
 */

/**
 * 打包下载通用逻辑，管理命名弹窗状态与下载提交流程。
 *
 * @param {UseArchiveDownloadOptions} [options={}] - 配置项。
 * @returns {{ archiveDialogVisible: import('vue').Ref<boolean>, archiveSubmitting: import('vue').Ref<boolean>, archiveDefaultName: import('vue').Ref<string>, openArchiveNameDialog: Function, closeArchiveNameDialog: Function, handleArchiveDownloadSubmit: Function }} 打包下载状态与操作方法。
 */
export function useArchiveDownload(options = {}) {
  const { resolveOpenState, buildRunnerOptions, resetOnSuccess } = options

  const archiveDialogVisible = ref(false)
  const archiveSubmitting = ref(false)
  const archiveDefaultName = ref('archive')
  const archiveContext = ref(null)

  function openArchiveNameDialog(payload) {
    const state = resolveOpenState?.(payload)
    if (!state?.opened) {
      return false
    }

    archiveContext.value = state.context ?? null
    archiveDefaultName.value = state.defaultName || 'archive'
    archiveDialogVisible.value = true
    return true
  }

  function closeArchiveNameDialog() {
    if (archiveSubmitting.value) {
      return
    }

    archiveDialogVisible.value = false
  }

  async function handleArchiveDownloadSubmit(archiveName) {
    const runnerOptions = buildRunnerOptions?.(archiveName, archiveContext.value)
    if (!runnerOptions) {
      return
    }

    const originalOnSuccess = runnerOptions.onSuccess

    await runArchiveDownload({
      ...runnerOptions,
      setSubmitting: (value) => {
        archiveSubmitting.value = value
        runnerOptions.setSubmitting?.(value)
      },
      // 成功收尾统一放在通用层，避免各业务场景重复关闭弹窗和重置上下文。
      onSuccess: () => {
        originalOnSuccess?.()
        archiveDialogVisible.value = false
        archiveContext.value = null
        resetOnSuccess?.()
      },
    })
  }

  return {
    archiveDialogVisible,
    archiveSubmitting,
    archiveDefaultName,
    openArchiveNameDialog,
    closeArchiveNameDialog,
    handleArchiveDownloadSubmit,
  }
}
