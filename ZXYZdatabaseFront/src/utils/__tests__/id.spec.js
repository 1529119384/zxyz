import { describe, it, expect, vi, afterEach } from 'vitest'

import { createClientId, normalizePositiveId } from '@/utils/id'

describe('normalizePositiveId', () => {
  it('数字与数字字符串都转成正整数', () => {
    expect(normalizePositiveId(42)).toBe(42)
    expect(normalizePositiveId('42')).toBe(42)
  })

  it('零与负数返回 null', () => {
    expect(normalizePositiveId(0)).toBeNull()
    expect(normalizePositiveId(-1)).toBeNull()
    expect(normalizePositiveId('-5')).toBeNull()
  })

  it('非数字与空值返回 null', () => {
    expect(normalizePositiveId(null)).toBeNull()
    expect(normalizePositiveId(undefined)).toBeNull()
    expect(normalizePositiveId('abc')).toBeNull()
    expect(normalizePositiveId({})).toBeNull()
    expect(normalizePositiveId([])).toBeNull()
    expect(normalizePositiveId(NaN)).toBeNull()
    expect(normalizePositiveId(Infinity)).toBeNull()
  })

  it('浮点数返回 null（要求安全整数）', () => {
    expect(normalizePositiveId(1.5)).toBeNull()
    expect(normalizePositiveId('3.14')).toBeNull()
  })

  it('安全整数边界：MAX_SAFE_INTEGER 通过，超出返回 null', () => {
    expect(normalizePositiveId(Number.MAX_SAFE_INTEGER)).toBe(Number.MAX_SAFE_INTEGER)
    expect(normalizePositiveId(Number.MAX_SAFE_INTEGER + 2)).toBeNull()
  })
})

describe('createClientId', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('有 randomUUID 时直接返回 UUID，带前缀则拼接', () => {
    const uuid = '11111111-2222-3333-4444-555555555555'
    const randomUUID = vi.fn(() => uuid)
    vi.stubGlobal('crypto', { randomUUID })
    expect(createClientId()).toBe(uuid)
    expect(createClientId('msg')).toBe(`msg-${uuid}`)
    expect(randomUUID).toHaveBeenCalledTimes(2)
  })

  it('无 randomUUID 但有 getRandomValues 时生成 UUID v4 兜底', () => {
    const bytes = new Uint8Array(16).fill(0xab)
    vi.stubGlobal('crypto', {
      getRandomValues: (arr) => arr.set(bytes.subarray(0, arr.length)),
    })
    const result = createClientId()
    // 版本位（第 3 段首位为 4）与变体位（第 4 段首位为 8/9/a/b）应被修正
    expect(result).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    // getRandomValues 是确定性填充，两次调用生成同一个 UUID
    expect(createClientId('p')).toBe(`p-${result}`)
  })

  it('crypto 存在但两个方法都不可用时兜底为时间戳+随机数', () => {
    vi.stubGlobal('crypto', {})
    const result = createClientId()
    expect(result).toMatch(/^[0-9a-z]+-[0-9a-z]+$/)
    expect(createClientId('p')).toMatch(/^p-[0-9a-z]+-[0-9a-z]+$/)
  })

  it('完全没有 crypto 时同样兜底为时间戳+随机数', () => {
    vi.stubGlobal('crypto', undefined)
    expect(createClientId()).toMatch(/^[0-9a-z]+-[0-9a-z]+$/)
  })
})
