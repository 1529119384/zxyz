import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'

import { logger } from '@/utils/logger'

// logger 的每条方法都在调用时读 import.meta.env.DEV，所以可以逐用例改写它
// 来同时覆盖「开发放行」与「生产抑制」两条分支。
const originalDev = import.meta.env.DEV

let debugSpy
let infoSpy
let warnSpy
let errorSpy

beforeEach(() => {
  debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
  infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {})
  warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
  errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  import.meta.env.DEV = originalDev
  vi.restoreAllMocks()
})

describe('logger: 形状', () => {
  it('暴露 debug / info / warn / error 四个方法', () => {
    for (const level of ['debug', 'info', 'warn', 'error']) {
      expect(typeof logger[level], level).toBe('function')
    }
  })

  it('对象被冻结（约定：全局单例，不允许被替换方法）', () => {
    expect(Object.isFrozen(logger)).toBe(true)
    expect(() => {
      logger.debug = () => {}
    }).toThrow()
  })
})

describe('logger: 开发模式（DEV 为真）', () => {
  beforeEach(() => {
    import.meta.env.DEV = true
  })

  it('debug / info / warn 都会转发到 console', () => {
    logger.debug('d')
    logger.info('i')
    logger.warn('w')

    expect(debugSpy).toHaveBeenCalledWith('d')
    expect(infoSpy).toHaveBeenCalledWith('i')
    expect(warnSpy).toHaveBeenCalledWith('w')
  })

  it('原样透传全部参数与对象引用', () => {
    const payload = { a: 1 }
    logger.warn('[ctx]', payload, 42)

    expect(warnSpy).toHaveBeenCalledWith('[ctx]', payload, 42)
    expect(warnSpy.mock.calls[0][1]).toBe(payload)
  })
})

describe('logger: 生产模式（DEV 为假）', () => {
  beforeEach(() => {
    import.meta.env.DEV = false
  })

  it('debug / info / warn 被静默抑制（不打到 console）', () => {
    logger.debug('d')
    logger.info('i')
    logger.warn('w')

    expect(debugSpy).not.toHaveBeenCalled()
    expect(infoSpy).not.toHaveBeenCalled()
    expect(warnSpy).not.toHaveBeenCalled()
  })

  it('error 始终放行——生产也要能看到错误', () => {
    logger.error('boom')

    expect(errorSpy).toHaveBeenCalledWith('boom')
  })

  it('被抑制时不抛错，也不产生返回值', () => {
    expect(() => logger.debug('x')).not.toThrow()
    expect(logger.info('x')).toBeUndefined()
  })
})

describe('logger: error 与 DEV 无关', () => {
  it('两种模式下 error 都转发', () => {
    import.meta.env.DEV = false
    logger.error('one')
    import.meta.env.DEV = true
    logger.error('two')

    expect(errorSpy).toHaveBeenNthCalledWith(1, 'one')
    expect(errorSpy).toHaveBeenNthCalledWith(2, 'two')
  })
})
