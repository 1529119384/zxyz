import { describe, it, expect, vi } from 'vitest'

vi.mock('@/utils/env', () => ({
  requireViteEnv: vi.fn((name) => {
    throw new Error(`${name} 未配置（测试中不实际连接）`)
  }),
  resolveWebSocketUrl: vi.fn((url) => url),
}))

vi.mock('@/utils/imRequest', () => ({
  default: { post: vi.fn().mockResolvedValue({ data: '' }) },
}))

vi.mock('@/utils/id', () => ({
  createClientId: vi.fn(() => 'test-client-id'),
}))

import { computeReconnectDelay } from '@/utils/imWebSocket'

describe('imWebSocket 重连策略', () => {
  describe('computeReconnectDelay 指数退避', () => {
    it('基线延迟为 1s', () => {
      expect(computeReconnectDelay(0)).toBe(1000)
    })

    it('每次失败延迟翻倍（1s→2s→4s）', () => {
      expect(computeReconnectDelay(1)).toBe(2000)
      expect(computeReconnectDelay(2)).toBe(4000)
    })

    it('最大值封顶在 30s，不再无限增长', () => {
      expect(computeReconnectDelay(5)).toBe(30000)
      expect(computeReconnectDelay(10)).toBe(30000)
      expect(computeReconnectDelay(20)).toBe(30000)
    })
  })
})
