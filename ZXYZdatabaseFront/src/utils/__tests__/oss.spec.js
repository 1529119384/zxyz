import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

import { uploadToBackend, uploadToOss } from '@/utils/oss'

const DEFAULT_UPLOAD_TIMEOUT = 5 * 60 * 1000

// 用假 XHR 替换全局实现：既能同步驱动 onload / onerror / ontimeout / progress，
// 又不会真的发起网络请求。OSS 直传是本项目最易出问题的一环（曾因 nginx CSP 的
// connect-src 未放行 OSS 主机而只报 "network error"），因此每个失败分支都要钉住。
class FakeXHR {
  static instances = []

  constructor() {
    this.headers = {}
    this.upload = {}
    this.status = 0
    this.responseText = ''
    this.timeout = 0
    this.body = undefined
    FakeXHR.instances.push(this)
  }

  open(method, url, async) {
    this.method = method
    this.url = url
    this.async = async
  }

  setRequestHeader(name, value) {
    this.headers[name] = value
  }

  send(body) {
    this.body = body
  }

  emitLoad(status, responseText = '') {
    this.status = status
    this.responseText = responseText
    this.onload?.()
  }

  emitError() {
    this.onerror?.()
  }

  emitTimeout() {
    this.ontimeout?.()
  }

  emitProgress(loaded, total, lengthComputable = true) {
    this.upload.onprogress?.({ loaded, total, lengthComputable })
  }
}

function lastXHR() {
  return FakeXHR.instances[FakeXHR.instances.length - 1]
}

