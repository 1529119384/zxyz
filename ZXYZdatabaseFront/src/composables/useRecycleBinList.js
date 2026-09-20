import { fetchRecycleList } from '@/api/files'
import { usePagedList } from '@/composables/usePagedList'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'

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
 * 07-P0-2 后接口改为分页返回，因此这里维护 page / pageSize / total 并对外提供
 * 页码与页长变更的处理函数供 `el-pagination` 绑定；
 * 07-P1-4 起这套骨架改由 usePagedList 提供（此前与 useMyShareList 重复）。
 *
 * @param {UseRecycleBinListOptions} options - 配置项。
 * @returns {{ list: import('vue').Ref<any[]>, loading: import('vue').Ref<boolean>, total: import('vue').Ref<number>, page: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, emptyText: string, refresh: Function, handleCurrentChange: Function, handleSizeChange: Function }} 回收站列表状态与操作方法。
 */
export function useRecycleBinList(options) {
  const { spaceContext, teamId, spaceType, projectId } = options

  const emptyText = '回收站为空'
  const paged = usePagedList(
    async ({ page, pageSize }) => {
      const recycleList = await fetchRecycleList({
        ...resolveSpaceRequestParams(spaceContext, {
          teamId,
          spaceType,
          projectId,
        }),
        page,
        pageSize,
      })

      return {
        list: Array.isArray(recycleList.data) ? recycleList.data : [],
        total: recycleList.total,
      }
    },
    {
      context: 'recycleBin',
      errorMessage: '加载回收站失败，请稍后重试',
    },
  )

  return {
    list: paged.list,
    loading: paged.loading,
    total: paged.total,
    page: paged.page,
    pageSize: paged.pageSize,
    emptyText,
    refresh: paged.refresh,
    handleCurrentChange: paged.handleCurrentChange,
    handleSizeChange: paged.handleSizeChange,
  }
}
