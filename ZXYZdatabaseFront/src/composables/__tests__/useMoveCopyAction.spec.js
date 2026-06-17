import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

vi.mock('@/api/files', () => ({
  copyFiles: vi.fn(),
  moveFiles: vi.fn(),
}))

vi.mock('@/composables/useBatchFeedback', () => ({
  useBatchFeedback: vi.fn(() => ({ showFeedback: vi.fn() })),
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({})),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

vi.mock('@/utils/logger', () => ({
  logger: { error: vi.fn() },
}))

vi.mock('@/utils/selection', () => ({
  resolveActionTargets: vi.fn((items) => items.map((i) => i.id)),
}))

import { useMoveCopyAction } from '@/composables/useMoveCopyAction'

describe('useMoveCopyAction', () => {
  let action

  beforeEach(() => {
    vi.clearAllMocks()
    action = useMoveCopyAction({
      onSuccess: vi.fn(),
      spaceContext: ref({}),
    })
  })

  it('应初始化对话框为不可见', () => {
    expect(action.moveCopyDialogVisible.value).toBe(false)
  })

  it('应重置对话框状态', () => {
    action.moveCopyDialogVisible.value = true
    action.moveCopyDialogMode.value = 'copy'
    action.resetMoveCopyDialog()
    expect(action.moveCopyDialogVisible.value).toBe(false)
  })

  it('应打开复制对话框', () => {
    action.openMoveCopyDialog('copy', [{ id: 1 }], '/docs')
    expect(action.moveCopyDialogVisible.value).toBe(true)
    expect(action.moveCopyDialogMode.value).toBe('copy')
  })

  it('应打开移动对话框', () => {
    action.openMoveCopyDialog('move', [{ id: 2 }], '/')
    expect(action.moveCopyDialogMode.value).toBe('move')
  })

  it('应处理可见性变更', () => {
    action.handleMoveCopyDialogVisibleChange(false)
    expect(action.moveCopyDialogVisible.value).toBe(false)
  })
})
