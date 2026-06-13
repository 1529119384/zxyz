import { ElMessage } from 'element-plus'
import { unref } from 'vue'

import { useFileDownload } from '@/composables/useFileDownload'
import { copyText } from '@/utils/clipboard'
import { FILE_CONTEXT_ACTIONS, FILE_ROW_ACTIONS } from '@/models/fileActions'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'
import { isProjectFolderEntry, isProjectRootEntry } from '@/utils/projectVirtualFolder'
import { resolveActionTargets } from '@/utils/selection'

const contextActionFallbackMessages = {
  [FILE_CONTEXT_ACTIONS.DOWNLOAD]: '下载失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.COPY_DOWNLOAD_LINK]: '复制下载链接失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.COPY_FILE_NAME]: '复制文件名称失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.ARCHIVE_DOWNLOAD]: '打包下载失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.REFRESH]: '刷新失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.RENAME]: '重命名失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.DELETE]: '删除失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.MOVE]: '移动失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.COPY]: '复制失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.SHARE_FILE]: '创建分享失败，请稍后重试',
  [FILE_CONTEXT_ACTIONS.SEND_TO_CONVERSATION]: '发送到会话失败，请稍后重试',
}

function resolveOptionValue(value) {
  return typeof value === 'function' ? value() : unref(value)
}

/**
 * @typedef {Object} UseFileSpaceActionsOptions
 * @property {Object} router - Vue Router 实例。
 * @property {import('vue').Ref<Object|null>} fileShowRef - 文件展示组件模板引用，提供 openFolder 方法。
 * @property {import('vue').Ref<Object|null>} uploaderRef - 上传组件模板引用，提供 openFileUpload / openFolderUpload 方法。
 * @property {import('vue').Ref<boolean>|Function} canWrite - 当前空间是否可写，可以是 Ref 或返回布尔值的函数。
 * @property {Function} [refreshFileList] - 刷新文件列表。
 * @property {Function} [openCreateFolderDialog] - 打开创建文件夹对话框。
 * @property {Function} [openCreateProjectDialog] - 打开创建项目对话框。
 * @property {Function} [openProjectSettings] - 打开项目设置，接收目标项参数。
 * @property {Function} [openArchiveNameDialog] - 打开打包下载命名对话框，接收目标列表。
 * @property {Function} [openRenameDialog] - 打开重命名对话框，接收单个目标项。
 * @property {Function} [openDeleteDialog] - 打开删除确认对话框，接收目标列表。
 * @property {Function} [openMoveCopyDialog] - 打开移动/复制对话框，接收 (mode, payload)。
 * @property {Function} [openCreateShareDialog] - 打开创建分享对话框，接收目标列表。
 * @property {Function} [openSendToConversation] - 打开发送到会话，接收目标列表。
 * @property {Function} [openProjectSpace] - 打开项目空间，接收目标项。
 */

/**
 * 文件空间右键与行操作的业务分发层。
 * 页面只负责传入依赖，具体动作分派集中在这里维护。
 *
 * @param {UseFileSpaceActionsOptions} [options={}] - 配置项。
 * @returns {{ handleContextAction: Function, handleRowAction: Function }} 文件空间操作分发方法。
 */
