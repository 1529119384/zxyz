import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'

vi.mock('@/api/files', () => ({
  searchFiles: vi.fn(),
}))
vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({
    teamId: null,
    spaceType: 1,
    projectId: null,
  })),
}))
vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

import { searchFiles } from '@/api/files'
import { handleBusinessError } from '@/utils/error'
import { useFileSearch } from '@/composables/useFileSearch'

describe('useFileSearch', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('初始状态：无搜索结果且非搜索模式', () => {
    const { list, total, isSearchMode, loading, results } = useFileSearch({
      searchText: ref(''),
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    expect(list.value).toEqual([])
    expect(total.value).toBe(0)
    expect(isSearchMode.value).toBe(false)
    expect(loading.value).toBe(false)
  })

  it('输入关键词后 500ms 防抖触发搜索', async () => {
    const mockResult = {
      data: {
        total: 2,
        list: [
          { id: 1, fileName: '文件A' },
          { id: 2, fileName: '文件B' },
        ],
      },
    }
    searchFiles.mockResolvedValue(mockResult)

    const searchText = ref('')
    const { list, total, isSearchMode } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    // 防抖前不触发搜索
    expect(searchFiles).not.toHaveBeenCalled()
    expect(isSearchMode.value).toBe(false)

    searchText.value = '文件'
    await vi.advanceTimersByTimeAsync(500)

    expect(searchFiles).toHaveBeenCalledWith('文件', 1, 20, expect.any(Object))
    expect(list.value).toEqual(mockResult.data.list)
    expect(total.value).toBe(2)
    expect(isSearchMode.value).toBe(true)
  })

  it('防抖时间内多次输入只触发最后一次搜索', async () => {
    searchFiles.mockResolvedValue({ data: { total: 0, list: [] } })

    const searchText = ref('')
    useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    searchText.value = 'abc'
    await vi.advanceTimersByTimeAsync(200)

    searchText.value = 'abcdef'
    await vi.advanceTimersByTimeAsync(200)

    // 仅第一次输入已过 500ms 但被第二次输入取消了
    expect(searchFiles).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300)

    expect(searchFiles).toHaveBeenCalledTimes(1)
    expect(searchFiles).toHaveBeenCalledWith('abcdef', 1, 20, expect.any(Object))
  })

  it('清空关键词后退出搜索模式', async () => {
    searchFiles.mockResolvedValue({
      data: { total: 1, list: [{ id: 1 }] },
    })

    const searchText = ref('')
    const { isSearchMode, list } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    // 输入关键词触发搜索
    searchText.value = '关键词'
    await vi.advanceTimersByTimeAsync(500)
    await vi.waitFor(() => expect(list.value).toHaveLength(1))
    expect(isSearchMode.value).toBe(true)

    // 清空关键词 → watch 回调清空结果、退出搜索模式
    searchText.value = ''
    expect(isSearchMode.value).toBe(false)

    // 不会有新的搜索请求
    await vi.advanceTimersByTimeAsync(600)
    expect(searchFiles).toHaveBeenCalledTimes(1)
  })

  it('enabled 为 false 时不触发搜索', async () => {
    searchFiles.mockResolvedValue({ data: { total: 0, list: [] } })

    const searchText = ref('关键词')
    useFileSearch({
      searchText,
      enabled: ref(false),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(600)

    expect(searchFiles).not.toHaveBeenCalled()
  })

  it('搜索失败时调用 handleBusinessError', async () => {
    const error = new Error('network error')
    searchFiles.mockRejectedValue(error)

    const searchText = ref('关键词')
    useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(500)

    // search 是异步的，等待它完成
    await vi.waitFor(() => {
      expect(handleBusinessError).toHaveBeenCalledWith(error, '搜索失败，请稍后重试')
    })
  })

  it('resetResults 手动清空搜索结果', async () => {
    searchFiles.mockResolvedValue({
      data: { total: 1, list: [{ id: 1 }] },
    })

    const searchText = ref('关键词')
    const { resetResults, list, total } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: null,
    })

    await vi.advanceTimersByTimeAsync(500)
    await vi.waitFor(() => expect(list.value).toHaveLength(1))

    resetResults()

    expect(list.value).toEqual([])
    expect(total.value).toBe(0)
  })

  // 07-P1-4：分页事件从「模板直接绑 refresh」改为显式 handler（并把换页长回第 1 页的语义补上）。
  it('handleCurrentChange 用新页码重新搜索', async () => {
    searchFiles.mockResolvedValue({ data: { total: 41, list: [{ id: 1 }] } })

    const searchText = ref('关键词')
    const { page, handleCurrentChange } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(500)
    await vi.waitFor(() => expect(searchFiles).toHaveBeenCalledTimes(1))

    await handleCurrentChange(3)

    expect(page.value).toBe(3)
    expect(searchFiles).toHaveBeenLastCalledWith('关键词', 3, 20, expect.any(Object))
  })

  it('handleSizeChange 换页长后回到第 1 页', async () => {
    searchFiles.mockResolvedValue({ data: { total: 100, list: [] } })

    const searchText = ref('关键词')
    const { page, pageSize, handleCurrentChange, handleSizeChange } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(500)
    await handleCurrentChange(4)
    expect(page.value).toBe(4)

    await handleSizeChange(50)

    expect(pageSize.value).toBe(50)
    expect(page.value).toBe(1)
    expect(searchFiles).toHaveBeenLastCalledWith('关键词', 1, 50, expect.any(Object))
  })

  it('handleSizeChange 把超限页长钳到上限', async () => {
    searchFiles.mockResolvedValue({ data: { total: 0, list: [] } })

    const searchText = ref('关键词')
    const { pageSize, handleSizeChange } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(500)
    await handleSizeChange(9999)

    // 与后端 PageResult.MAX_PAGE_SIZE 同值。
    expect(pageSize.value).toBe(200)
  })

  // 07-P2-4：搜索信封开始带 page / pageSize（此前只有 total/list）。
  // 采纳后端生效值，否则「前端按 200 算总页数、后端按 50 分页」⇒ 中后段翻不到。
  // 用「前端发 200、后端回声 50」建模前后端混版窗口（前端页长选项已开到 200，
  // 未升级的旧后端把搜索上限钳在 50）。回声必须**小于**请求，否则测的就不是钳制。
  it('采纳后端回传的 pageSize，并按生效页长继续翻页', async () => {
    searchFiles.mockResolvedValue({ data: { total: 1000, page: 1, pageSize: 20, list: [] } })

    const searchText = ref('关键词')
    const { page, pageSize, handleCurrentChange, handleSizeChange } = useFileSearch({
      searchText,
      enabled: ref(true),
      spaceContext: null,
      teamId: ref(null),
      spaceType: ref(1),
      projectId: ref(null),
    })

    await vi.advanceTimersByTimeAsync(500)
    await vi.waitFor(() => expect(searchFiles).toHaveBeenCalledTimes(1))

    // 用户把页长切到 200，旧后端只按 50 生效并把 50 回传。
    searchFiles.mockResolvedValue({ data: { total: 1000, page: 1, pageSize: 50, list: [] } })
    await handleSizeChange(200)

    expect(searchFiles).toHaveBeenLastCalledWith('关键词', 1, 200, expect.any(Object))
    expect(pageSize.value).toBe(50)

    // 采纳后必须真的用生效页长继续翻页。
    searchFiles.mockClear()
    await handleCurrentChange(3)
    expect(searchFiles).toHaveBeenLastCalledWith('关键词', 3, 50, expect.any(Object))
    // 刻意不采纳 page：后端回声的 page=1 不得把用户刚翻到的第 3 页拽回去。
    expect(page.value).toBe(3)
  })

  // 卸载清理此前一直没被走到：它必须清掉**未触发**的防抖定时器，
  // 否则组件都销毁了仍会迟 500ms 发一次搜索请求。
  it('组件卸载时清掉未触发的防抖定时器，卸载后不再发请求', async () => {
    const { createApp, nextTick } = await import('vue')

    const searchText = ref('')
    const app = createApp({
      setup() {
        useFileSearch({
          searchText,
          enabled: ref(true),
          spaceContext: null,
          teamId: ref(null),
          spaceType: ref(1),
          projectId: ref(null),
        })
        return () => {}
      },
    })
    app.mount(document.createElement('div'))

    searchText.value = '关键词'
    await nextTick()

    app.unmount()

    await vi.advanceTimersByTimeAsync(1000)
    expect(searchFiles).not.toHaveBeenCalled()
  })
})
