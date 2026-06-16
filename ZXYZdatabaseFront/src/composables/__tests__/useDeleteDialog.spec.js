import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { useDeleteDialog } from '@/composables/useDeleteDialog'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

vi.mock('@/utils/logger', () => ({
  logger: { error: vi.fn() },
}))

import { ElMessage } from 'element-plus'
import { handleBusinessError } from '@/utils/error'

describe('useDeleteDialog', () => {
  const baseOptions = {
    deleteRequest: vi.fn(),
    getSuccessMessage: (items) => `成功删除 ${items.length} 个项目`,
    getFallbackMessage: (items) => `删除 ${items.length} 个项目失败`,
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should initialize with dialog not visible and not submitting', () => {
    const { deleteDialogVisible, deleteSubmitting } = useDeleteDialog(baseOptions)
    expect(deleteDialogVisible.value).toBe(false)
    expect(deleteSubmitting.value).toBe(false)
  })

  it('should return false when opening with empty array', () => {
    const { openDeleteDialog } = useDeleteDialog(baseOptions)
    expect(openDeleteDialog([])).toBe(false)
  })

  it('should return false when opening with falsy values', () => {
    const { openDeleteDialog } = useDeleteDialog(baseOptions)
    expect(openDeleteDialog([null, undefined, false])).toBe(false)
  })

  it('should open dialog with single item', () => {
    const { openDeleteDialog, deleteDialogVisible, deleteDialogOptions } = useDeleteDialog(baseOptions)
    const result = openDeleteDialog([{ fileName: 'test.txt', type: 1 }])
    expect(result).toBe(true)
    expect(deleteDialogVisible.value).toBe(true)
    expect(deleteDialogOptions.value.fileName).toBe('test.txt')
    expect(deleteDialogOptions.value.message).toBe('')
  })

  it('should open dialog with batch items and call getBatchMessage', () => {
    const getBatchMessage = vi.fn(() => '批量删除 2 个项目')
    const { openDeleteDialog, deleteDialogOptions } = useDeleteDialog({
      ...baseOptions,
      getBatchMessage,
    })
    openDeleteDialog([{ fileName: 'a.txt' }, { fileName: 'b.txt' }])
    expect(getBatchMessage).toHaveBeenCalled()
    expect(deleteDialogOptions.value.message).toBe('批量删除 2 个项目')
  })

  it('should close dialog when not submitting', () => {
    const { openDeleteDialog, closeDeleteDialog, deleteDialogVisible } = useDeleteDialog(baseOptions)
    openDeleteDialog([{ fileName: 'test.txt' }])
    expect(deleteDialogVisible.value).toBe(true)
    closeDeleteDialog()
    expect(deleteDialogVisible.value).toBe(false)
  })

  it('should not close dialog while submitting', async () => {
    let resolveDelete
    baseOptions.deleteRequest.mockImplementation(() => new Promise((r) => { resolveDelete = r }))
    const { openDeleteDialog, handleDeleteSubmit, closeDeleteDialog, deleteDialogVisible } = useDeleteDialog(baseOptions)
    openDeleteDialog([{ fileName: 'test.txt' }])
    handleDeleteSubmit()
    await nextTick()
    closeDeleteDialog()
    expect(deleteDialogVisible.value).toBe(true)
    resolveDelete()
    await nextTick()
  })

  it('should submit and show success message', async () => {
    baseOptions.deleteRequest.mockResolvedValue(undefined)
    const { openDeleteDialog, handleDeleteSubmit, deleteDialogVisible, deleteSubmitting } = useDeleteDialog(baseOptions)
    openDeleteDialog([{ fileName: 'test.txt' }])
    await handleDeleteSubmit()
    expect(baseOptions.deleteRequest).toHaveBeenCalled()
    expect(ElMessage.success).toHaveBeenCalledWith('成功删除 1 个项目')
    expect(deleteDialogVisible.value).toBe(false)
    expect(deleteSubmitting.value).toBe(false)
  })

  it('should call onSuccess callback after successful delete', async () => {
    const onSuccess = vi.fn()
    baseOptions.deleteRequest.mockResolvedValue(undefined)
    const { openDeleteDialog, handleDeleteSubmit } = useDeleteDialog({ ...baseOptions, onSuccess })
    const items = [{ fileName: 'test.txt' }]
    openDeleteDialog(items)
    await handleDeleteSubmit()
    expect(onSuccess).toHaveBeenCalledWith(items)
  })

  it('should handle delete failure gracefully', async () => {
    baseOptions.deleteRequest.mockRejectedValue(new Error('Network error'))
    const { openDeleteDialog, handleDeleteSubmit, deleteSubmitting } = useDeleteDialog(baseOptions)
    openDeleteDialog([{ fileName: 'test.txt' }])
    await handleDeleteSubmit()
    expect(handleBusinessError).toHaveBeenCalled()
    expect(deleteSubmitting.value).toBe(false)
  })

  it('should handle dialog visibility change to false', () => {
    const { openDeleteDialog, handleDeleteDialogVisibleChange, deleteDialogVisible } = useDeleteDialog(baseOptions)
    openDeleteDialog([{ fileName: 'test.txt' }])
    expect(deleteDialogVisible.value).toBe(true)
    handleDeleteDialogVisibleChange(false)
    expect(deleteDialogVisible.value).toBe(false)
  })

  it('should handle dialog visibility change to true', () => {
    const { handleDeleteDialogVisibleChange, deleteDialogVisible } = useDeleteDialog(baseOptions)
    handleDeleteDialogVisibleChange(true)
    expect(deleteDialogVisible.value).toBe(true)
  })

  it('should use custom confirmText', () => {
    const { openDeleteDialog, deleteDialogOptions } = useDeleteDialog({
      ...baseOptions,
      confirmText: '确认移除',
    })
    openDeleteDialog([{ fileName: 'test.txt' }])
    expect(deleteDialogOptions.value.confirmText).toBe('确认移除')
  })
})