describe('oss：预签名直传与后端直传', () => {
  let originalXHR

  beforeEach(() => {
    originalXHR = globalThis.XMLHttpRequest
    globalThis.XMLHttpRequest = FakeXHR
    FakeXHR.instances = []
  })

  afterEach(() => {
    globalThis.XMLHttpRequest = originalXHR
  })

  describe('uploadToOss', () => {
    const url = 'https://bucket.oss-cn-shenzhen.aliyuncs.com/key?a=1&signature=x'

    it('以 PUT 把文件原样发出，并使用默认 5 分钟超时', () => {
      const file = new File(['hello'], 'a.txt')
      uploadToOss(url, file)

      const xhr = lastXHR()
      expect(xhr.method).toBe('PUT')
      expect(xhr.url).toBe(url)
      expect(xhr.async).toBe(true)
      expect(xhr.body).toBe(file)
      expect(xhr.timeout).toBe(DEFAULT_UPLOAD_TIMEOUT)
    })

    it('未传 contentType/contentDisposition 时不设置任何请求头', () => {
      uploadToOss(url, new File(['x'], 'a.txt'))
      expect(lastXHR().headers).toEqual({})
    })

    it('传了 contentType/contentDisposition 时按原值设置', () => {
      uploadToOss(url, new File(['x'], 'a.txt'), {
        contentType: 'text/plain',
        contentDisposition: 'attachment; filename="a.txt"',
      })

      expect(lastXHR().headers).toEqual({
        'Content-Type': 'text/plain',
        'Content-Disposition': 'attachment; filename="a.txt"',
      })
    })

    it('未传 onUploadProgress 时不注册 upload.onprogress', () => {
      uploadToOss(url, new File(['x'], 'a.txt'))
      expect(lastXHR().upload.onprogress).toBeUndefined()
    })

    it('进度回调换算为四舍五入的百分比', () => {
      const onUploadProgress = vi.fn()
      uploadToOss(url, new File(['x'], 'a.txt'), { onUploadProgress })

      lastXHR().emitProgress(1, 3)
      expect(onUploadProgress).toHaveBeenCalledWith({ loaded: 1, total: 3, percent: 33 })

      lastXHR().emitProgress(2, 3)
      expect(onUploadProgress).toHaveBeenLastCalledWith({ loaded: 2, total: 3, percent: 67 })
    })

    it('lengthComputable 为 false 时不上报进度（避免算出 NaN%）', () => {
      const onUploadProgress = vi.fn()
      uploadToOss(url, new File(['x'], 'a.txt'), { onUploadProgress })

      lastXHR().emitProgress(10, 0, false)
      expect(onUploadProgress).not.toHaveBeenCalled()
    })

    it('2xx 视为成功', async () => {
      const settled = uploadToOss(url, new File(['x'], 'a.txt')).then(
        () => 'resolved',
        () => 'rejected',
      )
      lastXHR().emitLoad(204)
      expect(await settled).toBe('resolved')
    })

    it('非 2xx（如 403）时 reject，并把状态码与响应体挂到 error.response', async () => {
      const settled = uploadToOss(url, new File(['x'], 'a.txt')).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitLoad(403, '<Error>AccessDenied</Error>')

      const err = await settled
      expect(err.message).toBe('OSS upload failed: status 403')
      expect(err.response).toEqual({ status: 403, data: '<Error>AccessDenied</Error>' })
    })

    it('网络错误（如 CSP 拦下 PUT）报 network error，且状态回退为 0', async () => {
      const settled = uploadToOss(url, new File(['x'], 'a.txt')).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitError()

      const err = await settled
      expect(err.message).toBe('OSS upload failed: network error')
      expect(err.response).toEqual({ status: 0, data: '' })
    })

    it('超时时报 timeout，且不携带响应数据', async () => {
      const settled = uploadToOss(url, new File(['x'], 'a.txt')).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitTimeout()

      const err = await settled
      expect(err.message).toBe('OSS upload failed: timeout')
      expect(err.response).toEqual({ status: 0, data: null })
    })
  })

  describe('uploadToBackend', () => {
    const file = new File(['hello'], 'a.txt')

    it('POST 到后端直传接口，且不手动设置 Content-Type（留给浏览器补 boundary）', () => {
      uploadToBackend(file)

      const xhr = lastXHR()
      expect(xhr.method).toBe('POST')
      expect(xhr.url).toBe('/api/files/uploads/direct')
      expect(xhr.headers).toEqual({})
      expect(xhr.timeout).toBe(DEFAULT_UPLOAD_TIMEOUT)
      expect(xhr.body).toBeInstanceOf(FormData)
      expect(xhr.body.get('file').name).toBe('a.txt')
    })

    it('定位字段为 null/undefined 时不追加', () => {
      uploadToBackend(file, null, undefined, null, undefined)

      const body = lastXHR().body
      expect(body.get('parentId')).toBeNull()
      expect(body.get('teamId')).toBeNull()
      expect(body.get('spaceType')).toBeNull()
      expect(body.get('projectId')).toBeNull()
    })

    it('值为 0 或空串时仍会追加（判据是 != null，不能退化成真值判断）', () => {
      uploadToBackend(file, 0, 0, '', 0)

      const body = lastXHR().body
      expect(body.get('parentId')).toBe('0')
      expect(body.get('teamId')).toBe('0')
      expect(body.get('spaceType')).toBe('')
      expect(body.get('projectId')).toBe('0')
    })

    it('定位字段统一转成字符串后发送', () => {
      uploadToBackend(file, 123, 456, 'team', 789)

      const body = lastXHR().body
      expect(body.get('parentId')).toBe('123')
      expect(body.get('teamId')).toBe('456')
      expect(body.get('spaceType')).toBe('team')
      expect(body.get('projectId')).toBe('789')
    })

    it('2xx 且响应是 JSON 时 resolve 解析后的对象', async () => {
      const settled = uploadToBackend(file, 1, 2, 'team', 3).then(
        (v) => ({ ok: true, v }),
        (e) => ({ ok: false, e }),
      )
      lastXHR().emitLoad(200, JSON.stringify({ fileId: 'f1', name: 'a.txt' }))

      const r = await settled
      expect(r.ok).toBe(true)
      expect(r.v).toEqual({ fileId: 'f1', name: 'a.txt' })
    })

    it('2xx 但响应不是 JSON 时回退为原始文本，不因此抛错', async () => {
      const settled = uploadToBackend(file, 1, 2, 'team', 3).then(
        (v) => ({ ok: true, v }),
        (e) => ({ ok: false, e }),
      )
      lastXHR().emitLoad(200, 'OK')

      const r = await settled
      expect(r.ok).toBe(true)
      expect(r.v).toBe('OK')
    })

    it('非 2xx 时 reject，并带上状态码与响应体', async () => {
      const settled = uploadToBackend(file, 1, 2, 'team', 3).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitLoad(500, 'boom')

      const err = await settled
      expect(err.message).toBe('Backend upload failed: status 500')
      expect(err.response).toEqual({ status: 500, data: 'boom' })
    })

    it('网络错误时报 network error，状态回退为 0', async () => {
      const settled = uploadToBackend(file).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitError()

      const err = await settled
      expect(err.message).toBe('Backend upload failed: network error')
      expect(err.response).toEqual({ status: 0, data: '' })
    })

    it('超时时报 timeout，data 为 null', async () => {
      const settled = uploadToBackend(file).then(
        () => null,
        (e) => e,
      )
      lastXHR().emitTimeout()

      const err = await settled
      expect(err.message).toBe('Backend upload failed: timeout')
      expect(err.response).toEqual({ status: 0, data: null })
    })
  })
})
