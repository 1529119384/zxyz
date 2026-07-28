import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { searchFiles } from '@/api/files'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { handleBusinessError } from '@/utils/error'

const EMPTY_SEARCH_RESULT = {
  total: 0,
  list: [],
}

/**
 * @typedef {Object} UseFileSearchOptions
 * @property {import('vue').Ref<string>} searchText - 搜索关键词。
 * @property {import('vue').Ref<boolean>} [enabled] - 是否启用搜索。
 * @property {Object} spaceContext - 空间上下文。
 * @property {string} teamId - 团队 ID。
 * @property {number} spaceType - 空间类型。
 * @property {string} [projectId] - 项目 ID。
 */

/**
 * 文件搜索组合函数，提供防抖搜索、结果管理和搜索模式切换。
 *
 * @param {UseFileSearchOptions} options - 配置项。
 * @returns {{ list: import('vue').ComputedRef<Array>, loading: import('vue').Ref<boolean>, results: import('vue').Ref<Object>, total: import('vue').ComputedRef<number>, isSearchMode: import('vue').ComputedRef<boolean>, search: Function, resetResults: Function }} 文件搜索状态与操作方法。
 */
export function useFileSearch(options) {
  const { searchText, enabled, spaceContext, teamId, spaceType, projectId } = options

  const loading = ref(false)
  const results = ref({ ...EMPTY_SEARCH_RESULT })
  const list = computed(() => results.value.list)
  const total = computed(() => results.value.total || 0)
  const page = ref(1)
  const pageSize = ref(20)
  const isSearchMode = computed(() => Boolean(enabled?.value) && Boolean(searchText.value.trim()))
  const spaceParams = computed(() =>
    resolveSpaceRequestParams(spaceContext, {
      teamId,
      spaceType,
      projectId,
    }),
  )

  const FILE_SEARCH_DEBOUNCE_MS = 500
  let debounceTimer = null
  let latestSearchToken = 0
  let activeAbortController = null

  function resetResults() {
    results.value = { ...EMPTY_SEARCH_RESULT }
    page.value = 1
  }

  async function refresh() {
    if (searchText.value.trim()) {
      await search(searchText.value.trim())
    }
  }

  async function search(keyword) {
    const normalizedKeyword = keyword?.trim()
    if (!normalizedKeyword || !enabled?.value) {
      resetResults()
      return
    }

    if (activeAbortController) {
      activeAbortController.abort()
    }
    activeAbortController = new AbortController()

    const searchToken = ++latestSearchToken
    loading.value = true

    try {
      const response = await searchFiles(normalizedKeyword, page.value, pageSize.value, {
        ...spaceParams.value,
        signal: activeAbortController.signal,
      })

      // 只接受最后一次关键词对应的返回，避免慢请求覆盖当前搜索结果。
      if (searchToken !== latestSearchToken || normalizedKeyword !== searchText.value.trim()) {
        return
      }

      const data = response?.data || EMPTY_SEARCH_RESULT
      results.value = {
        total: Number(data.total) || 0,
        list: Array.isArray(data.list) ? data.list : [],
      }
    } catch (error) {
      if (searchToken !== latestSearchToken) {
        return
      }

      // 请求被主动中止（如组件卸载或新的搜索开始），不视为错误。
      if (error?.code === 'ERR_CANCELED') {
        return
      }

      handleBusinessError(error, '搜索失败，请稍后重试')
    } finally {
      if (searchToken === latestSearchToken) {
        loading.value = false
      }
    }
  }

  watch(
    [searchText, enabled, spaceParams],
    ([newKeyword, isEnabled]) => {
      clearTimeout(debounceTimer)
      latestSearchToken += 1
      loading.value = false

      const normalizedKeyword = newKeyword?.trim()
      if (!isEnabled || !normalizedKeyword) {
        resetResults()
        return
      }

      page.value = 1
      debounceTimer = setTimeout(() => {
        search(normalizedKeyword)
      }, FILE_SEARCH_DEBOUNCE_MS)
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    clearTimeout(debounceTimer)
    latestSearchToken += 1
    if (activeAbortController) {
      activeAbortController.abort()
      activeAbortController = null
    }
  })

  return {
    list,
    loading,
    results,
    total,
    page,
    pageSize,
    isSearchMode,
    search,
    refresh,
    resetResults,
  }
}
