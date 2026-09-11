import { describe, it, expect, vi, beforeEach } from 'vitest'

// 只 mock I/O 与模型层：本文件要验证的是 services/upload.js 自己的编排逻辑
// （走哪条上传路径、参数怎么传、目录 id 如何链到子文件、批量失败如何隔离）。
vi.mock('@/api/files', () => ({
  getUploadSign: vi.fn(),
  confirmUpload: vi.fn(),
  createFolder: vi.fn(),
}))

vi.mock('@/utils/oss', () => ({
  uploadToOss: vi.fn(),
  uploadToBackend: vi.fn(),
}))

vi.mock('@/models/upload', () => ({
  normalizeUploadConfirmResult: vi.fn((data, fallback) => ({
    ...fallback,
    id: data?.id,
    normalized: true,
  })),
  normalizeFolderCreateResult: vi.fn((data, name, parentId) => ({
    id: data?.id,
    finalName: data?.finalName ?? name,
    parentId,
    kind: 'folder',
  })),
  createUploadFailResult: vi.fn((payload) => ({ ...payload, kind: 'fail' })),
}))

vi.mock('@/utils/error', () => ({
  getErrorDetail: vi.fn((e) => e?.message ?? String(e)),
  logUploadError: vi.fn(),
}))

vi.mock('@/utils/fileValidation', () => ({
  validateFiles: vi.fn(() => ({ accepted: [], rejected: [] })),
}))

import { confirmUpload, createFolder, getUploadSign } from '@/api/files'
import { uploadToBackend, uploadToOss } from '@/utils/oss'
import { normalizeUploadConfirmResult } from '@/models/upload'
import { logUploadError } from '@/utils/error'
import { validateFiles } from '@/utils/fileValidation'
import { uploadFileWithPresign, uploadFolderTree } from '@/services/upload'

const FILE = new File(['hello'], 'a.txt')
const FILE_SIZE = 5

function signResponse(overrides = {}) {
  return {
    data: {
      uploadUrl: 'https://bucket.oss/x?a=1',
      objectKey: 'uploads/k1',
      contentType: 'text/plain',
      contentDisposition: 'inline',
      directUpload: false,
      ...overrides,
    },
  }
}

