import { ref } from 'vue'

import { fetchFileList } from '@/api/files'
import { fetchTeamProjects } from '@/api/project'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { SPACE_TYPE } from '@/models/space'
import { handleBusinessError } from '@/utils/error'
import {
  createProjectFolderEntry,
  createProjectRootEntry,
  isProjectRootId,
} from '@/utils/projectVirtualFolder'

/**
 * @typedef {Object} UseSpaceFileListOptions
 * @property {import('vue').Ref<string|number>} currentId - 当前目录 ID。
 * @property {import('vue').Ref<Object>} [sortState] - 排序状态。
 * @property {Object} spaceContext - 空间上下文。
 * @property {string} teamId - 团队 ID。
 * @property {number} spaceType - 空间类型。
 * @property {string} [projectId] - 项目 ID。
 */

/**
 * 空间文件列表组合函数，负责加载和刷新当前目录下的文件列表。
 *
 * @param {UseSpaceFileListOptions} options - 配置项。
 * @returns {{ list: import('vue').Ref<Array>, loading: import('vue').Ref<boolean>, currentPage: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, total: import('vue').Ref<number>, resetPage: Function, refresh: Function }} 空间文件列表状态与操作方法。
 */
export function useSpaceFileList(options) {
  const { currentId, sortState, spaceContext, teamId, spaceType, projectId } = options

  const list = ref([])
  const loading = ref(false)
  const currentPage = ref(1)
  const pageSize = ref(50)
  const total = ref(0)
  let latestRefreshToken = 0

  function resolveSpaceParams() {
    return resolveSpaceRequestParams(spaceContext, {
      teamId,
      spaceType,
      projectId,
    })
  }

  /**
   * 刷新文件列表。
   *
   * @param {Object} [refreshOptions={}] - 刷新选项。
   * @param {Array} [refreshOptions.prefetchedList] - 预取的文件列表，跳过网络请求。
   */
  async function refresh(refreshOptions = {}) {
    const { prefetchedList = null } = refreshOptions
    const refreshToken = ++latestRefreshToken
    loading.value = true

    try {
      if (Array.isArray(prefetchedList)) {
        // 路径解析已经预取目标目录列表，这里直接复用，避免同一路径重复请求。
        list.value = prefetchedList
        return
      }

      const spaceParams = resolveSpaceParams()

      if (spaceParams.spaceType === SPACE_TYPE.TEAM && isProjectRootId(currentId.value)) {
        const response = await fetchTeamProjects(spaceParams.teamId)
        if (refreshToken !== latestRefreshToken) return
        const projects = Array.isArray(response?.data) ? response.data : []
        list.value = projects.map(createProjectFolderEntry)
        return
      }

      const fileList = await fetchFileList(currentId.value, {
        ...(sortState?.value || {}),
        ...spaceParams,
        page: currentPage.value,
        pageSize: pageSize.value,
      })
      if (refreshToken !== latestRefreshToken) return
      const paged = fileList.data && typeof fileList.data === 'object'
      const entries = Array.isArray(paged ? fileList.data.list : fileList.data)
        ? paged
          ? fileList.data.list
          : fileList.data
        : []
      if (paged && fileList.data.total != null) {
        total.value = fileList.data.total
      }

      if (
        spaceParams.spaceType === SPACE_TYPE.TEAM &&
        Number(currentId.value) === -1 &&
        spaceParams.teamId
      ) {
        // 团队空间根目录需要拼入项目组虚拟入口，真实文件列表仍保持后端返回顺序。
        list.value = [createProjectRootEntry(spaceParams.teamId), ...entries]
        return
      }

      list.value = entries
    } catch (error) {
      if (refreshToken !== latestRefreshToken) return
      list.value = []
      handleBusinessError(error, '加载文件列表失败，请稍后重试')
    } finally {
      if (refreshToken === latestRefreshToken) {
        loading.value = false
      }
    }
  }

  function resetPage() {
    currentPage.value = 1
  }

  return {
    list,
    loading,
    currentPage,
    pageSize,
    total,
    resetPage,
    refresh,
  }
}
