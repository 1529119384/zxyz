import { ref } from 'vue'

import { fetchFileList } from '@/api/files'
import { fetchTeamProjects } from '@/api/project'
import { normalizePageSize, resolvePageSize } from '@/constants/pagination'
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
 * 分页状态复用 constants/pagination（页长与归一化），但**没有**改成 usePagedList 的消费者：
 * 本函数的 refresh 带并发令牌（丢弃过期响应）、支持预取复用、还要给团队空间根目录拼虚拟入口，
 * 塞进通用骨架只会把抽象做坏。对外行为与 usePagedList 保持一致（换页长回第 1 页）。
 *
 * @param {UseSpaceFileListOptions} options - 配置项。
 * @returns {{ list: import('vue').Ref<Array>, loading: import('vue').Ref<boolean>, currentPage: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, total: import('vue').Ref<number>, resetPage: Function, refresh: Function, handleCurrentChange: Function, handleSizeChange: Function }} 空间文件列表状态与操作方法。
 */
export function useSpaceFileList(options) {
  const { currentId, sortState, spaceContext, teamId, spaceType, projectId } = options

  const list = ref([])
  const loading = ref(false)
  const currentPage = ref(1)
  const pageSize = ref(resolvePageSize('spaceFiles'))
  const total = ref(0)
  let latestRefreshToken = 0
  let forcedRefreshToken = 0

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
    const { prefetchedList = null, force = false } = refreshOptions
    const refreshToken = ++latestRefreshToken
    if (force) {
      forcedRefreshToken = refreshToken
    }
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
        if (refreshToken !== latestRefreshToken && refreshToken !== forcedRefreshToken) return
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
      if (refreshToken !== latestRefreshToken && refreshToken !== forcedRefreshToken) return
      // fetchFileList 已把后端信封统一拍平成 data 数组（裸数组或 { list, total } 皆然），
      // 所以这里必须「数组优先」解析。旧实现用 `typeof data === 'object'` 判断分页信封，
      // 但数组的 typeof 同样是 'object' → 误把数组当成 { list } 信封 → 取到 data.list
      // (= undefined) → entries 恒为 [] → 列表被清空。
      // 这正是「新建文件夹/重命名/删除成功后提示成功但列表不更新」的根因。
      const payload = fileList?.data
      const entries = Array.isArray(payload)
        ? payload
        : Array.isArray(payload?.list)
          ? payload.list
          : []
      // total 可能来自 fetchFileList 提升的信封同级字段，或旧式 { list, total } 信封内部。
      const rawTotal = fileList?.total ?? (Array.isArray(payload) ? undefined : payload?.total)
      if (rawTotal != null) {
        total.value = Number(rawTotal)
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
      if (refreshToken !== latestRefreshToken && refreshToken !== forcedRefreshToken) return
      list.value = []
      handleBusinessError(error, '加载文件列表失败，请稍后重试')
    } finally {
      if (refreshToken === latestRefreshToken || refreshToken === forcedRefreshToken) {
        loading.value = false
      }
    }
  }

  function resetPage() {
    currentPage.value = 1
  }

  async function handleCurrentChange(nextPage) {
    currentPage.value = nextPage
    await refresh()
  }

  async function handleSizeChange(nextPageSize) {
    pageSize.value = normalizePageSize(nextPageSize)
    // 换页长后停在原页码很可能越界（例如第 5 页 10 条/页 → 50 条/页只剩 1 页），
    // 统一回到第 1 页。此前 FileExplorer 直接绑 refresh，少了这一步。
    currentPage.value = 1
    await refresh()
  }

  return {
    list,
    loading,
    currentPage,
    pageSize,
    total,
    resetPage,
    refresh,
    handleCurrentChange,
    handleSizeChange,
  }
}
