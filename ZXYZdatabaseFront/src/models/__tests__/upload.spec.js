import { describe, it, expect } from 'vitest'

import {
  createUploadFailResult,
  createUploadSuccessResult,
  normalizeFolderCreateResult,
  normalizeUploadConfirmResult,
} from '@/models/upload'

const CONFIRM_ARGS = {
  originalName: 'a.txt',
  fileSize: 5,
  parentId: 7,
  clientRequestId: 'r1',
}

describe('models/upload 结果归一化', () => {
  describe('createUploadFailResult', () => {
    it('默认 status 为 FAILED，finalName 回退到 originalName 且不算重命名', () => {
      const r = createUploadFailResult({ originalName: 'a.txt' })

      expect(r).toMatchObject({
        status: 'FAILED',
        originalName: 'a.txt',
        finalName: 'a.txt',
        renamed: false,
        type: 1,
        size: 0,
        id: null,
        parentId: null,
        fileUrl: '',
      })
    })

    it('finalName 与 originalName 不同时才标记 renamed', () => {
      expect(createUploadFailResult({ originalName: 'a.txt', finalName: 'a(1).txt' }).renamed).toBe(
        true,
      )
    })

    it('extra 可覆盖默认字段（如补 batchId）', () => {
      const r = createUploadFailResult({ originalName: 'a.txt', extra: { batchId: 'b1' } })
      expect(r.batchId).toBe('b1')
    })
  })

  describe('createUploadSuccessResult', () => {
    it('status 为 SUCCESS 且 message 为空串', () => {
      const r = createUploadSuccessResult({ originalName: 'a.txt' })

      expect(r).toMatchObject({
        status: 'SUCCESS',
        message: '',
        renamed: false,
        finalName: 'a.txt',
      })
    })
  })

  describe('normalizeFolderCreateResult', () => {
    it('对象响应：采用响应字段，缺失时回退请求名与入参 parentId', () => {
      const r = normalizeFolderCreateResult({ id: 9, originalName: 'docs' }, 'docs', 3)

      expect(r).toMatchObject({
        status: 'SUCCESS',
        id: 9,
        originalName: 'docs',
        finalName: 'docs',
        parentId: 3,
        type: 0,
        size: 0,
      })
    })

    it('fileType 为 0 时保留 0（用 ?? 而非 ||，否则会被默认值吃掉）', () => {
      expect(normalizeFolderCreateResult({ id: 1, fileType: 0 }, 'd', 1).type).toBe(0)
    })

    it('响应未带 parentId 时回退到入参 parentId', () => {
      expect(normalizeFolderCreateResult({ id: 1 }, 'd', 7).parentId).toBe(7)
    })

    it('标量响应退化为「只有 id」的成功结果', () => {
      const r = normalizeFolderCreateResult(12, 'd', 1)

      expect(r).toMatchObject({ status: 'SUCCESS', id: 12, originalName: 'd', finalName: 'd' })
    })

    it('null 响应时 id 为 null，但仍算成功', () => {
      const r = normalizeFolderCreateResult(null, 'd', 1)

      expect(r.status).toBe('SUCCESS')
      expect(r.id).toBeNull()
    })

    it('数组响应会落进标量分支，把整个数组当成 id（当前行为，属隐患）', () => {
      // 后端的「数组 vs 对象」不一致正是本层要挡的故障类型；
      // 这里记录现状，若将来改为显式拒绝数组，本用例应同步更新。
      const r = normalizeFolderCreateResult([{ id: 1 }], 'd', 1)

      expect(r.id).toEqual([{ id: 1 }])
    })
  })

  describe('normalizeUploadConfirmResult', () => {
    it('items 首个元素成功时映射为成功结果，并取用其 clientRequestId', () => {
      const r = normalizeUploadConfirmResult(
        { items: [{ status: 'success', fileId: 42, fileSize: 9, clientRequestId: 'x' }] },
        CONFIRM_ARGS,
      )

      expect(r).toMatchObject({ status: 'SUCCESS', id: 42, size: 9, clientRequestId: 'x' })
    })

    it('item.status 非 success 时抛出后端 msg（大小写不敏感）', () => {
      expect(() =>
        normalizeUploadConfirmResult(
          { items: [{ status: 'FAIL', msg: '名字冲突' }] },
          CONFIRM_ARGS,
        ),
      ).toThrow('名字冲突')

      expect(() =>
        normalizeUploadConfirmResult({ items: [{ status: 'Failed' }] }, CONFIRM_ARGS),
      ).toThrow('上传确认失败')
    })

    it('item.code 为数字且不为 1 时抛错；为 1 时通过', () => {
      expect(() =>
        normalizeUploadConfirmResult({ items: [{ code: 0, msg: '配额不足' }] }, CONFIRM_ARGS),
      ).toThrow('配额不足')

      expect(normalizeUploadConfirmResult({ items: [{ code: 1 }] }, CONFIRM_ARGS).status).toBe(
        'SUCCESS',
      )
    })

    it('code 为字符串时不触发 code 校验（只判数字类型，不误伤 "1"）', () => {
      const r = normalizeUploadConfirmResult(
        { items: [{ code: '0', status: 'success' }] },
        CONFIRM_ARGS,
      )

      expect(r.status).toBe('SUCCESS')
    })

    it('items 为空数组时明确报错，而不是静默当成功', () => {
      expect(() => normalizeUploadConfirmResult({ items: [] }, CONFIRM_ARGS)).toThrow(
        '上传确认结果为空',
      )
    })

    it('没有 items 字段时走单对象兼容路径', () => {
      const r = normalizeUploadConfirmResult({ id: 5, originalName: 'b.txt' }, CONFIRM_ARGS)

      expect(r).toMatchObject({ status: 'SUCCESS', id: 5, originalName: 'b.txt' })
    })

    it('空对象时逐级回退到入参的 name/size/parentId/clientRequestId', () => {
      const r = normalizeUploadConfirmResult({}, CONFIRM_ARGS)

      expect(r).toMatchObject({
        status: 'SUCCESS',
        originalName: 'a.txt',
        finalName: 'a.txt',
        size: 5,
        parentId: 7,
        clientRequestId: 'r1',
        type: 1,
      })
    })

    it('字符串响应被当作 fileUrl', () => {
      const r = normalizeUploadConfirmResult('https://cdn.example/f.txt', CONFIRM_ARGS)

      expect(r).toMatchObject({ status: 'SUCCESS', fileUrl: 'https://cdn.example/f.txt' })
    })

    it('null 响应时 fileUrl 为空串，仍算成功', () => {
      const r = normalizeUploadConfirmResult(null, CONFIRM_ARGS)

      expect(r.status).toBe('SUCCESS')
      expect(r.fileUrl).toBe('')
    })

    it('后端改名时标记 renamed', () => {
      const r = normalizeUploadConfirmResult(
        { items: [{ status: 'success', finalName: 'a(1).txt' }] },
        CONFIRM_ARGS,
      )

      expect(r).toMatchObject({ finalName: 'a(1).txt', renamed: true })
    })
  })
})
