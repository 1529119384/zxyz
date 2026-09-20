// @ts-check
import { describe, expect, it } from 'vitest'

import { mapEmailRecordPage } from '@/models/emailRecord'

describe('models/emailRecord', () => {
  it('reads the unified PageResult envelope (list)', () => {
    const result = mapEmailRecordPage({
      page: 2,
      pageSize: 20,
      total: 41,
      list: [{ id: 1 }, { id: 2 }],
    })

    expect(result.list).toEqual([{ id: 1 }, { id: 2 }])
    expect(result.total).toBe(41)
    expect(result.page).toBe(2)
    expect(result.pageSize).toBe(20)
  })

  it('falls back to the legacy records field when list is absent', () => {
    // 部署窗口期：浏览器可能拿到「新后端 + 旧前端产物」或反之，这里必须两条路都能读。
    const result = mapEmailRecordPage({ page: 1, pageSize: 10, total: 3, records: [{ id: 7 }] })

    expect(result.list).toEqual([{ id: 7 }])
    expect(result.total).toBe(3)
  })

  it('prefers list over records when both are present', () => {
    const result = mapEmailRecordPage({
      list: [{ id: 'new' }],
      records: [{ id: 'legacy' }],
    })

    expect(result.list).toEqual([{ id: 'new' }])
  })

  it('degrades to an empty page instead of throwing on odd payloads', () => {
    expect(mapEmailRecordPage().list).toEqual([])
    expect(mapEmailRecordPage({}).list).toEqual([])
    expect(mapEmailRecordPage({ list: 'not-an-array' }).list).toEqual([])
    expect(mapEmailRecordPage({ records: null }).list).toEqual([])
    expect(mapEmailRecordPage({ list: [], total: 'abc' }).total).toBe(0)
  })

  it('reports 0 for missing page/pageSize so the pager keeps its local value', () => {
    // usePagedList 只在 >= 1 时才采纳回传值；0 必须被忽略，否则页长会被改成 0。
    const result = mapEmailRecordPage({ list: [] })

    expect(result.page).toBe(0)
    expect(result.pageSize).toBe(0)
  })
})
