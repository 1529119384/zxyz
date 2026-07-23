import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

vi.mock('@/utils/archive/archiveDownloadRunner', () => ({
  runArchiveDownload: vi.fn(),
}))

import { runArchiveDownload } from '@/utils/archive/archiveDownloadRunner'
import { useArchiveDownload } from '@/composables/useArchiveDownload'

describe('useArchiveDownload', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应初始化状态', () => {
    const { archiveDialogVisible, archiveSubmitting, archiveDefaultName } = useArchiveDownload()
    expect(archiveDialogVisible.value).toBe(false)
    expect(archiveSubmitting.value).toBe(false)
    expect(archiveDefaultName.value).toBe('archive')
  })

  it('应打开弹窗并设置默认名称', () => {
    const resolveOpenState = vi.fn(() => ({
      opened: true,
      defaultName: '项目文件',
      context: { id: 1 },
    }))
    const { openArchiveNameDialog, archiveDialogVisible, archiveDefaultName } = useArchiveDownload({
      resolveOpenState,
    })

    const result = openArchiveNameDialog({ type: 'project' })
    expect(result).toBe(true)
    expect(archiveDialogVisible.value).toBe(true)
    expect(archiveDefaultName.value).toBe('项目文件')
    expect(resolveOpenState).toHaveBeenCalledWith({ type: 'project' })
  })

  it('resolveOpenState 返回 opened=false 时不应打开弹窗', () => {
    const resolveOpenState = vi.fn(() => ({ opened: false }))
    const { openArchiveNameDialog, archiveDialogVisible } = useArchiveDownload({ resolveOpenState })

    const result = openArchiveNameDialog({})
    expect(result).toBe(false)
    expect(archiveDialogVisible.value).toBe(false)
  })

  it('未提交时应关闭弹窗', async () => {
    const resolveOpenState = vi.fn(() => ({ opened: true }))
    const { openArchiveNameDialog, closeArchiveNameDialog, archiveDialogVisible } =
      useArchiveDownload({ resolveOpenState })

    openArchiveNameDialog({})
    expect(archiveDialogVisible.value).toBe(true)
    closeArchiveNameDialog()
    expect(archiveDialogVisible.value).toBe(false)
  })

  it('提交中不应关闭弹窗', async () => {
    let resolveDownload
    runArchiveDownload.mockImplementation((opts) => {
      // 模拟 setSubmitting 被调用
      opts.setSubmitting?.(true)
      return new Promise((r) => {
        resolveDownload = r
      })
    })

    const buildRunnerOptions = vi.fn(() => ({
      onSuccess: vi.fn(),
      setSubmitting: vi.fn(),
    }))
    const resolveOpenState = vi.fn(() => ({ opened: true }))
    const {
      openArchiveNameDialog,
      handleArchiveDownloadSubmit,
      closeArchiveNameDialog,
      archiveDialogVisible,
      archiveSubmitting,
    } = useArchiveDownload({ resolveOpenState, buildRunnerOptions })

    openArchiveNameDialog({})
    handleArchiveDownloadSubmit('download.zip')
    await nextTick()
    expect(archiveSubmitting.value).toBe(true)
    closeArchiveNameDialog()
    expect(archiveDialogVisible.value).toBe(true)
    resolveDownload()
    await nextTick()
  })

  it('提交成功后应关闭弹窗并重置', async () => {
    runArchiveDownload.mockImplementation(async (opts) => {
      opts.setSubmitting?.(true)
      opts.onSuccess?.()
    })
    const onSuccess = vi.fn()
    const resetOnSuccess = vi.fn()
    const buildRunnerOptions = vi.fn(() => ({
      onSuccess,
      setSubmitting: vi.fn(),
    }))
    const resolveOpenState = vi.fn(() => ({ opened: true }))
    const { openArchiveNameDialog, handleArchiveDownloadSubmit, archiveDialogVisible } =
      useArchiveDownload({ resolveOpenState, buildRunnerOptions, resetOnSuccess })

    openArchiveNameDialog({})
    await handleArchiveDownloadSubmit('download.zip')
    expect(archiveDialogVisible.value).toBe(false)
    expect(onSuccess).toHaveBeenCalled()
    expect(resetOnSuccess).toHaveBeenCalled()
  })
})
