import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('@/api/files', () => ({
  fetchFileList: vi.fn(),
}))

vi.mock('@/api/project', () => ({
  fetchTeamProjects: vi.fn(),
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

import { fetchFileList } from '@/api/files'
import { useSpaceFileList } from '@/composables/useSpaceFileList'

function createComposable(overrides = {}) {
  return useSpaceFileList({
    currentId: ref(-1),
    sortState: ref({ sortField: 'name', sortOrder: 'asc' }),
    spaceContext: {
      resolveRequestParams: () => ({ teamId: null, spaceType: 1, projectId: null }),
    },
    ...overrides,
  })
}

describe('useSpaceFileList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 回归用例：fetchFileList 会把后端信封拍平成 data 数组，旧实现用
  // `typeof data === 'object'` 判定「分页信封」，而数组同样是 'object'，
  // 于是误取 data.list(=undefined) → entries 恒为空 → 列表被清空。
  // 症状即「新建文件夹/重命名/删除提示成功，但列表不显示」。
  it('populates list when data is a flattened array (fetchFileList real shape)', async () => {
    const entries = [
      { id: 1, fileName: '新建文件夹', type: 0 },
      { id: 2, fileName: '新建文件夹(1)', type: 0 },
    ]
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: entries, total: 2 })

    const { list, total, refresh } = createComposable()
    await refresh()

    expect(list.value).toEqual(entries)
    expect(total.value).toBe(2)
  })

  it('still supports a paged { list, total } envelope', async () => {
    const entries = [{ id: 3, fileName: '文档.pdf', type: 1 }]
    fetchFileList.mockResolvedValue({
      code: 1,
      msg: 'success',
      data: { list: entries, total: 41 },
    })

    const { list, total, refresh } = createComposable()
    await refresh()

    expect(list.value).toEqual(entries)
    expect(total.value).toBe(41)
  })

  it('reuses prefetchedList without hitting the network', async () => {
    const prefetched = [{ id: 9, fileName: '预取目录', type: 0 }]

    const { list, refresh } = createComposable()
    await refresh({ prefetchedList: prefetched })

    expect(list.value).toEqual(prefetched)
    expect(fetchFileList).not.toHaveBeenCalled()
  })

  it('clears the list and stops loading when the request fails', async () => {
    fetchFileList.mockRejectedValue(new Error('boom'))

    const { list, loading, refresh } = createComposable()
    await refresh()

    expect(list.value).toEqual([])
    expect(loading.value).toBe(false)
  })

  // 07-P1-4：分页事件从「模板直接绑 refresh」改为显式 handler。
  // 这一步顺手修掉了「换页长不回第 1 页」—— 旧写法下第 5 页切到 50 条/页会停在越界页码，看到空白页。
  it('refetches with the requested page on current-change', async () => {
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 41 })

    const { currentPage, handleCurrentChange } = createComposable()
    await handleCurrentChange(3)

    expect(currentPage.value).toBe(3)
    expect(fetchFileList).toHaveBeenLastCalledWith(
      -1,
      expect.objectContaining({ page: 3, pageSize: 50 }),
    )
  })

  it('resets to the first page when the page size changes', async () => {
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 100 })

    const { currentPage, pageSize, handleCurrentChange, handleSizeChange } = createComposable()
    await handleCurrentChange(5)
    expect(currentPage.value).toBe(5)

    await handleSizeChange(20)

    expect(pageSize.value).toBe(20)
    expect(currentPage.value).toBe(1)
    expect(fetchFileList).toHaveBeenLastCalledWith(
      -1,
      expect.objectContaining({ page: 1, pageSize: 20 }),
    )
  })

  it('clamps an oversized page size to the shared upper bound', async () => {
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 0 })

    const { pageSize, handleSizeChange } = createComposable()
    await handleSizeChange(9999)

    // 与后端 PageResult.MAX_PAGE_SIZE 同值；不钳的话前端算出的总页数会多于后端。
    expect(pageSize.value).toBe(200)
  })

  // resetPage 在「切换目录」时被 FileExplorer 调用：只拨页码、不自己发请求，
  // 紧随其后的 refresh 才负责取数（否则会多打一次上一页的请求）。
  it('resetPage 只把页码拨回第 1 页，不自己发请求', async () => {
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 41 })

    const { currentPage, resetPage, handleCurrentChange } = createComposable()
    await handleCurrentChange(4)
    expect(currentPage.value).toBe(4)

    fetchFileList.mockClear()
    resetPage()

    expect(currentPage.value).toBe(1)
    expect(fetchFileList).not.toHaveBeenCalled()
  })
})
