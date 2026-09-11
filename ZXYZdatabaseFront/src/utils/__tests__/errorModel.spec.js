import { describe, it, expect, vi, beforeEach } from 'vitest'

import {
  createBusinessError,
  getErrorCode,
  getErrorDetail,
  getErrorMessage,
  isHandledByGlobalError,
  isImConversationAccessError,
  logUploadError,
  markGlobalErrorHandled,
} from '@/utils/errorModel'
import { logger } from '@/utils/logger'

// logger 是 Object.freeze 过的对象，vi.spyOn 无法改写其属性，
// 因此这里在模块层面替换掉，只为断言 logUploadError 的落点与载荷。
vi.mock('@/utils/logger', () => ({
  logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('createBusinessError', () => {
  it('用给定文案构造 Error 并挂上 response', () => {
    const response = { status: 400 }
    const error = createBusinessError('参数不对', response)

    expect(error).toBeInstanceOf(Error)
    expect(error.message).toBe('参数不对')
    expect(error.response).toBe(response)
  })

  it('文案为空时回退为"请求失败"', () => {
    expect(createBusinessError('').message).toBe('请求失败')
    expect(createBusinessError(undefined).message).toBe('请求失败')
    expect(createBusinessError(null).message).toBe('请求失败')
  })

  it('extra 字段被合并到 error 上', () => {
    const error = createBusinessError('x', { status: 500 }, { stage: 'confirm', retryable: true })
    expect(error.stage).toBe('confirm')
    expect(error.retryable).toBe(true)
    expect(error.response).toEqual({ status: 500 })
  })

  it('不传 response 时该字段为 undefined（不抛错）', () => {
    expect(createBusinessError('x').response).toBeUndefined()
  })
})

describe('markGlobalErrorHandled / isHandledByGlobalError', () => {
  it('打标后判定为已处理，且返回同一个对象', () => {
    const error = new Error('x')
    expect(isHandledByGlobalError(error)).toBe(false)
    expect(markGlobalErrorHandled(error)).toBe(error)
    expect(isHandledByGlobalError(error)).toBe(true)
  })

  it('标记不可枚举式暴露（挂在内部标志位上）', () => {
    const error = markGlobalErrorHandled(new Error('x'))
    expect(error.__globalErrorHandled__).toBe(true)
  })

  it('对 null / 非对象入参安全返回', () => {
    expect(markGlobalErrorHandled(null)).toBeNull()
    expect(markGlobalErrorHandled(undefined)).toBeUndefined()
    expect(markGlobalErrorHandled('str')).toBe('str')
    expect(markGlobalErrorHandled(42)).toBe(42)
  })

  it('isHandledByGlobalError 对各种空值都返回 false', () => {
    expect(isHandledByGlobalError(null)).toBe(false)
    expect(isHandledByGlobalError(undefined)).toBe(false)
    expect(isHandledByGlobalError({})).toBe(false)
    expect(isHandledByGlobalError({ __globalErrorHandled__: false })).toBe(false)
  })
})

describe('getErrorMessage: 取值优先级', () => {
  it('response.data 为字符串时优先采用（并 trim）', () => {
    expect(getErrorMessage({ response: { data: '  后端说不行  ' } })).toBe('后端说不行')
  })

  it('response.data.msg 次之', () => {
    expect(getErrorMessage({ response: { data: { msg: ' msg 文案 ' } } })).toBe('msg 文案')
  })

  it('response.data.message 再次之', () => {
    expect(getErrorMessage({ response: { data: { message: 'message 文案' } } })).toBe('message 文案')
  })

  it('再退到 error.message', () => {
    expect(getErrorMessage({ message: '本地异常' })).toBe('本地异常')
  })

  it('都不存在时用默认兜底文案', () => {
    expect(getErrorMessage({})).toBe('操作失败，请稍后重试')
    expect(getErrorMessage(null)).toBe('操作失败，请稍后重试')
    expect(getErrorMessage(undefined)).toBe('操作失败，请稍后重试')
  })

  it('支持自定义兜底文案', () => {
    expect(getErrorMessage({}, '自定义兜底')).toBe('自定义兜底')
  })

  it('data 字符串优先于 data.msg（同时存在时取前者）', () => {
    expect(getErrorMessage({ response: { data: { msg: 'msg' } } })).toBe('msg')
    // data 是对象，不是字符串，故落到 msg
    expect(getErrorMessage({ response: { data: 'plain' } })).toBe('plain')
  })

  it('纯空白文案被忽略，继续向下回退', () => {
    expect(getErrorMessage({ response: { data: '   ' }, message: '真实文案' })).toBe('真实文案')
    expect(getErrorMessage({ response: { data: { msg: '  ' } }, message: '真实文案' })).toBe(
      '真实文案',
    )
    expect(getErrorMessage({ message: '   ' }, '兜底')).toBe('兜底')
  })

  it('非字符串的 data（数字/数组/对象）不会被当成文案', () => {
    expect(getErrorMessage({ response: { data: 500 } }, '兜底')).toBe('兜底')
    expect(getErrorMessage({ response: { data: ['a'] } }, '兜底')).toBe('兜底')
    expect(getErrorMessage({ response: { data: {} } }, '兜底')).toBe('兜底')
  })

  it('response 存在但 data 缺失时安全回退', () => {
    expect(getErrorMessage({ response: {} }, '兜底')).toBe('兜底')
    expect(getErrorMessage({ response: { data: null } }, '兜底')).toBe('兜底')
  })
})

describe('getErrorDetail', () => {
  it('复用 getErrorMessage，但默认兜底文案是上传场景的', () => {
    expect(getErrorDetail({})).toBe('上传失败，请稍后重试')
    expect(getErrorDetail({ response: { data: '磁盘满了' } })).toBe('磁盘满了')
    expect(getErrorDetail({}, '自定义')).toBe('自定义')
  })
})

describe('getErrorCode', () => {
  it('取出数字 code', () => {
    expect(getErrorCode({ response: { data: { code: 4030 } } })).toBe(4030)
  })

  it('数字字符串会被归一化为数字', () => {
    expect(getErrorCode({ response: { data: { code: '4401' } } })).toBe(4401)
  })

  it('非数字 / 缺失 / 非有限值一律返回 null', () => {
    expect(getErrorCode({ response: { data: { code: 'abc' } } })).toBeNull()
    expect(getErrorCode({ response: { data: {} } })).toBeNull()
    expect(getErrorCode({ response: {} })).toBeNull()
    expect(getErrorCode({})).toBeNull()
    expect(getErrorCode(null)).toBeNull()
  })

  it('Infinity 不算有限值，返回 null', () => {
    expect(getErrorCode({ response: { data: { code: Infinity } } })).toBeNull()
  })

  it('code 为 0 时返回 0（而不是被当成缺失）', () => {
    expect(getErrorCode({ response: { data: { code: 0 } } })).toBe(0)
  })
})

describe('isImConversationAccessError', () => {
  it.each([4030, 4400, 4401])('code=%i 判定为会话访问错误', (code) => {
    expect(isImConversationAccessError({ response: { data: { code } } })).toBe(true)
  })

  it.each([401, 403, 404, 4010, 500])('code=%i 不判定为会话访问错误', (code) => {
    expect(isImConversationAccessError({ response: { data: { code } } })).toBe(false)
  })

  it('没有 code 时为 false', () => {
    expect(isImConversationAccessError({})).toBe(false)
    expect(isImConversationAccessError(null)).toBe(false)
  })
})

describe('logUploadError', () => {
  it('以 [upload failed] 前缀记录阶段，并带上文件与响应信息', () => {
    const file = { name: 'a.txt', size: 1024 }
    const error = { message: 'boom', response: { status: 500, data: { code: 1 } } }

    logUploadError('confirm', file, error)

    expect(logger.error).toHaveBeenCalledTimes(1)
    const [prefix, payload] = logger.error.mock.calls[0]
    expect(prefix).toBe('[upload failed] confirm')
    expect(payload).toEqual({
      fileName: 'a.txt',
      fileSize: 1024,
      status: 500,
      message: 'boom',
      responseData: { code: 1 },
    })
  })

  it('extra 字段合并进载荷（且可覆盖同名键）', () => {
    logUploadError('sign', { name: 'b.bin', size: 2 }, new Error('x'), {
      conversationId: 9,
      message: '被覆盖',
    })

    const [, payload] = logger.error.mock.calls[0]
    expect(payload.conversationId).toBe(9)
    expect(payload.message).toBe('被覆盖')
  })

  it('file / error 缺失时不抛错，字段为 undefined', () => {
    expect(() => logUploadError('direct', undefined, undefined)).not.toThrow()
    const [, payload] = logger.error.mock.calls[0]
    expect(payload.fileName).toBeUndefined()
    expect(payload.fileSize).toBeUndefined()
    expect(payload.status).toBeUndefined()
  })
})
