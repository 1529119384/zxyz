import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp } from 'vue'

vi.mock('@/api/share', () => ({
  fetchMyShareList: vi.fn(),
  cancelMyShare: vi.fn(),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyText: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

import { cancelMyShare, fetchMyShareList } from '@/api/share'
import { useMyShareList } from '@/composables/useMyShareList'
import { mapMyShareRecords } from '@/models/share'

function createComposable() {
  let result
  const app = createApp({
    setup() {
      result = useMyShareList()
      return () => {}
    },
  })
  app.mount(document.createElement('div'))
  return { ...result, app }
}

describe('useMyShareList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchMyShareList.mockResolvedValue({ data: { page: 1, pageSize: 10, total: 0, list: [] } })
  })

  it('requests the first page with the myShare page size (10, not the global 20)', async () => {
    fetchMyShareList.mockResolvedValue({
      data: { page: 1, pageSize: 10, total: 41, list: [{ shareId: 1 }] },
    })

    const c = createComposable()
    await c.loadList()

    expect(fetchMyShareList).toHaveBeenLastCalledWith({ page: 1, pageSize: 10 })
    expect(c.list.value).toEqual([{ shareId: 1 }])
    expect(c.total.value).toBe(41)
    expect(c.pageSize.value).toBe(10)
    c.app.unmount()
  })

  it('keeps the list populated when the legacy rows envelope comes back', async () => {
    // 把「旧后端信封」整条链走一遍：models 层归一化 → composable 取数。
    // 这是前后端同批上线期间的降级保护，缺了它旧 chunk 会看到整页空白。
    fetchMyShareList.mockResolvedValue({
      data: mapMyShareRecords({ total: 1, rows: [{ shareId: 9 }] }),
    })

    const c = createComposable()
    await c.loadList()

    expect(c.list.value).toHaveLength(1)
    expect(c.total.value).toBe(1)
    c.app.unmount()
  })

  it('resets to the first page when the page size changes', async () => {
    // 回声必须跟随请求：后端只会在**超限**时把 pageSize 钳小（分享列表默认 10、上限 200，
    // 50 既非超限也不会被钳），正常情况原样回传。用固定值当回声等于宣称「用户选的 50 是错的」，
    // 测出来的不是真实行为 —— 真实后端收到 50 就回 50。
    fetchMyShareList.mockImplementation((params) =>
      Promise.resolve({
        data: { page: params.page, pageSize: params.pageSize, total: 100, list: [] },
      }),
    )

    const c = createComposable()
    await c.handleCurrentChange(4)
    expect(c.page.value).toBe(4)

    await c.handleSizeChange(50)

    expect(c.pageSize.value).toBe(50)
    expect(c.page.value).toBe(1)
    expect(fetchMyShareList).toHaveBeenLastCalledWith({ page: 1, pageSize: 50 })
    c.app.unmount()
  })

  it('clears list and total when the request fails', async () => {
    fetchMyShareList.mockRejectedValue(new Error('boom'))

    const c = createComposable()
    await c.loadList()

    expect(c.list.value).toEqual([])
    expect(c.total.value).toBe(0)
    expect(c.loading.value).toBe(false)
    c.app.unmount()
  })

  it('keeps loadList as a working alias for existing callers', async () => {
    const c = createComposable()

    // views/my-share 绑定的就是 loadList；重构不得把它弄丢，也不能只留个同名的空壳。
    expect(typeof c.loadList).toBe('function')

    fetchMyShareList.mockClear()
    await c.loadList()

    expect(fetchMyShareList).toHaveBeenCalledTimes(1)
    c.app.unmount()
  })

  it('reloads the current page after a share is cancelled', async () => {
    cancelMyShare.mockResolvedValue({})
    const { ElMessageBox } = await import('element-plus')
    ElMessageBox.confirm.mockResolvedValue(undefined)

    const c = createComposable()
    await c.loadList()
    const callsBefore = fetchMyShareList.mock.calls.length

    await c.cancelShareRecord({ shareId: 77 })

    expect(cancelMyShare).toHaveBeenCalledWith(77)
    expect(fetchMyShareList.mock.calls.length).toBe(callsBefore + 1)
    c.app.unmount()
  })

  it('does not surface an error when the cancel dialog is dismissed', async () => {
    const { ElMessageBox } = await import('element-plus')
    ElMessageBox.confirm.mockRejectedValue(new Error('cancel'))

    const c = createComposable()
    await c.loadList()
    const callsBefore = fetchMyShareList.mock.calls.length

    await c.cancelShareRecord({ shareId: 78 })

    // 用户点了「再想想」不该弹错误提示，也不该重新拉列表。
    expect(cancelMyShare).not.toHaveBeenCalled()
    expect(fetchMyShareList.mock.calls.length).toBe(callsBefore)
    c.app.unmount()
  })

  // 07-P1-4：copyShareRecord 是这次重构后唯一没被走到的业务动作（取消走 3 条，复制 0 条）。
  // 它同时覆盖 models/share.buildShareMessage —— 分享文案的品牌名与提取码两路都在这里。
  it('复制分享文案：成功给提示，失败委派 handleBusinessError', async () => {
    const { copyText } = await import('@/utils/clipboard')
    const { handleBusinessError } = await import('@/utils/error')
    const { ElMessage } = await import('element-plus')

    copyText.mockResolvedValue(undefined)

    const c = createComposable()
    await c.copyShareRecord({ shareUrl: 'https://example.test/s/a1' })

    expect(copyText).toHaveBeenCalledWith('指绣云章给你分享了文件：https://example.test/s/a1')
    expect(ElMessage.success).toHaveBeenCalledWith('分享文案已复制')

    copyText.mockRejectedValue(new Error('clipboard denied'))
    await c.copyShareRecord({ shareUrl: 'https://example.test/s/a1' })

    expect(handleBusinessError).toHaveBeenCalledWith(expect.any(Error), '复制分享文案失败，请稍后重试')
    c.app.unmount()
  })

  // 「再想想」走的是 return 分支；真正失败（接口 500）必须被报出来，两者不能混为一谈。
  it('取消分享真失败（非用户取消）时委派 handleBusinessError', async () => {
    const { handleBusinessError } = await import('@/utils/error')
    const { ElMessageBox } = await import('element-plus')

    ElMessageBox.confirm.mockResolvedValue(undefined)
    cancelMyShare.mockRejectedValue(new Error('500'))

    const c = createComposable()
    await c.cancelShareRecord({ shareId: 88 })

    expect(handleBusinessError).toHaveBeenCalledWith(expect.any(Error), '取消分享失败，请稍后重试')
    c.app.unmount()
  })
})
