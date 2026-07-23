import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  UPLOAD_REQUEST_TIMEOUT: 30000,
}))

vi.mock('@/models/file', () => ({
  mapSpaceFileEntries: vi.fn((d) => d),
  mapSearchFileEntries: vi.fn((d) => d),
  mapRecycleFileEntries: vi.fn((d) => d),
}))

import request from '@/utils/request'
import {
  fetchFileList,
  searchFiles,
  getFileDownloadUrl,
  renameFile,
  moveFiles,
  copyFiles,
  logicalDeleteFiles,
  createFolder,
} from '@/api/files'

describe('files API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应调用 GET /api/files 获取文件列表（分页格式）', async () => {
    request.get.mockResolvedValue({ data: { list: [{ id: 1 }], total: 1 } })
    const result = await fetchFileList(1, { teamId: 10, sortField: 'name', page: 1, pageSize: 20 })
    expect(request.get).toHaveBeenCalledWith('/api/files', {
      params: { parentId: 1, teamId: 10, sortField: 'name', page: 1, pageSize: 20 },
      signal: undefined,
    })
    expect(result.data).toEqual([{ id: 1 }])
  })

  it('应调用 GET /api/files/search 搜索文件', async () => {
    request.get.mockResolvedValue({ data: {} })
    await searchFiles('test', 1, 20, { teamId: 5 })
    expect(request.get).toHaveBeenCalledWith('/api/files/search', {
      params: { keyword: 'test', page: 1, pageSize: 20, teamId: 5 },
      signal: undefined,
    })
  })

  it('应调用 GET 获取下载链接', async () => {
    request.get.mockResolvedValue({ data: { downloadUrl: 'https://cdn/file.pdf' } })
    await getFileDownloadUrl(42)
    expect(request.get).toHaveBeenCalledWith('/api/files/42/download-url')
  })

  it('应调用 PATCH 重命名文件', async () => {
    request.patch.mockResolvedValue({})
    await renameFile({ fileId: 1, newName: 'new.txt' })
    expect(request.patch).toHaveBeenCalledWith('/api/files/1', { fileId: 1, newName: 'new.txt' })
  })

  it('应调用 PATCH 移动文件', async () => {
    request.patch.mockResolvedValue({ data: {} })
    await moveFiles({ fileIds: [1, 2], targetParentId: 5, teamId: 10 })
    expect(request.patch).toHaveBeenCalledWith('/api/files', {
      fileIds: [1, 2],
      targetParentId: 5,
      teamId: 10,
    })
  })

  it('应调用 POST 复制文件', async () => {
    request.post.mockResolvedValue({ data: {} })
    await copyFiles({ fileIds: [1], targetParentId: 3 })
    expect(request.post).toHaveBeenCalledWith('/api/files/copies', {
      fileIds: [1],
      targetParentId: 3,
    })
  })

  it('应调用 PATCH 删除文件到回收站', async () => {
    request.patch.mockResolvedValue({})
    await logicalDeleteFiles([1, 2])
    expect(request.patch).toHaveBeenCalledWith('/api/files/trash', { fileIds: [1, 2] })
  })

  it('应调用 POST 创建文件夹', async () => {
    request.post.mockResolvedValue({})
    await createFolder({ folderName: 'new', parentId: 1, teamId: 10 })
    expect(request.post).toHaveBeenCalledWith('/api/folders', {
      folderName: 'new',
      parentId: 1,
      teamId: 10,
    })
  })
})
