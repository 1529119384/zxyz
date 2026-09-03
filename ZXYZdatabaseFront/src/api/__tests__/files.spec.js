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
  getUploadSign,
  directUpload,
  confirmUpload,
  fetchStorageUsage,
  renameFile,
  moveFiles,
  copyFiles,
  logicalDeleteFiles,
  createFolder,
  fetchRecycleList,
  restoreFiles,
  deleteFilesForever,
} from '@/api/files'

describe('files API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应调用 GET /api/files 获取文件列表（分页格式）', async () => {
    request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { list: [{ id: 1 }], total: 1 } })
    const result = await fetchFileList(1, { teamId: 10, sortField: 'name', page: 1, pageSize: 20 })
    expect(request.get).toHaveBeenCalledWith('/api/files', {
      params: { parentId: 1, teamId: 10, sortField: 'name', page: 1, pageSize: 20 },
      signal: undefined,
    })
    // 返回完整信封，data 被映射为数组
    expect(result.code).toBe(1)
    expect(result.data).toEqual([{ id: 1 }])
  })

  it('应调用 GET /api/files/search 搜索文件', async () => {
    request.get.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
    await searchFiles('test', 1, 20, { teamId: 5 })
    expect(request.get).toHaveBeenCalledWith('/api/files/search', {
      params: { keyword: 'test', page: 1, pageSize: 20, teamId: 5 },
      signal: undefined,
    })
  })

  it('应调用 GET 获取下载链接', async () => {
    request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { downloadUrl: 'https://cdn/file.pdf' } })
    await getFileDownloadUrl(42)
    expect(request.get).toHaveBeenCalledWith('/api/files/42/download-url')
  })

  it('应调用 PATCH 重命名文件', async () => {
    request.patch.mockResolvedValue({ code: 1, msg: 'ok', data: null })
    await renameFile({ fileId: 1, newName: 'new.txt' })
    expect(request.patch).toHaveBeenCalledWith('/api/files/1', { fileId: 1, newName: 'new.txt' })
  })

  it('应调用 PATCH 移动文件并返回信封', async () => {
    request.patch.mockResolvedValue({ code: 1, msg: 'ok', data: { successCount: 2 } })
    const result = await moveFiles({ fileIds: [1, 2], targetParentId: 5, teamId: 10 })
    expect(request.patch).toHaveBeenCalledWith('/api/files', {
      fileIds: [1, 2],
      targetParentId: 5,
      teamId: 10,
    })
    // 移动/复制统一返回完整信封，业务结果在 data 中
    expect(result.code).toBe(1)
    expect(result.data.successCount).toBe(2)
  })

  it('应调用 POST 复制文件并返回信封', async () => {
    request.post.mockResolvedValue({ code: 1, msg: 'ok', data: { successCount: 1 } })
    const result = await copyFiles({ fileIds: [1], targetParentId: 3 })
    expect(request.post).toHaveBeenCalledWith('/api/files/copies', {
      fileIds: [1],
      targetParentId: 3,
    })
    expect(result.code).toBe(1)
    expect(result.data.successCount).toBe(1)
  })

  it('应调用 PATCH 删除文件到回收站', async () => {
    request.patch.mockResolvedValue({ code: 1, msg: 'ok', data: null })
    await logicalDeleteFiles([1, 2])
    expect(request.patch).toHaveBeenCalledWith('/api/files/trash', { fileIds: [1, 2] })
  })

  it('应调用 POST 创建文件夹', async () => {
    request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
    await createFolder({ folderName: 'new', parentId: 1, teamId: 10 })
    expect(request.post).toHaveBeenCalledWith('/api/folders', {
      folderName: 'new',
      parentId: 1,
      teamId: 10,
    })
  })

  it('createFolder 带 spaceType/projectId 时一并透传', async () => {
    request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
    await createFolder({
      folderName: 'new',
      parentId: 1,
      teamId: 10,
      spaceType: 2,
      projectId: 7,
    })
    expect(request.post).toHaveBeenCalledWith('/api/folders', {
      folderName: 'new',
      parentId: 1,
      teamId: 10,
      spaceType: 2,
      projectId: 7,
    })
  })

  describe('fetchFileList 参数与数据兜底', () => {
    it('带 spaceType/projectId/sortOrder 时全部进入 params', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { list: [], total: 0 } })
      await fetchFileList(1, {
        teamId: 10,
        spaceType: 2,
        projectId: 7,
        sortField: 'name',
        sortOrder: 'desc',
      })
      expect(request.get).toHaveBeenCalledWith('/api/files', {
        params: {
          parentId: 1,
          teamId: 10,
          spaceType: 2,
          projectId: 7,
          sortField: 'name',
          sortOrder: 'desc',
        },
        signal: undefined,
      })
    })

    it('无 page/pageSize 时不带上分页参数', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { list: [] } })
      await fetchFileList(1)
      expect(request.get).toHaveBeenCalledWith('/api/files', {
        params: { parentId: 1 },
        signal: undefined,
      })
    })

    it('只传 page 时只带 page', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { list: [] } })
      await fetchFileList(1, { page: 3 })
      expect(request.get).toHaveBeenCalledWith('/api/files', {
        params: { parentId: 1, page: 3 },
        signal: undefined,
      })
    })

    it('data 为直接数组（无 list 字段）时也能映射', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: [{ id: 9 }] })
      const result = await fetchFileList(1)
      expect(result.data).toEqual([{ id: 9 }])
    })

    it('透传 signal 以便取消请求', async () => {
      const controller = new AbortController()
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { list: [] } })
      await fetchFileList(1, { signal: controller.signal })
      expect(request.get).toHaveBeenCalledWith('/api/files', {
        params: { parentId: 1 },
        signal: controller.signal,
      })
    })
  })

  describe('searchFiles 可选参数', () => {
    it('带 spaceType/projectId/teamId 时全部进入 params', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await searchFiles('kw', 2, 30, { teamId: 5, spaceType: 2, projectId: 7 })
      expect(request.get).toHaveBeenCalledWith('/api/files/search', {
        params: { keyword: 'kw', page: 2, pageSize: 30, teamId: 5, spaceType: 2, projectId: 7 },
        signal: undefined,
      })
    })

    it('使用默认分页 1/20', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await searchFiles('kw')
      expect(request.get).toHaveBeenCalledWith('/api/files/search', {
        params: { keyword: 'kw', page: 1, pageSize: 20 },
        signal: undefined,
      })
    })
  })

  describe('moveFiles / copyFiles 可选参数', () => {
    it('moveFiles 带 spaceType/projectId 时透传', async () => {
      request.patch.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await moveFiles({ fileIds: [1], targetParentId: 2, teamId: 3, spaceType: 2, projectId: 7 })
      expect(request.patch).toHaveBeenCalledWith('/api/files', {
        fileIds: [1],
        targetParentId: 2,
        teamId: 3,
        spaceType: 2,
        projectId: 7,
      })
    })

    it('copyFiles 带 spaceType/projectId 时透传', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await copyFiles({ fileIds: [1], targetParentId: 2, teamId: 3, spaceType: 2, projectId: 7 })
      expect(request.post).toHaveBeenCalledWith('/api/files/copies', {
        fileIds: [1],
        targetParentId: 2,
        teamId: 3,
        spaceType: 2,
        projectId: 7,
      })
    })
  })

  describe('上传链路', () => {
    it('getUploadSign 带上传超时与 originalName 参数', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: { uploadUrl: 'https://oss' } })
      const result = await getUploadSign('a.txt')
      expect(request.post).toHaveBeenCalledWith('/api/files/uploads', null, {
        params: { originalName: 'a.txt' },
        timeout: 30000,
      })
      expect(result.data.uploadUrl).toBe('https://oss')
    })

    it('directUpload 组装 FormData 并用 multipart 头', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      const file = new File(['hello'], 'a.txt', { type: 'text/plain' })
      await directUpload('a.txt', file, 5, 10, 2, 7)

      expect(request.post).toHaveBeenCalledTimes(1)
      const [url, formData, config] = request.post.mock.calls[0]
      expect(url).toBe('/api/files/uploads/direct')
      expect(formData).toBeInstanceOf(FormData)
      expect(formData.get('file')).toBe(file)
      expect(formData.get('parentId')).toBe('5')
      expect(formData.get('teamId')).toBe('10')
      expect(formData.get('spaceType')).toBe('2')
      expect(formData.get('projectId')).toBe('7')
      expect(config.headers['Content-Type']).toBe('multipart/form-data')
      expect(config.timeout).toBe(30000)
    })

    it('directUpload 省略可选参数时只带 file', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      const file = new File(['x'], 'b.txt', { type: 'text/plain' })
      await directUpload('b.txt', file)

      const formData = request.post.mock.calls[0][1]
      expect(formData.get('file')).toBe(file)
      expect(formData.get('parentId')).toBeNull()
      expect(formData.get('teamId')).toBeNull()
    })

    it('confirmUpload 最小参数：只带 files[0] 必填字段', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await confirmUpload({ objectKey: 'k1', originalName: 'a.txt', fileSize: 10, parentId: 5 })

      const [url, body, config] = request.post.mock.calls[0]
      expect(url).toBe('/api/files/uploads/confirmations')
      expect(body).toEqual({
        files: [{ objectKey: 'k1', originalName: 'a.txt', fileSize: 10, parentId: 5 }],
      })
      expect(config.timeout).toBe(30000)
    })

    it('confirmUpload 全参数：团队/空间/项目提升为顶层，batchId/clientRequestId 留在文件项', async () => {
      request.post.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await confirmUpload({
        objectKey: 'k1',
        originalName: 'a.txt',
        fileSize: 10,
        parentId: 5,
        teamId: 10,
        spaceType: 2,
        projectId: 7,
        batchId: 'b1',
        clientRequestId: 'r1',
      })

      const body = request.post.mock.calls[0][1]
      expect(body).toEqual({
        teamId: 10,
        spaceType: 2,
        projectId: 7,
        files: [
          {
            objectKey: 'k1',
            originalName: 'a.txt',
            fileSize: 10,
            parentId: 5,
            batchId: 'b1',
            clientRequestId: 'r1',
          },
        ],
      })
    })
  })

  describe('fetchStorageUsage', () => {
    it('无参数时 params 为空对象', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: {} })
      await fetchStorageUsage()
      expect(request.get).toHaveBeenCalledWith('/api/storage/usage', { params: {} })
    })

    it('带 spaceType/teamId/projectId 时全部进入 params', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: { usedStorage: 100 } })
      const result = await fetchStorageUsage({ spaceType: 2, teamId: 10, projectId: 7 })
      expect(request.get).toHaveBeenCalledWith('/api/storage/usage', {
        params: { spaceType: 2, teamId: 10, projectId: 7 },
      })
      expect(result.data.usedStorage).toBe(100)
    })
  })

  describe('回收站', () => {
    it('fetchRecycleList 无参数时 params 为空对象', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: [] })
      await fetchRecycleList()
      expect(request.get).toHaveBeenCalledWith('/api/trash/files', { params: {} })
    })

    it('fetchRecycleList 带参数时透传并映射 data', async () => {
      request.get.mockResolvedValue({ code: 1, msg: 'ok', data: [{ id: 3, deleteTime: 't' }] })
      const result = await fetchRecycleList({ teamId: 10, spaceType: 2, projectId: 7 })
      expect(request.get).toHaveBeenCalledWith('/api/trash/files', {
        params: { teamId: 10, spaceType: 2, projectId: 7 },
      })
      expect(result.data).toEqual([{ id: 3, deleteTime: 't' }])
    })

    it('restoreFiles 用 DELETE 且请求体放在 data 中', async () => {
      request.delete.mockResolvedValue({ code: 1, msg: 'ok', data: null })
      await restoreFiles([1, 2])
      expect(request.delete).toHaveBeenCalledWith('/api/files/trash', { data: { fileIds: [1, 2] } })
    })

    it('deleteFilesForever 用 DELETE 彻底删除', async () => {
      request.delete.mockResolvedValue({ code: 1, msg: 'ok', data: null })
      await deleteFilesForever([3, 4])
      expect(request.delete).toHaveBeenCalledWith('/api/files', { data: { fileIds: [3, 4] } })
    })
  })
})
