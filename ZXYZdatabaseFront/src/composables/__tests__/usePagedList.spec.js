import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp } from 'vue'

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

import { usePagedList } from '@/composables/usePagedList'
import { handleBusinessError } from '@/utils/error'

function withSetup(fn) {
  let result
  const app = createApp({
    setup() {
      result = fn()
      return () => {}
    },
  })
  app.mount(document.createElement('div'))
  return { result, app }
}

describe('usePagedList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the context page size and sends it with the first request', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [{ id: 1 }], total: 7 })

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'myShare' }))
    await result.refresh()

    // myShare 的页长是 10（后端 ShareManager 同值），不是全局默认 20。
    expect(loader).toHaveBeenCalledWith({ page: 1, pageSize: 10 })
    expect(result.list.value).toEqual([{ id: 1 }])
    expect(result.total.value).toBe(7)
    expect(result.loading.value).toBe(false)

    app.unmount()
  })

  it('lets an explicit pageSize win over the context and clamps it', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 0 })

    const { result, app } = withSetup(() =>
      usePagedList(loader, { context: 'myShare', pageSize: 9999 }),
    )

    // 上限与后端 PageResult.MAX_PAGE_SIZE 一致，否则前端按 pageSize 算的总页数会多于后端。
    expect(result.pageSize.value).toBe(200)
    await result.refresh()
    expect(loader).toHaveBeenCalledWith({ page: 1, pageSize: 200 })

    app.unmount()
  })

  it('resets to the first page when the page size changes', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 100 })

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'recycleBin' }))
    await result.handleCurrentChange(5)
    expect(result.page.value).toBe(5)

    await result.handleSizeChange(50)

    expect(result.pageSize.value).toBe(50)
    expect(result.page.value).toBe(1)
    expect(loader).toHaveBeenLastCalledWith({ page: 1, pageSize: 50 })

    app.unmount()
  })

  it('clears the list and delegates the error when loading fails', async () => {
    const boom = new Error('boom')
    const loader = vi.fn().mockRejectedValue(boom)

    const { result, app } = withSetup(() =>
      usePagedList(loader, { context: 'recycleBin', errorMessage: '加载回收站失败，请稍后重试' }),
    )
    result.list.value = [{ id: 1 }]
    result.total.value = 3

    await result.refresh()

    expect(result.list.value).toEqual([])
    expect(result.total.value).toBe(0)
    expect(result.loading.value).toBe(false)
    expect(handleBusinessError).toHaveBeenCalledWith(boom, '加载回收站失败，请稍后重试')

    app.unmount()
  })

  it('loads once on mount when immediate is set', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 0 })

    const { app } = withSetup(() => usePagedList(loader, { context: 'myShare', immediate: true }))

    expect(loader).toHaveBeenCalledTimes(1)

    app.unmount()
  })

  it('does not load on mount by default', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 0 })

    const { app } = withSetup(() => usePagedList(loader, { context: 'recycleBin' }))

    expect(loader).not.toHaveBeenCalled()

    app.unmount()
  })

  it('degrades to an empty page when the loader returns nothing', async () => {
    const loader = vi.fn().mockResolvedValue(undefined)

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'myShare' }))
    await result.refresh()

    expect(result.list.value).toEqual([])
    expect(result.total.value).toBe(0)

    app.unmount()
  })

  it('resetPage only moves the cursor without refetching', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 100 })

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'recycleBin' }))
    await result.handleCurrentChange(4)
    loader.mockClear()

    result.resetPage()

    expect(result.page.value).toBe(1)
    expect(loader).not.toHaveBeenCalled()

    app.unmount()
  })

  // 07-P2-4：请求里的 pageSize 可能被后端的页长上限钳过。采纳回传的生效值是第二道防线 ——
  // 不采纳就会出现「前端按 200 算总页数、后端按 100 分页」⇒ 每翻一页跳掉一批数据。
  // 用「前端发 200、后端回声 100」建模前后端混版窗口（前端页长选项已开到 200，
  // 未升级的旧后端仍把上限钳在 100）。回声必须**小于**请求，否则测的就不是钳制。
  it('adopts the page size the backend actually applied', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 1000, page: 1, pageSize: 100 })

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'recycleBin' }))
    await result.handleSizeChange(200)

    // 请求带的是用户选的 200，不是回声的 100 —— 否则这道防线等于没测。
    expect(loader).toHaveBeenLastCalledWith({ page: 1, pageSize: 200 })
    expect(result.pageSize.value).toBe(100)

    // 采纳后必须真的用生效页长继续翻页，否则只是「显示对了、请求还是错的」。
    await result.handleCurrentChange(4)
    expect(loader).toHaveBeenLastCalledWith({ page: 4, pageSize: 100 })
    // 刻意不采纳 page：后端回声的 page=1 不得把用户刚翻到的第 4 页拽回去。
    expect(result.page.value).toBe(4)

    app.unmount()
  })

  it('keeps local paging when the backend omits or sends invalid paging fields', async () => {
    const loader = vi.fn().mockResolvedValue({ list: [], total: 5 })

    const { result, app } = withSetup(() => usePagedList(loader, { context: 'recycleBin' }))
    await result.refresh()
    expect(result.pageSize.value).toBe(20)

    // 0 或缺字段都表示「后端没回传」，不能把页长改成 0。
    loader.mockResolvedValue({ list: [], total: 5, page: 9, pageSize: 0 })
    await result.refresh()
    expect(result.page.value).toBe(1)
    expect(result.pageSize.value).toBe(20)

    app.unmount()
  })
})
