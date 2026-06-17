import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
}))

import { useBatchFeedback } from '@/composables/useBatchFeedback'
import { ElMessage } from 'element-plus'

describe('useBatchFeedback', () => {
  let showFeedback

  beforeEach(() => {
    vi.clearAllMocks()
    ;({ showFeedback } = useBatchFeedback())
  })

  it('应展示兜底成功消息当 result 为空', () => {
    showFeedback(null)
    expect(ElMessage.success).toHaveBeenCalledWith('操作成功')
  })

  it('应展示自定义兜底消息', () => {
    showFeedback(null, { actionName: '删除', fallbackMessage: '删除完成' })
    expect(ElMessage.success).toHaveBeenCalledWith('删除完成')
  })

  it('应展示全部成功消息', () => {
    showFeedback({ successCount: 5, renamedCount: 0, failedCount: 0, details: [] })
    expect(ElMessage.success).toHaveBeenCalledWith('操作成功：5 个文件')
  })

  it('应展示部分失败警告', () => {
    showFeedback({
      successCount: 3,
      renamedCount: 0,
      failedCount: 2,
      details: [
        { status: 'failed', fileName: 'a.txt' },
        { status: 'success', fileName: 'b.txt' },
      ],
    })
    expect(ElMessage.warning).toHaveBeenCalled()
    const msg = ElMessage.warning.mock.calls[0][0]
    expect(msg).toContain('3 成功')
    expect(msg).toContain('2 失败')
  })

  it('应展示全部失败错误', () => {
    showFeedback({
      successCount: 0,
      renamedCount: 0,
      failedCount: 3,
      details: [{ status: 'failed', fileName: 'x.txt' }],
    })
    expect(ElMessage.error).toHaveBeenCalled()
  })

  it('应展示重命名成功消息', () => {
    showFeedback({
      successCount: 2,
      renamedCount: 1,
      failedCount: 0,
      details: [{ renamed: true, fileName: 'old.txt', finalName: 'old(1).txt' }],
    })
    expect(ElMessage.success).toHaveBeenCalled()
    const msg = ElMessage.success.mock.calls[0][0]
    expect(msg).toContain('重命名')
  })

  it('应限制详情展示数量为 3', () => {
    const details = Array.from({ length: 5 }, (_, i) => ({
      status: 'failed',
      fileName: `file${i}.txt`,
    }))
    showFeedback({ successCount: 0, renamedCount: 0, failedCount: 5, details })
    const msg = ElMessage.error.mock.calls[0][0]
    expect(msg).toContain('等')
  })
})
