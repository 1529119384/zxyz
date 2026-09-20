// @ts-check
import { onMounted, ref } from 'vue'

import { normalizePageSize, resolvePageSize } from '@/constants/pagination'
import { handleBusinessError } from '@/utils/error'

/**
 * @typedef {Object} PagedListOptions
 * @property {string} [context] - 页长上下文，见 constants/pagination 的 PAGE_SIZE_BY_CONTEXT。
 * @property {number} [pageSize] - 直接指定页长，优先于 context。
 * @property {string} [errorMessage] - 加载失败时的提示文案。
 * @property {boolean} [immediate] - 是否在挂载时立刻加载一次（默认 false）。
 */

/**
 * @typedef {Object} PagedListLoaderParams
 * @property {number} page - 目标页码（从 1 开始）。
 * @property {number} pageSize - 每页条数。
 */

/**
 * @typedef {Object} PagedListPage
 * @property {any[]} [list] - 当前页记录。
 * @property {number|string|null} [total] - 记录总数。
 */

/**
 * 分页列表的公共骨架：状态（list / loading / total / page / pageSize）+ 加载 + 翻页。
 *
 * 抽出来的原因：useMyShareList 与 useRecycleBinList 此前是**逐字重复**的两份 ——
 * 同样的 loading / try-catch / finally 骨架、同样的 handleCurrentChange /
 * handleSizeChange，连「换页长回第 1 页」这种业务语义都各抄了一遍。
 *
 * 边界（有意不覆盖）：useSpaceFileList 的加载带并发令牌（丢弃过期响应）、预取复用、
 * 以及团队空间根目录的虚拟入口，套不进这个骨架，所以它只复用 constants/pagination 的常量，
 * 不强行改成 usePagedList 的消费者。
 *
 * @param {(params: PagedListLoaderParams) => Promise<PagedListPage|null|undefined>} loader
 *        按 { page, pageSize } 取数，返回 { list, total }。
 * @param {PagedListOptions} [options] - 配置项。
 * @returns {{ list: import('vue').Ref<any[]>, loading: import('vue').Ref<boolean>, total: import('vue').Ref<number>, page: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, refresh: () => Promise<void>, resetPage: () => void, handleCurrentChange: (nextPage: number) => Promise<void>, handleSizeChange: (nextPageSize: number) => Promise<void> }} 列表状态与操作方法。
 */
export function usePagedList(loader, options = {}) {
  const {
    context,
    pageSize: explicitPageSize,
    errorMessage = '加载列表失败，请稍后重试',
    immediate = false,
  } = options

  // 列表内容整表替换、调用方只读，用 any[] 以免与各列表自己的行类型互相打架
  // （Ref<T> 作为泛型参数出现在入参位置时并不互容，写死 unknown[] 反而会逼调用点加断言）。
  const list = ref(/** @type {any[]} */ ([]))
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(normalizePageSize(explicitPageSize ?? resolvePageSize(context)))

  async function refresh() {
    loading.value = true

    try {
      const result = await loader({ page: page.value, pageSize: pageSize.value })

      list.value = Array.isArray(result?.list) ? result.list : []
      total.value = Number(result?.total) || 0
    } catch (error) {
      list.value = []
      total.value = 0
      handleBusinessError(error, errorMessage)
    } finally {
      loading.value = false
    }
  }

  /**
   * @param {number} nextPage - 目标页码。
   */
  async function handleCurrentChange(nextPage) {
    page.value = nextPage
    await refresh()
  }

  /**
   * @param {number} nextPageSize - 目标页长。
   */
  async function handleSizeChange(nextPageSize) {
    pageSize.value = normalizePageSize(nextPageSize)
    // 换页长后停在原页码很可能越界（例如第 5 页 10 条/页 → 50 条/页只剩 1 页），
    // 统一回到第 1 页。
    page.value = 1
    await refresh()
  }

  function resetPage() {
    page.value = 1
  }

  if (immediate) {
    onMounted(refresh)
  }

  return {
    list,
    loading,
    total,
    page,
    pageSize,
    refresh,
    resetPage,
    handleCurrentChange,
    handleSizeChange,
  }
}