export function useFileSpaceActions(options = {}) {
  const {
    router,
    fileShowRef,
    uploaderRef,
    canWrite,
    refreshFileList,
    openCreateFolderDialog,
    openCreateProjectDialog,
    openProjectSettings,
    openArchiveNameDialog,
    openRenameDialog,
    openDeleteDialog,
    openMoveCopyDialog,
    openCreateShareDialog,
    openSendToConversation,
    openProjectSpace,
  } = options
  const fileDownloadAction = useFileDownload({ router })

  function canWriteCurrentSpace() {
    return Boolean(resolveOptionValue(canWrite))
  }

  function openUploader(action) {
    if (!canWriteCurrentSpace()) {
      return
    }

    if (action === FILE_CONTEXT_ACTIONS.UPLOAD_FILE) {
      uploaderRef?.value?.openFileUpload?.()
      return
    }

    uploaderRef?.value?.openFolderUpload?.()
  }

  async function handleContextAction(payload = {}) {
    const action = payload.action

    try {
      if (
        action === FILE_CONTEXT_ACTIONS.UPLOAD_FILE ||
        action === FILE_CONTEXT_ACTIONS.UPLOAD_FOLDER
      ) {
        openUploader(action)
        return
      }

      const targets = resolveActionTargets(payload)

      switch (action) {
        case FILE_CONTEXT_ACTIONS.CREATE_FOLDER:
          openCreateFolderDialog?.()
          return
        case FILE_CONTEXT_ACTIONS.CREATE_PROJECT_GROUP:
          openCreateProjectDialog?.()
          return
        case FILE_CONTEXT_ACTIONS.PROJECT_SETTINGS:
          openProjectSettings?.(payload.targetItem)
          return
        case FILE_CONTEXT_ACTIONS.REFRESH:
          await refreshFileList?.()
          return
        case FILE_CONTEXT_ACTIONS.OPEN:
          if (isProjectRootEntry(payload.targetItem)) {
            fileShowRef?.value?.openFolder?.(payload.targetItem)
            return
          }
          if (isProjectFolderEntry(payload.targetItem)) {
            openProjectSpace?.(payload.targetItem)
          }
          return
        case FILE_CONTEXT_ACTIONS.PREVIEW:
          ElMessage.info('预览功能暂未接入')
          return
        case FILE_CONTEXT_ACTIONS.DOWNLOAD:
          if (payload.targetItem) {
            await fileDownloadAction.downloadFile(payload.targetItem)
          }
          return
        case FILE_CONTEXT_ACTIONS.COPY_DOWNLOAD_LINK:
          if (payload.targetItem) {
            await fileDownloadAction.copyDownloadLink(payload.targetItem)
          }
          return
        case FILE_CONTEXT_ACTIONS.COPY_FILE_NAME: {
          const names = targets.map((item) => item.fileName).filter(Boolean)
          if (names.length) {
            await copyText(names.join('\n'))
            ElMessage.success('文件名称已复制')
          }
          return
        }
        case FILE_CONTEXT_ACTIONS.ARCHIVE_DOWNLOAD:
          openArchiveNameDialog?.(targets)
          return
        case FILE_CONTEXT_ACTIONS.OPEN_IN_NEW_TAB:
          fileDownloadAction.openFolderInNewTab(payload.targetPath)
          return
        case FILE_CONTEXT_ACTIONS.RENAME:
          if (targets.length !== 1) {
            ElMessage.warning('请选择一个文件或文件夹进行重命名')
            return
          }

          openRenameDialog?.(targets[0])
          return
        case FILE_CONTEXT_ACTIONS.DELETE:
          openDeleteDialog?.(targets)
          return
        case FILE_CONTEXT_ACTIONS.MOVE:
          openMoveCopyDialog?.('move', payload)
          return
        case FILE_CONTEXT_ACTIONS.COPY:
          openMoveCopyDialog?.('copy', payload)
          return
        case FILE_CONTEXT_ACTIONS.SHARE_FILE:
          openCreateShareDialog?.(targets)
          return
        case FILE_CONTEXT_ACTIONS.SEND_TO_CONVERSATION:
          await openSendToConversation?.(targets)
          return
        default:
          logger.debug('未处理的文件空间右键动作', {
            action,
            targetItem: payload.targetItem,
            selectedItems: payload.selectedItems,
            anchorId: payload.anchorId,
          })
      }
    } catch (error) {
      logger.error('执行右键操作失败:', error)
      handleBusinessError(error, contextActionFallbackMessages[action] || '操作失败，请稍后重试')
    }
  }

  function handleRowAction({ action, row } = {}) {
    if (action === FILE_ROW_ACTIONS.OPEN_PROJECT) {
      openProjectSpace?.(row)
      return
    }

    logger.debug('未处理的文件空间行操作', { action, row })
  }

  return {
    handleContextAction,
    handleRowAction,
  }
}
