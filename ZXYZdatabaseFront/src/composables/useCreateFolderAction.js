import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import { createFolder } from '@/api/files'
import { normalizeFolderCreateResult } from '@/models/upload'
import { FILE_TYPE, resolveUniqueName } from '@/utils/nameConflict'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'

const DEFAULT_FOLDER_NAME = '新建文件夹'

/**
 * @typedef {Object} UseCreateFolderActionOptions
 * @property {Object} [spaceContext] - 空间上下文，用于解析创建请求的空间参数。
 * @property {Function} [getParentId] - 获取父目录 ID。
 * @property {Function} [getTeamId] - 获取当前团队 ID。
 * @property {Function} [getSpaceType] - 获取当前空间类型。
 * @property {Function} [getProjectId] - 获取当前项目 ID。
 * @property {Function} [getSiblingEntries] - 获取同级目录条目列表，用于冲突名称检测。
 * @property {Function} [onSuccess] - 创建成功后的回调，参数为创建结果对象。
 */

/**
 * 创建文件夹操作 composable，管理创建文件夹对话框及提交逻辑。
 *
 * @param {UseCreateFolderActionOptions} options - 配置项。
 * @returns {{ createFolderDialogVisible: import('vue').Ref<boolean>, createFolderSubmitting: import('vue').Ref<boolean>, createFolderDefaultName: import('vue').Ref<string>, openCreateFolderDialog: Function, closeCreateFolderDialog: Function, handleCreateFolder: Function }} 创建文件夹状态与操作方法。
 */
export function useCreateFolderAction(options) {
  const {
    spaceContext,
    getParentId,
    getTeamId,
    getSpaceType,
    getProjectId,
    getSiblingEntries,
    onSuccess,
  } = options

  const createFolderDialogVisible = ref(false)
  const createFolderSubmitting = ref(false)
  const createFolderDefaultName = ref(DEFAULT_FOLDER_NAME)

  function buildDefaultFolderName() {
    const siblingEntries = getSiblingEntries?.() || []
    return resolveUniqueName(DEFAULT_FOLDER_NAME, siblingEntries, FILE_TYPE.FOLDER)
  }

  function openCreateFolderDialog(defaultName = '') {
    createFolderDefaultName.value = defaultName || buildDefaultFolderName()
    createFolderDialogVisible.value = true
  }

  function closeCreateFolderDialog() {
    if (createFolderSubmitting.value) {
      return
    }

    createFolderDialogVisible.value = false
  }

  async function handleCreateFolder(folderName) {
    createFolderSubmitting.value = true

    try {
      const parentId = getParentId?.()
      const spaceParams = resolveSpaceRequestParams(spaceContext, {
        teamId: getTeamId?.(),
        spaceType: getSpaceType?.(),
        projectId: getProjectId?.(),
      })
      const response = await createFolder({
        folderName,
        parentId,
        ...spaceParams,
      })
      const result = normalizeFolderCreateResult(response?.data, folderName, parentId)

      createFolderDialogVisible.value = false
      ElMessage.success(result.renamed ? `已创建：${result.finalName}` : '创建成功')
      await onSuccess?.(result)
      return result
    } catch (error) {
      logger.error('创建文件夹失败:', error)
      handleBusinessError(error, '创建失败，请稍后重试')
      return null
    } finally {
      createFolderSubmitting.value = false
    }
  }

  return {
    createFolderDialogVisible,
    createFolderSubmitting,
    createFolderDefaultName,
    openCreateFolderDialog,
    closeCreateFolderDialog,
    handleCreateFolder,
  }
}
