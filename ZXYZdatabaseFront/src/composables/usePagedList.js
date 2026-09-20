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
 * @property {number|string|null} [pageSize] - 后端实际生效的页长（可能被上限钳制）；缺失时保持本地值。
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
  let latestRequestToken = 0

  /**
   * 用后端回传的 pageSize 校准本地页长。
   *
   * 为什么需要：请求里的 pageSize 可能被后端的页长上限钳过
   * （`PageResult.normalizePageSize`）。前端若不采纳生效值，就会出现
   * 「前端按 200 算总页数、后端按 100 分页」⇒ 每翻一页跳掉一批数据，中后段永远看不到。
   *
   * **只采纳 pageSize，不采纳 page**：pageSize 是唯一会被后端静默改变的量
   * （钳到上限）；而 page 只会被 `< 1 → 1` 归一化，前端本就不会发出小于 1 的页码，
   * 后端的回声值恒等于请求值。若连 page 一起采纳，反而会在「响应回声落后于本地状态」时
   * 把用户刚翻到的页码拽回去（实测会把 handleCurrentChange(4) 拉回第 1 页）。
   *
   * 只在后端真的回传了合法值时才覆盖；缺字段（旧后端 / 裸数组）时保持本地值。
   *
   * 只由**当前这次请求**触发校准（refresh 用请求令牌把过期响应挡在门外）：
   * 「第 4 页还没回来就换页长」时两个请求同时在飞，迟到的旧响应回声会把用户刚选的页长拽回去。
   *
   * @param {PagedListPage|null|undefined} result - loader 的返回值。
   */
  function adoptServerPaging(result) {
    const serverPageSize = Number(result?.pageSize)
    if (Number.isFinite(serverPageSize) && serverPageSize >= 1) {
      pageSize.value = normalizePageSize(serverPageSize)
    }
  }

  async function refresh() {
    // 请求令牌：迟到的旧响应不得覆盖新状态。page / pageSize 会被用户连续改动
    // （点了第 4 页又马上换页长），两个请求同时在飞是常态，谁先返回不一定谁最新。
    // 与 useSpaceFileList 的 latestRefreshToken、useFileSearch 的 latestSearchToken 同法。
    const requestToken = ++latestRequestToken
    loading.value = true

    try {
      const result = await loader({ page: page.value, pageSize: pageSize.value })
      if (requestToken !== latestRequestToken) return

      list.value = Array.isArray(result?.list) ? result.list : []
      total.value = Number(result?.total) || 0
      adoptServerPaging(result)
    } catch (error) {
      if (requestToken !== latestRequestToken) return

      list.value = []
      total.value = 0
      handleBusinessError(error, errorMessage)
    } finally {
      if (requestToken === latestRequestToken) {
        loading.value = false
      }
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
