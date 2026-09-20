import { describe, expect, it } from 'vitest'

import {
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  PAGE_SIZE_BY_CONTEXT,
  PAGE_SIZE_OPTIONS,
  SPACE_PAGE_SIZE_OPTIONS,
  normalizePageSize,
  resolvePageSize,
} from '@/constants/pagination'

describe('constants/pagination', () => {
  it('mirrors the backend default and upper bound', () => {
    // 与 zxyz-common 的 PageResult 保持一致，漂了就会让「前端算的总页数」和「后端给的页」对不上。
    expect(DEFAULT_PAGE_SIZE).toBe(20)
    expect(MAX_PAGE_SIZE).toBe(200)
  })

  it('keeps every context page size equal to the value it replaced', () => {
    // 这四个数字是 07-P1-4 之前散在四个文件里的字面量。收敛之后必须逐个对齐原值 ——
    // 一旦哪个对不上，就是「顺手改了用户看到的每页条数」这种契约外变更。
    expect(resolvePageSize('myShare')).toBe(10)
    expect(resolvePageSize('recycleBin')).toBe(20)
    expect(resolvePageSize('spaceFiles')).toBe(50)
    expect(resolvePageSize('fileSearch')).toBe(20)
  })

  it('falls back to the backend default for unknown contexts', () => {
    expect(resolvePageSize(undefined)).toBe(DEFAULT_PAGE_SIZE)
    expect(resolvePageSize('')).toBe(DEFAULT_PAGE_SIZE)
    expect(resolvePageSize('no-such-context')).toBe(DEFAULT_PAGE_SIZE)
  })

  it('normalizes page sizes with the same rules as the backend', () => {
    expect(normalizePageSize(10)).toBe(10)
    expect(normalizePageSize('25')).toBe(25)
    expect(normalizePageSize(20.7)).toBe(20)
    // 超上限必须被钳制：否则前端按它算总页数会与后端不一致。
    expect(normalizePageSize(9999)).toBe(MAX_PAGE_SIZE)
    expect(normalizePageSize(0)).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize(-5)).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize(undefined)).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize(null)).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize('abc')).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize(Number.NaN)).toBe(DEFAULT_PAGE_SIZE)
    expect(normalizePageSize(Number.POSITIVE_INFINITY)).toBe(DEFAULT_PAGE_SIZE)
  })

  it('exposes the pagination option lists used by el-pagination', () => {
    expect(PAGE_SIZE_OPTIONS).toEqual([10, 20, 50])
    expect(SPACE_PAGE_SIZE_OPTIONS).toEqual([20, 50, 100, 200])
  })

  it('freezes the context table so it cannot be mutated at runtime', () => {
    expect(Object.isFrozen(PAGE_SIZE_BY_CONTEXT)).toBe(true)
  })
})
