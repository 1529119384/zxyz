import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/files', () => ({
  fetchRecycleList: vi.fn(),
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({ teamId: 7, spaceType: 2, projectId: null })),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

import { fetchRecycleList } from '@/api/files'
import { useRecycleBinList } from '@/composables/useRecycleBinList'

function createComposable() {
  return useRecycleBinList({
    spaceContext: {},
    teamId: 7,
    spaceType: 2,
    projectId: null,
  })
}

describe('useRecycleBinList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 07-P0-2：回收站由「无上限全量返回」改为服务端分页，该用例同时钉住
  // 「首页默认参数」与「空间参数未被分页改造挤掉」两件事。
  it('loads the first page with the default page size', async () => {
    const entries = [{ id: 1, fileName: '旧文件.txt' }]
    fetchRecycleList.mockResolvedValue({ code: 1, msg: 'ok', data: entries, total: 41 })

    const { list, total, page, pageSize, refresh } = createComposable()
    await refresh()

    expect(fetchRecycleList).toHaveBeenCalledWith(
      expect.objectContaining({ teamId: 7, spaceType: 2, page: 1, pageSize: 20 }),
    )
    expect(list.value).toEqual(entries)
    expect(total.value).toBe(41)
    expect(page.value).toBe(1)
    expect(pageSize.value).toBe(20)
  })

  it('refetches with the requested page on current-change', async () => {
    fetchRecycleList.mockResolvedValue({ code: 1, msg: 'ok', data: [], total: 41 })

    const { page, handleCurrentChange } = createComposable()
    await handleCurrentChange(3)

    expect(page.value).toBe(3)
    expect(fetchRecycleList).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 3, pageSize: 20 }),
    )
  })

  it('resets to the first page when the page size changes', async () => {
    fetchRecycleList.mockResolvedValue({ code: 1, msg: 'ok', data: [], total: 100 })

    const { page, pageSize, handleCurrentChange, handleSizeChange } = createComposable()
    await handleCurrentChange(5)
    await handleSizeChange(50)

    // 换页长后第 5 页大概率越界（50 条/页可能只剩 1 页），必须回到第 1 页。
    expect(pageSize.value).toBe(50)
    expect(page.value).toBe(1)
    expect(fetchRecycleList).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, pageSize: 50 }),
    )
  })

  it('falls back to total 0 when the backend returns a bare array', async () => {
    // 兼容后端版本回退成裸数组：列表仍要能显示，只是没有总数。
    fetchRecycleList.mockResolvedValue({ code: 1, msg: 'ok', data: [{ id: 2 }] })

    const { list, total, refresh } = createComposable()
    await refresh()

    expect(list.value).toHaveLength(1)
    expect(total.value).toBe(0)
  })

  it('exposes an empty state for an empty recycle bin', async () => {
    fetchRecycleList.mockResolvedValue({ code: 1, msg: 'ok', data: [], total: 0 })

    const { list, total, emptyText, handleSizeChange } = createComposable()
    await handleSizeChange(10)

    expect(list.value).toEqual([])
    expect(total.value).toBe(0)
    expect(emptyText).toBe('回收站为空')
  })

  it('clears list and total when the request fails', async () => {
    fetchRecycleList.mockRejectedValue(new Error('boom'))

    const { list, total, loading, refresh } = createComposable()
    await refresh()

    expect(list.value).toEqual([])
    expect(total.value).toBe(0)
    expect(loading.value).toBe(false)
  })
})
