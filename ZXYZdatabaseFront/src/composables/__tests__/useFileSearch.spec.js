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
      data: { total: 2, list: [{ id: 1, fileName: '文件A' }, { id: 2, fileName: '文件B' }] },
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
})
