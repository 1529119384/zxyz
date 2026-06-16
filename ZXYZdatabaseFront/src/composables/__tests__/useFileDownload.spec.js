import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useFileDownload } from '@/composables/useFileDownload'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
}))

vi.mock('@/api/files', () => ({
  getFileDownloadUrl: vi.fn(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyText: vi.fn(),
}))

vi.mock('@/utils/download', () => ({
  downloadBlobByUrl: vi.fn(),
}))

import { getFileDownloadUrl } from '@/api/files'
import { copyText } from '@/utils/clipboard'
import { downloadBlobByUrl } from '@/utils/download'
import { ElMessage } from 'element-plus'

describe('useFileDownload', () => {
  const mockRouter = {
    resolve: vi.fn(() => ({ href: '/folder?path=/docs' })),
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should download file successfully', async () => {
    getFileDownloadUrl.mockResolvedValue({ data: { downloadUrl: 'https://cdn.example.com/file.pdf' } })
    const { downloadFile } = useFileDownload({ router: mockRouter })
    await downloadFile({ id: 42, fileName: 'file.pdf' })
    expect(getFileDownloadUrl).toHaveBeenCalledWith(42)
    expect(downloadBlobByUrl).toHaveBeenCalledWith('https://cdn.example.com/file.pdf', 'file.pdf')
  })

  it('should throw when no download URL returned', async () => {
    getFileDownloadUrl.mockResolvedValue({ data: {} })
    const { downloadFile } = useFileDownload({ router: mockRouter })
    await expect(downloadFile({ id: 42, fileName: 'file.pdf' })).rejects.toThrow('未获取到下载链接')
  })

  it('should copy download link successfully', async () => {
    getFileDownloadUrl.mockResolvedValue({ data: { downloadUrl: 'https://cdn.example.com/file.pdf' } })
    const { copyDownloadLink } = useFileDownload({ router: mockRouter })
    await copyDownloadLink({ id: 42, fileName: 'file.pdf' })
    expect(copyText).toHaveBeenCalledWith('https://cdn.example.com/file.pdf')
    expect(ElMessage.success).toHaveBeenCalledWith('下载链接已复制')
  })

  it('should open folder in new tab', () => {
    const { openFolderInNewTab } = useFileDownload({ router: mockRouter })
    openFolderInNewTab('/documents')
    expect(mockRouter.resolve).toHaveBeenCalledWith({
      name: 'index',
      query: { path: '/documents' },
    })
  })
})
