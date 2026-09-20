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
})
