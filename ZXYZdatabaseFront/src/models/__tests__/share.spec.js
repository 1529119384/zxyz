// @ts-check
import { describe, expect, it } from 'vitest'

import {
  buildShareMessage,
  formatShareExpireText,
  generateSharePassword,
  getShareTargetTitle,
  isValidSharePassword,
  mapMyShareRecords,
  mapShareFileEntries,
  sanitizeSharePassword,
  splitSharePath,
} from '@/models/share'

describe('mapMyShareRecords', () => {
  it('reads the new PageResult envelope { page, pageSize, total, list }', () => {
    const result = mapMyShareRecords({
      page: 2,
      pageSize: 10,
      total: 41,
      list: [{ shareId: 1, shareUrl: 'https://example.test/s/abc' }],
    })

    expect(result.page).toBe(2)
    expect(result.pageSize).toBe(10)
    expect(result.total).toBe(41)
    expect(result.list).toHaveLength(1)
  })

  it('still reads the legacy { total, rows } envelope', () => {
    // 07-P1-4 把后端信封从 {total, rows} 换成了 PageResult。这里必须**仍然认 rows**：
    // 前后端同批上线时浏览器可能还持有旧 chunk，只认 list 会让「我的分享」整页空白。
    const result = mapMyShareRecords({ total: 3, rows: [{ shareId: 2 }] })

    expect(result.list).toHaveLength(1)
    expect(result.total).toBe(3)
    // 旧信封没有页码信息，页码回落到第 1 页（不是 0 / NaN）。
    expect(result.page).toBe(1)
  })

  it('prefers list over rows when both are present', () => {
    const result = mapMyShareRecords({
      total: 2,
      list: [{ shareId: 1 }],
      rows: [{ shareId: 1 }, { shareId: 2 }],
    })

    expect(result.list).toHaveLength(1)
  })

  it('degrades to an empty page instead of throwing on a missing payload', () => {
    expect(mapMyShareRecords()).toEqual({ page: 1, pageSize: 0, total: 0, list: [] })
    expect(mapMyShareRecords({})).toEqual({ page: 1, pageSize: 0, total: 0, list: [] })
  })

  it('does not mistake a non-array list for records', () => {
    // 契约外返回值（例如把 list 给成了字符串）不能把列表搞崩。
    /** @type {any} */
    const malformed = { list: 'nope' }
    const result = mapMyShareRecords(malformed)

    expect(result.list).toEqual([])
  })
})

describe('share 文案 / 口令 / 条目归一化辅助', () => {
  it('buildShareMessage 覆盖带与不带提取码两种文案', () => {
    expect(buildShareMessage('https://example.test/s/a1')).toBe(
      '指绣云章给你分享了文件：https://example.test/s/a1',
    )
    expect(buildShareMessage('https://example.test/s/a1', 'ab12')).toBe(
      '指绣云章给你分享了文件：https://example.test/s/a1，提取码为：ab12',
    )
  })

  it('generateSharePassword 生成 4 位且剔除易混淆字符', () => {
    // 随机函数无法断言具体值，断言的是**不变式**：长度 4、字符集内、无 I/l/1/O/0。
    for (let i = 0; i < 50; i += 1) {
      const password = generateSharePassword()
      expect(password).toHaveLength(4)
      expect(password).not.toMatch(/[Il1O0]/)
    }
  })

  it('sanitizeSharePassword 只留字母数字并截到 4 位', () => {
    expect(sanitizeSharePassword('ab-12!')).toBe('ab12')
    expect(sanitizeSharePassword('abcdefgh')).toBe('abcd')
    expect(sanitizeSharePassword('')).toBe('')
    expect(sanitizeSharePassword(undefined)).toBe('')
    expect(sanitizeSharePassword(1234)).toBe('1234')
  })

  it('isValidSharePassword 只认 4 位字母数字', () => {
    expect(isValidSharePassword('ab12')).toBe(true)
    expect(isValidSharePassword('ab1')).toBe(false)
    expect(isValidSharePassword('ab-1')).toBe(false)
    expect(isValidSharePassword(undefined)).toBe(false)
  })

  it('getShareTargetTitle 覆盖空 / 单项 / 多项三种文案', () => {
    expect(getShareTargetTitle([])).toBe('')
    expect(getShareTargetTitle([{ fileName: 'a.pdf' }])).toBe('a.pdf')
    expect(getShareTargetTitle([{ fileName: 'a.pdf' }, { fileName: 'b' }, { fileName: 'c' }])).toBe(
      'a.pdf等 3 项',
    )
    expect(getShareTargetTitle([{ fileName: '' }, {}, {}])).toBe('所选内容等 3 项')
  })

  it('formatShareExpireText 分「永久有效」与「具体时间」两路', () => {
    expect(formatShareExpireText({ expireType: 'forever' })).toBe('永久有效')
    expect(formatShareExpireText({ expireType: '7d', expireTime: null })).toBe('永久有效')
    expect(formatShareExpireText({})).toBe('永久有效')

    const text = formatShareExpireText({ expireType: '7d', expireTime: '2026-09-20 10:00:00' })
    expect(typeof text).toBe('string')
    expect(text).not.toBe('永久有效')
  })

  it('mapShareFileEntries 归一化条目，非数组时降级为空', () => {
    const entries = mapShareFileEntries([
      { fileId: 1, fileName: 'a.pdf', fileType: 1, size: 10 },
      { fileId: 2, fileName: 'dir', isFolder: true },
    ])

    expect(entries).toHaveLength(2)
    expect(entries[0].id).toBe(1)
    expect(entries[0].fileName).toBe('a.pdf')

    expect(mapShareFileEntries()).toEqual([])
    expect(mapShareFileEntries({ nope: true })).toEqual([])
  })

  it('splitSharePath 返回面包屑数组', () => {
    const crumbs = splitSharePath('/a/b')

    expect(Array.isArray(crumbs)).toBe(true)
    expect(crumbs.length).toBeGreaterThan(0)
  })
})
