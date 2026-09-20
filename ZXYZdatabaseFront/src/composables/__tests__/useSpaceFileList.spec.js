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

    const { page, handleCurrentChange } = createComposable()
    await handleCurrentChange(3)

    expect(page.value).toBe(3)
    expect(fetchFileList).toHaveBeenLastCalledWith(
      -1,
      expect.objectContaining({ page: 3, pageSize: 50 }),
    )
  })

  it('resets to the first page when the page size changes', async () => {
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 100 })

    const { page, pageSize, handleCurrentChange, handleSizeChange } = createComposable()
    await handleCurrentChange(5)
    expect(page.value).toBe(5)

    await handleSizeChange(20)

    expect(pageSize.value).toBe(20)
    expect(page.value).toBe(1)
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

    const { page, resetPage, handleCurrentChange } = createComposable()
    await handleCurrentChange(4)
    expect(page.value).toBe(4)

    fetchFileList.mockClear()
    resetPage()

    expect(page.value).toBe(1)
    expect(fetchFileList).not.toHaveBeenCalled()
  })

  // 07-P2-4：请求里的 pageSize 可能被后端页长上限钳过，采纳回传的生效值是第二道防线 ——
  // 不采纳就会出现「前端按 200 算总页数、后端按 100 分页」⇒ 每翻一页跳掉一批数据。
  // 用「前端发 200、后端回声 100」建模前后端混版窗口（SPACE_PAGE_SIZE_OPTIONS 已开到 200，
  // 未升级的旧后端仍把上限钳在 100）。回声必须**小于**请求，否则测的就不是钳制。
  it('adopts the page size the backend actually applied', async () => {
    fetchFileList.mockResolvedValue({
      code: 1,
      msg: 'success',
      data: { list: [], total: 1000, page: 1, pageSize: 100 },
    })

    const { page, pageSize, handleCurrentChange, handleSizeChange } = createComposable()
    await handleSizeChange(200)

    // 请求带的是用户选的 200，不是回声的 100。
    expect(fetchFileList).toHaveBeenLastCalledWith(
      -1,
      expect.objectContaining({ page: 1, pageSize: 200 }),
    )
    expect(pageSize.value).toBe(100)

    // 采纳后必须真的用生效页长继续翻页。
    await handleCurrentChange(3)
    expect(fetchFileList).toHaveBeenLastCalledWith(
      -1,
      expect.objectContaining({ page: 3, pageSize: 100 }),
    )
    // 刻意不采纳 page：后端回声的 page=1 不得把用户刚翻到的第 3 页拽回去。
    expect(page.value).toBe(3)
  })

  it('keeps the local page size when the payload carries no paging fields', async () => {
    // 裸数组 / 旧后端：不得把页长改成 0 或全局默认值。
    fetchFileList.mockResolvedValue({ code: 1, msg: 'success', data: [], total: 2 })

    const { page, pageSize, refresh } = createComposable()
    await refresh()

    expect(page.value).toBe(1)
    expect(pageSize.value).toBe(50)
  })
})
