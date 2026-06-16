import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { useRenameAction } from '@/composables/useRenameAction'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
}))

vi.mock('@/api/files', () => ({
  renameFile: vi.fn(),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

vi.mock('@/utils/logger', () => ({
  logger: { error: vi.fn() },
}))

vi.mock('@/models/file', () => ({
  splitFileNameParts: vi.fn((item) => ({
    baseName: item.fileName?.replace(/\.[^.]+$/, '') || '',
    extension: item.fileName?.match(/\.[^.]+$/)?.[0] || '',
    fullName: item.fileName || '',
  })),
}))

import { renameFile } from '@/api/files'
import { handleBusinessError } from '@/utils/error'

describe('useRenameAction', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should initialize with dialog not visible', () => {
    const { renameDialogVisible, renameSubmitting } = useRenameAction({})
    expect(renameDialogVisible.value).toBe(false)
    expect(renameSubmitting.value).toBe(false)
  })

  it('should return false when opening with null', () => {
    const { openRenameDialog } = useRenameAction({})
    expect(openRenameDialog(null)).toBe(false)
  })

  it('should return false when opening with item without id', () => {
    const { openRenameDialog } = useRenameAction({})
    expect(openRenameDialog({ type: 1 })).toBe(false)
  })

  it('should open dialog with valid item', () => {
    const { openRenameDialog, renameDialogVisible } = useRenameAction({})
    const item = { id: 1, type: 1, fileName: 'test.txt' }
    const result = openRenameDialog(item)
    expect(result).toBe(true)
    expect(renameDialogVisible.value).toBe(true)
  })

  it('should compute renameDefaultName for file', () => {
    const { openRenameDialog, renameDefaultName } = useRenameAction({})
    openRenameDialog({ id: 1, type: 1, fileName: 'document.pdf' })
    expect(renameDefaultName.value).toBe('document')
  })

  it('should compute renameDefaultName for folder', () => {
    const { openRenameDialog, renameDefaultName } = useRenameAction({})
    openRenameDialog({ id: 1, type: 0, fileName: 'MyFolder' })
    expect(renameDefaultName.value).toBe('MyFolder')
  })

  it('should close dialog when not submitting', () => {
    const { openRenameDialog, closeRenameDialog, renameDialogVisible } = useRenameAction({})
    openRenameDialog({ id: 1, type: 1, fileName: 'test.txt' })
    closeRenameDialog()
    expect(renameDialogVisible.value).toBe(false)
  })

  it('should not close dialog while submitting', async () => {
    let resolve
    renameFile.mockImplementation(() => new Promise((r) => { resolve = r }))
    const { openRenameDialog, handleRenameSubmit, closeRenameDialog, renameDialogVisible } = useRenameAction({})
    openRenameDialog({ id: 1, type: 1, fileName: 'test.txt' })
    handleRenameSubmit('new-name.txt')
    await nextTick()
    closeRenameDialog()
    expect(renameDialogVisible.value).toBe(true)
    resolve()
    await nextTick()
  })

  it('should submit and show success', async () => {
    renameFile.mockResolvedValue(undefined)
    const { openRenameDialog, handleRenameSubmit, renameDialogVisible } = useRenameAction({})
    openRenameDialog({ id: 1, type: 1, fileName: 'test.txt' })
    await handleRenameSubmit('renamed.txt')
    expect(renameFile).toHaveBeenCalledWith({ fileId: 1, newName: 'renamed.txt' })
    expect(renameDialogVisible.value).toBe(false)
  })

  it('should call onSuccess after rename', async () => {
    const onSuccess = vi.fn()
    renameFile.mockResolvedValue(undefined)
    const { openRenameDialog, handleRenameSubmit } = useRenameAction({ onSuccess })
    const item = { id: 1, type: 1, fileName: 'test.txt' }
    openRenameDialog(item)
    await handleRenameSubmit('new.txt')
    expect(onSuccess).toHaveBeenCalledWith(item)
  })

  it('should handle rename failure', async () => {
    renameFile.mockRejectedValue(new Error('conflict'))
    const { openRenameDialog, handleRenameSubmit, renameSubmitting } = useRenameAction({})
    openRenameDialog({ id: 1, type: 1, fileName: 'test.txt' })
    await handleRenameSubmit('new.txt')
    expect(handleBusinessError).toHaveBeenCalled()
    expect(renameSubmitting.value).toBe(false)
  })
})
