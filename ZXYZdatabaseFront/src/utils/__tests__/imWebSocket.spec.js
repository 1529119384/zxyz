import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('@/utils/env', () => ({
  requireViteEnv: vi.fn(() => 'ws://im.test/ws'),
  resolveWebSocketUrl: vi.fn((url) => url),
}))

vi.mock('@/utils/imRequest', () => ({
  default: { post: vi.fn().mockResolvedValue({ data: 'test-ticket' }) },
}))

vi.mock('@/utils/id', () => ({
  createClientId: vi.fn(() => 'test-client-id'),
}))

import { computeReconnectDelay, createImWebSocketClient, IM_WS_STATUS } from '@/utils/imWebSocket'

// 用假 WebSocket 替换全局实现，既能驱动 onopen/onclose，又不会真的发起网络连接。
class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances = []

  constructor(url, protocols) {
    this.url = url
    this.protocols = protocols
    this.readyState = FakeWebSocket.CONNECTING
    FakeWebSocket.instances.push(this)
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({})
  }
}

function lastSocket() {
  return FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
}

describe('imWebSocket 重连策略', () => {
  describe('computeReconnectDelay 指数退避 + 抖动', () => {
    it('抖动因子取中值时基线延迟为 1s', () => {
      expect(computeReconnectDelay(0, 0.5)).toBe(1000)
    })

    it('每次失败延迟翻倍（1s→2s→4s，抖动因子固定）', () => {
      expect(computeReconnectDelay(1, 0.5)).toBe(2000)
      expect(computeReconnectDelay(2, 0.5)).toBe(4000)
    })

    it('抖动因子把延迟压缩/放大到 ±30%', () => {
      expect(computeReconnectDelay(0, 0)).toBe(700)
      expect(computeReconnectDelay(2, 0)).toBe(2800)
      // 0.7 + 1 * 0.6 存在浮点误差，用近似断言
      expect(computeReconnectDelay(0, 1)).toBeCloseTo(1300, 6)
      expect(computeReconnectDelay(2, 1)).toBeCloseTo(5200, 6)
    })

    it('默认随机抖动的结果始终落在 [0.7x, 1.3x] 区间内', () => {
      for (let i = 0; i < 50; i += 1) {
        const delay = computeReconnectDelay(3)
        expect(delay).toBeGreaterThanOrEqual(8000 * 0.7)
        expect(delay).toBeLessThanOrEqual(8000 * 1.3)
      }
    })

    it('最大值封顶在 30s，不再无限增长（任意抖动因子）', () => {
      expect(computeReconnectDelay(6, 0)).toBe(30000)
      expect(computeReconnectDelay(10, 1)).toBe(30000)
      expect(computeReconnectDelay(20, 0.5)).toBe(30000)
    })
  })

  describe('无限重连与自动恢复', () => {
    let originalWebSocket

    beforeEach(() => {
      originalWebSocket = globalThis.WebSocket
      globalThis.WebSocket = FakeWebSocket
      FakeWebSocket.instances = []
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
      globalThis.WebSocket = originalWebSocket
    })

    async function connectClient() {
      const statuses = []
      const client = createImWebSocketClient({
        onStatusChange: (status) => statuses.push(status),
      })
      client.connect()
      await vi.advanceTimersByTimeAsync(0)
      lastSocket().readyState = FakeWebSocket.OPEN
      lastSocket().onopen()
      return { client, statuses }
    }

    it('超过原来的 20 次上限后仍在调度重连，不进入放弃终态', async () => {
      const { statuses } = await connectClient()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)

      // 连续掉线 25 次：每次断开后都应继续排队重连
      for (let i = 0; i < 25; i += 1) {
        const socket = lastSocket()
        socket.readyState = FakeWebSocket.CLOSED
        socket.onclose({})
        expect(statuses.at(-1)).toBe(IM_WS_STATUS.RECONNECTING)
        await vi.advanceTimersByTimeAsync(30000)
      }

      // 每轮重连都新建了一个连接，说明没有在第 20 次停下
      expect(FakeWebSocket.instances.length).toBe(26)
      expect(statuses).not.toContain(IM_WS_STATUS.DISCONNECTED)
    })

    it('网络恢复（online）时立即重连，不必等完退避时间', async () => {
      const { statuses } = await connectClient()
      const socket = lastSocket()
      socket.readyState = FakeWebSocket.CLOSED
      socket.onclose({})
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.RECONNECTING)

      window.dispatchEvent(new Event('online'))
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeWebSocket.instances.length).toBe(2)
    })

    it('disconnect() 后解绑监听，online 事件不再触发重连', async () => {
      const { client } = await connectClient()
      client.disconnect()
      const countAfterDisconnect = FakeWebSocket.instances.length

      window.dispatchEvent(new Event('online'))
      await vi.advanceTimersByTimeAsync(30000)
      expect(FakeWebSocket.instances.length).toBe(countAfterDisconnect)
    })
  })
})
