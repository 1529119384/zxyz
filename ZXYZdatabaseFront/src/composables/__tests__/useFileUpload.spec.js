import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

vi.mock('@/services/upload', () => ({
  uploadFileWithPresign: vi.fn(),
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({ teamId: null })),
}))

vi.mock('@/utils/uploadProgress', () => ({
  calculateUploadPercentage: vi.fn(() => 0),
  getUploadTrackingKey: vi.fn(() => 'key-1'),
  sumUploadedBytes: vi.fn(() => 0),
}))

vi.mock('@/utils/nameConflict', () => ({
  buildBatchPredictedNames: vi.fn(() => []),
  FILE_TYPE: { FILE: 1, FOLDER: 0 },
}))

vi.mock('@/utils/id', () => ({
  createClientId: vi.fn(() => 'client-1'),
}))

vi.mock('@/utils/fileValidation', () => ({
  validateFiles: vi.fn(() => ({ valid: [], rejected: [] })),
  MAX_FILE_SIZE: 5368709120,
  DANGEROUS_EXTENSIONS: ['.exe', '.bat', '.cmd', '.sh', '.js'],
}))

import { useFileUpload } from '@/composables/useFileUpload'

describe('useFileUpload', () => {
  const currentId = ref(1)
  let upload

  beforeEach(() => {
    vi.clearAllMocks()
    upload = useFileUpload(currentId, {
      onSuccess: vi.fn(),
      spaceContext: ref({}),
    })
  })

  it('应初始化状态', () => {
    expect(upload.fileUploadDialog.value).toBe(false)
    expect(upload.uploading.value).toBe(false)
    expect(upload.fileList.value).toEqual([])
  })

  it('应导出 MAX_FILE_SIZE 和 DANGEROUS_EXTENSIONS', () => {
    expect(upload.MAX_FILE_SIZE).toBe(5368709120)
    expect(Array.isArray(upload.DANGEROUS_EXTENSIONS)).toBe(true)
  })

  it('应打开上传对话框', () => {
    upload.openFileUpload()
    expect(upload.fileUploadDialog.value).toBe(true)
  })

  it('应计算总文件大小', () => {
    expect(upload.totalFileSize.value).toBe(0)
  })
})
