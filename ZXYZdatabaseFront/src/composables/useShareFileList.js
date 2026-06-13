import { ref, watch } from 'vue'

import { fetchPublicShareFiles } from '@/api/share'
import { getErrorMessage } from '@/utils/error'

function getStatusMessage(error) {
  const statusText = error?.data?.statusText
  if (statusText) {
    return statusText
  }

  return getErrorMessage(error, '分享暂时不可访问')
}

/**
 * @typedef {Object} UseShareFileListOptions
 * @property {import('vue').Ref<string>} [shareKey] - 分享链接 key。
 * @property {import('vue').Ref<string>} [currentPath] - 当前浏览路径。
 * @property {import('vue').Ref<boolean>} [canViewContent] - 是否有权查看内容。
 */

/**
 * 分享文件列表组合函数，加载公开分享链接下的文件列表。
 *
 * @param {UseShareFileListOptions} [options={}] - 配置项。
 * @returns {{ fileList: import('vue').Ref<Array>, fileLoading: import('vue').Ref<boolean>, fileListError: import('vue').Ref<string>, loadFiles: Function }} 分享文件列表状态与操作方法。
 */
export function useShareFileList(options = {}) {
  const { shareKey, currentPath, canViewContent } = options

  const fileList = ref([])
  const fileLoading = ref(false)
  const fileListError = ref('')

  async function loadFiles() {
    if (!canViewContent?.value) {
      fileList.value = []
      fileListError.value = ''
      return
    }

    fileLoading.value = true
    fileListError.value = ''

    try {
      const response = await fetchPublicShareFiles(shareKey?.value, currentPath?.value)
      fileList.value = response?.data || []
    } catch (error) {
      fileList.value = []
      fileListError.value = getStatusMessage(error)
    } finally {
      fileLoading.value = false
    }
  }

  watch(
    () => [shareKey?.value, currentPath?.value, canViewContent?.value].join('|'),
    () => {
      loadFiles()
    },
    { immediate: true },
  )

  return {
    fileList,
    fileLoading,
    fileListError,
    loadFiles,
  }
}