describe('services/upload 上传编排', () => {
  beforeEach(() => {
    // 每个用例都从「预签名直传成功」这条happy path 出发，用例内再按需覆盖。
    getUploadSign.mockResolvedValue(signResponse())
    uploadToOss.mockResolvedValue(undefined)
    uploadToBackend.mockResolvedValue(undefined)
    confirmUpload.mockResolvedValue({ data: { id: 42 } })
    validateFiles.mockReturnValue({ accepted: [], rejected: [] })
  })

  describe('uploadFileWithPresign', () => {
    it('预签名链路：取签名 → 传 OSS → 确认，并把参数完整透传', async () => {
      const onProgress = vi.fn()
      const result = await uploadFileWithPresign(FILE, 7, onProgress, {
        batchId: 'b1',
        teamId: 2,
        spaceType: 'team',
        projectId: 3,
        clientRequestId: 'r1',
      })

      expect(getUploadSign).toHaveBeenCalledWith('a.txt')
      expect(uploadToOss).toHaveBeenCalledWith('https://bucket.oss/x?a=1', FILE, {
        onUploadProgress: onProgress,
        contentType: 'text/plain',
        contentDisposition: 'inline',
      })
      expect(confirmUpload).toHaveBeenCalledWith({
        objectKey: 'uploads/k1',
        originalName: 'a.txt',
        fileSize: FILE_SIZE,
        parentId: 7,
        teamId: 2,
        spaceType: 'team',
        projectId: 3,
        batchId: 'b1',
        clientRequestId: 'r1',
      })
      expect(normalizeUploadConfirmResult).toHaveBeenCalledWith(
        { id: 42 },
        { originalName: 'a.txt', fileSize: FILE_SIZE, parentId: 7, clientRequestId: 'r1' },
      )
      expect(result).toEqual({
        originalName: 'a.txt',
        fileSize: FILE_SIZE,
        parentId: 7,
        clientRequestId: 'r1',
        id: 42,
        normalized: true,
      })
    })

    it('取签名失败时不再发起上传，并包成「获取上传签名失败」', async () => {
      getUploadSign.mockRejectedValue(new Error('sign service down'))

      await expect(uploadFileWithPresign(FILE, 1, vi.fn())).rejects.toThrow(
        '获取上传签名失败：sign service down',
      )
      expect(uploadToOss).not.toHaveBeenCalled()
      expect(confirmUpload).not.toHaveBeenCalled()
      expect(logUploadError).toHaveBeenCalled()
    })

    it.each([
      ['contentType', 'Missing contentType from getUploadSign response'],
      ['contentDisposition', 'Missing contentDisposition from getUploadSign response'],
    ])('预签名响应缺少 %s 时直接失败，不带着空值去传 OSS', async (field, message) => {
      getUploadSign.mockResolvedValue(signResponse({ [field]: '' }))

      await expect(uploadFileWithPresign(FILE, 1, vi.fn())).rejects.toThrow(
        `获取上传签名失败：${message}`,
      )
      expect(uploadToOss).not.toHaveBeenCalled()
    })

    it('directUpload=true 时跳过 contentType/contentDisposition 校验，走后端直传且无需确认', async () => {
      getUploadSign.mockResolvedValue(
        signResponse({ directUpload: true, contentType: '', contentDisposition: '' }),
      )

      const result = await uploadFileWithPresign(FILE, 7, vi.fn(), {
        teamId: 2,
        spaceType: 'team',
        projectId: 3,
        clientRequestId: 'r1',
      })

      expect(uploadToBackend).toHaveBeenCalledWith(FILE, 7, 2, 'team', 3)
      expect(uploadToOss).not.toHaveBeenCalled()
      expect(confirmUpload).not.toHaveBeenCalled()
      expect(result).toEqual({
        originalName: 'a.txt',
        fileSize: FILE_SIZE,
        parentId: 7,
        status: 'success',
        clientRequestId: 'r1',
      })
    })

    it('直传失败时包成「上传失败」，且不会走到确认', async () => {
      uploadToOss.mockRejectedValue(new Error('OSS upload failed: network error'))

      await expect(uploadFileWithPresign(FILE, 1, vi.fn())).rejects.toThrow(
        '上传失败：OSS upload failed: network error',
      )
      expect(confirmUpload).not.toHaveBeenCalled()
    })

    it('确认失败时包成「确认上传失败」', async () => {
      confirmUpload.mockRejectedValue(new Error('name conflict'))

      await expect(uploadFileWithPresign(FILE, 1, vi.fn())).rejects.toThrow(
        '确认上传失败：name conflict',
      )
    })
  })

  describe('uploadFolderTree', () => {
    it('先建目录再传文件，目录返回的 id 成为子文件的 parentId', async () => {
      createFolder.mockResolvedValue({ data: { id: 99, finalName: 'docs' } })
      const fileMap = new Map([['n2', FILE]])
      const onFileStart = vi.fn()
      const onFileSuccess = vi.fn()
      const onFileError = vi.fn()

      const { successList, failList } = await uploadFolderTree(
        [{ name: 'docs', isLeaf: false, children: [{ id: 'n2', name: 'a.txt', isLeaf: true }] }],
        1,
        {
          fileMap,
          onFileStart,
          onFileSuccess,
          onFileError,
          batchId: 'b1',
          teamId: 2,
          spaceType: 'team',
          projectId: 3,
        },
      )

      expect(createFolder).toHaveBeenCalledWith({
        folderName: 'docs',
        parentId: 1,
        teamId: 2,
        spaceType: 'team',
        projectId: 3,
      })
      expect(getUploadSign).toHaveBeenCalledWith('a.txt')
      // 关键：parentId 用的是新建目录的 id(99)，而不是根 parentId(1)
      expect(confirmUpload).toHaveBeenCalledWith(
        expect.objectContaining({ parentId: 99, clientRequestId: 'docs/a.txt' }),
      )
      expect(failList).toEqual([])
      expect(successList).toHaveLength(2)
      expect(onFileStart).toHaveBeenCalledWith(FILE)
      expect(onFileSuccess).toHaveBeenCalled()
      expect(onFileError).not.toHaveBeenCalled()
    })

    it('嵌套目录的 clientRequestId 逐层带路径前缀', async () => {
      createFolder
        .mockResolvedValueOnce({ data: { id: 1, finalName: 'a' } })
        .mockResolvedValueOnce({ data: { id: 2, finalName: 'b' } })
      // clientRequestId 取的是 File 自身的 name（而非树节点名），故两者保持一致
      const fileC = new File(['x'], 'c.txt')

      await uploadFolderTree(
        [
          {
            name: 'a',
            isLeaf: false,
            children: [
              {
                name: 'b',
                isLeaf: false,
                children: [{ id: 'x', name: 'c.txt', isLeaf: true }],
              },
            ],
          },
        ],
        1,
        { fileMap: new Map([['x', fileC]]) },
      )

      expect(confirmUpload).toHaveBeenCalledWith(
        expect.objectContaining({ parentId: 2, clientRequestId: 'a/b/c.txt' }),
      )
    })

    it('fileMap 里找不到对应文件的叶子节点被跳过，不计入成功或失败', async () => {
      const { successList, failList } = await uploadFolderTree(
        [{ id: 'missing', name: 'a.txt', isLeaf: true }],
        1,
        { fileMap: new Map() },
      )

      expect(getUploadSign).not.toHaveBeenCalled()
      expect(successList).toEqual([])
      expect(failList).toEqual([])
    })

    it('校验不通过的文件直接记为失败，不发上传请求', async () => {
      validateFiles.mockReturnValue({ accepted: [], rejected: ['文件大小超过限制'] })
      const onFileError = vi.fn()

      const { successList, failList } = await uploadFolderTree(
        [{ id: 'n1', name: 'a.txt', isLeaf: true }],
        1,
        { fileMap: new Map([['n1', FILE]]), onFileError },
      )

      expect(getUploadSign).not.toHaveBeenCalled()
      expect(successList).toEqual([])
      expect(failList).toHaveLength(1)
      expect(failList[0]).toMatchObject({ originalName: 'a.txt', message: '文件大小超过限制' })
      expect(onFileError).toHaveBeenCalled()
    })

    it('单个文件失败不影响同批其它文件（错误隔离）', async () => {
      getUploadSign
        .mockRejectedValueOnce(new Error('sign service down'))
        .mockResolvedValueOnce(signResponse())
      const fileB = new File(['world'], 'b.txt')

      const { successList, failList } = await uploadFolderTree(
        [
          { id: 'n1', name: 'a.txt', isLeaf: true },
          { id: 'n2', name: 'b.txt', isLeaf: true },
        ],
        1,
        {
          fileMap: new Map([
            ['n1', FILE],
            ['n2', fileB],
          ]),
        },
      )

      expect(failList).toHaveLength(1)
      expect(successList).toHaveLength(1)
      expect(confirmUpload).toHaveBeenCalledWith(expect.objectContaining({ originalName: 'b.txt' }))
    })

    it('创建目录失败只记一条失败，不阻断其后的兄弟节点', async () => {
      createFolder.mockRejectedValue(new Error('folder conflict'))

      const { successList, failList } = await uploadFolderTree(
        [
          { name: 'docs', isLeaf: false, children: [] },
          { id: 'n1', name: 'a.txt', isLeaf: true },
        ],
        1,
        { fileMap: new Map([['n1', FILE]]) },
      )

      expect(failList).toHaveLength(1)
      expect(failList[0]).toMatchObject({
        originalName: 'docs',
        message: '创建文件夹失败：folder conflict',
      })
      // 兄弟文件仍被正常上传
      expect(successList).toHaveLength(1)
      expect(confirmUpload).toHaveBeenCalledTimes(1)
    })
  })
})
