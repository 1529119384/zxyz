import { ref } from 'vue'

import { fetchRecycleList } from '@/api/files'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { handleBusinessError } from '@/utils/error'

/** 与后端 PageResult.DEFAULT_PAGE_SIZE 保持一致。 */
const DEFAULT_PAGE_SIZE = 20

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
 * 07-P0-2 后接口改为分页返回，因此这里同时维护 page / pageSize / total，
 * 并对外提供页码与页长变更的处理函数供 `el-pagination` 绑定。
 *
 * @param {UseRecycleBinListOptions} options - 配置项。
 * @returns {{ list: import('vue').Ref<Array>, loading: import('vue').Ref<boolean>, total: import('vue').Ref<number>, page: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, emptyText: string, refresh: Function, handleCurrentChange: Function, handleSizeChange: Function }} 回收站列表状态与操作方法。
 */
export function useRecycleBinList(options) {
  const { spaceContext, teamId, spaceType, projectId } = options

  const list = ref([])
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(DEFAULT_PAGE_SIZE)
  const emptyText = '回收站为空'

  async function refresh() {
    loading.value = true

    try {
      const recycleList = await fetchRecycleList({
        ...resolveSpaceRequestParams(spaceContext, {
          teamId,
          spaceType,
          projectId,
        }),
        page: page.value,
        pageSize: pageSize.value,
      })
      list.value = Array.isArray(recycleList.data) ? recycleList.data : []
      total.value = Number(recycleList.total) || 0
    } catch (error) {
      list.value = []
      total.value = 0
      handleBusinessError(error, '加载回收站失败，请稍后重试')
    } finally {
      loading.value = false
    }
  }

  async function handleCurrentChange(nextPage) {
    page.value = nextPage
    await refresh()
  }

  async function handleSizeChange(nextPageSize) {
    pageSize.value = nextPageSize
    // 换页长后停在原页码很可能越界（例如第 5 页 10 条/页 → 50 条/页只剩 1 页），
    // 统一回到第 1 页，与 useMyShareList 的做法一致。
    page.value = 1
    await refresh()
  }

  return {
    list,
    loading,
    total,
    page,
    pageSize,
    emptyText,
    refresh,
    handleCurrentChange,
    handleSizeChange,
  }
}
