import { ref } from 'vue'

import { fetchRecycleList } from '@/api/files'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { handleBusinessError } from '@/utils/error'

/**
 * @typedef {Object} UseRecycleBinListOptions
 * @property {Object} spaceContext - 空间上下文。
 * @property {string} teamId - 团队 ID。
 * @property {number} spaceType - 空间类型。
 * @property {string} [projectId] - 项目 ID。
 */

/**
 * 回收站列表组合函数，加载和展示已删除的文件列表。
 *
 * @param {UseRecycleBinListOptions} options - 配置项。
 * @returns {{ list: import('vue').Ref<Array>, loading: import('vue').Ref<boolean>, emptyText: string, refresh: Function }} 回收站列表状态与操作方法。
 */
export function useRecycleBinList(options) {
  const { spaceContext, teamId, spaceType, projectId } = options

  const list = ref([])
  const loading = ref(false)
  const emptyText = '回收站为空'

  async function refresh() {
    loading.value = true

    try {
      const recycleList = await fetchRecycleList(
        resolveSpaceRequestParams(spaceContext, {
          teamId,
          spaceType,
          projectId,
        }),
      )
      list.value = Array.isArray(recycleList.data) ? recycleList.data : []
    } catch (error) {
      list.value = []
      handleBusinessError(error, '加载回收站失败，请稍后重试')
    } finally {
      loading.value = false
    }
  }

  return {
    list,
    loading,
    emptyText,
    refresh,
  }
}
