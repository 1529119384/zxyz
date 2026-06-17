import { describe, it, expect } from 'vitest'

import { fmtTime, formatSize, formatStorageText, GB } from '@/utils/format'

describe('fmtTime', () => {
  it('should return - for null/undefined/empty', () => {
    expect(fmtTime(null)).toBe('-')
    expect(fmtTime(undefined)).toBe('-')
    expect(fmtTime('')).toBe('-')
  })

  it('should return datetime string as-is if already formatted', () => {
    expect(fmtTime('2026-01-15 10:30:00')).toBe('2026-01-15 10:30:00')
  })

  it('should parse ISO date string', () => {
    const result = fmtTime('2026-01-15T10:30:00')
    expect(result).toContain('2026')
    expect(result).toContain('10:30')
  })

  it('should return raw value for invalid date', () => {
    expect(fmtTime('not-a-date')).toBe('not-a-date')
  })
})

describe('formatSize', () => {
  it('should return - for null/undefined/empty', () => {
    expect(formatSize(null)).toBe('-')
    expect(formatSize(undefined)).toBe('-')
    expect(formatSize('')).toBe('-')
  })

  it('should return - for negative values', () => {
    expect(formatSize(-1)).toBe('-')
  })

  it('should return 0 B for zero', () => {
    expect(formatSize(0)).toBe('0 B')
  })

  it('should format bytes', () => {
    expect(formatSize(500)).toBe('500 B')
  })

  it('should format KB', () => {
    expect(formatSize(1024)).toBe('1.00 KB')
  })

  it('should format MB', () => {
    expect(formatSize(1048576)).toBe('1.00 MB')
  })

  it('should format GB', () => {
    expect(formatSize(1073741824)).toBe('1.00 GB')
  })
})

describe('formatStorageText', () => {
  it('should return -- for null', () => {
    expect(formatStorageText(null)).toBe('--')
  })

  it('should format valid size', () => {
    expect(formatStorageText(1048576)).toBe('1.00 MB')
  })
})

describe('GB constant', () => {
  it('should equal 1024^3', () => {
    expect(GB).toBe(1073741824)
  })
})
