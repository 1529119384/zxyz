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
import imRequest from '@/utils/imRequest'

// 用假 WebSocket 替换全局实现，既能驱动 onopen/onclose，又不会真的发起网络连接。
class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances = []
  // 测试可通过它让构造器同步抛错，验证调用方是否兵底（L6）
  static constructorHook = null

  constructor(url, protocols) {
    FakeWebSocket.constructorHook?.()
    this.url = url
    this.protocols = protocols
    this.readyState = FakeWebSocket.CONNECTING
    this.sent = []
    // close() 不自动触发 onclose：真实浏览器里 TCP 断开/服务端回 CLOSE 帧是**异步**到达的。
    // 需要同步行为的老用例显式调 closeNow()。默认异步恰好能复现「陈旧 onclose 迟到」竞态。
    FakeWebSocket.instances.push(this)
  }

  send(payload) {
    this.sent.push(payload)
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED
  }

  // 兼容既有用例的同步断开语义：置 CLOSED 并立即回调 onclose。
  closeNow() {
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

  describe('心跳看护与构造器异常兜底（L6）', () => {
    let originalWebSocket

    beforeEach(() => {
      originalWebSocket = globalThis.WebSocket
      globalThis.WebSocket = FakeWebSocket
      FakeWebSocket.instances = []
      FakeWebSocket.constructorHook = null
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
      FakeWebSocket.constructorHook = null
      globalThis.WebSocket = originalWebSocket
    })

    async function connectClient() {
      const statuses = []
      const errors = []
      const client = createImWebSocketClient({
        onStatusChange: (status) => statuses.push(status),
        onError: (error) => errors.push(error),
      })
      client.connect()
      await vi.advanceTimersByTimeAsync(0)
      lastSocket().readyState = FakeWebSocket.OPEN
      lastSocket().onopen()
      return { client, statuses, errors }
    }

    it('持续收到 PONG 时保持连接，不发起重连', async () => {
      const { statuses } = await connectClient()
      for (let i = 0; i < 2; i += 1) {
        await vi.advanceTimersByTimeAsync(25000)
        lastSocket().onmessage({ data: JSON.stringify({ type: 'PONG' }) })
      }
      expect(FakeWebSocket.instances.length).toBe(1)
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
      expect(lastSocket().sent.filter((f) => f.includes('PING')).length).toBe(2)
    })

    it('心跳超时（收不到 PONG）时主动重连', async () => {
      const { statuses, errors } = await connectClient()
      // 第一个周期发出 PING，服务端无响应
      await vi.advanceTimersByTimeAsync(25000)
      expect(FakeWebSocket.instances.length).toBe(1)
      // 第二个周期发现已超过 PONG 宽限期，判定连接已死
      await vi.advanceTimersByTimeAsync(25000)
      expect(statuses).toContain(IM_WS_STATUS.RECONNECTING)
      expect(errors.some((e) => /心跳超时/.test(e.message))).toBe(true)
      // 退避到期后重建连接
      await vi.advanceTimersByTimeAsync(30000)
      expect(FakeWebSocket.instances.length).toBe(2)
    })

    it('WebSocket 构造器同步抛错时上报 CONNECTION_ERROR 并继续重连', async () => {
      const { statuses, errors } = await connectClient()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)

      const boom = new Error('WebSocket is not defined')
      FakeWebSocket.constructorHook = () => {
        throw boom
      }
      const socket = lastSocket()
      socket.readyState = FakeWebSocket.CLOSED
      socket.onclose({})

      await vi.advanceTimersByTimeAsync(30000)
      expect(statuses).toContain(IM_WS_STATUS.CONNECTION_ERROR)
      expect(errors).toContain(boom)

      // 构造失败后依然在调度重连，不会永久卡在 CONNECTING
      FakeWebSocket.constructorHook = null
      await vi.advanceTimersByTimeAsync(30000)
      expect(FakeWebSocket.instances.length).toBe(2)
    })
  })

  // J-1/J-2（2026-10-03 修复）：真实浏览器里 TCP 断开、CLOSE 帧、消息帧都是**异步**到达的。
  // 既有 FakeWebSocket 的 close() 同步回调 onclose，恰好掩盖了两条竞态：
  //   ① 心跳判死后丢弃旧连接，旧连接的 onclose 迟到 → 清掉新连接的心跳/置空 socket/重复调度重连 → 双活连接；
  //   ② 票据请求（fetchWsTicket，imRequest 超时 10s）在途时 reconnect/online 再次触发 connect → 并行两个 connect 互相覆盖。
  // 下面用「close() 不自动回调」的默认异步语义 + 手动延时触发迟到事件来复现。
  describe('陈旧连接事件守卫（J-1/J-2）', () => {
    let originalWebSocket

    beforeEach(() => {
      originalWebSocket = globalThis.WebSocket
      globalThis.WebSocket = FakeWebSocket
      FakeWebSocket.instances = []
      FakeWebSocket.constructorHook = null
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
      FakeWebSocket.constructorHook = null
      globalThis.WebSocket = originalWebSocket
    })

    async function connectClient(extraOptions = {}) {
      const statuses = []
      const errors = []
      const client = createImWebSocketClient({
        onStatusChange: (status) => statuses.push(status),
        onError: (error) => errors.push(error),
        ...extraOptions,
      })
      client.connect()
      await vi.advanceTimersByTimeAsync(0)
      lastSocket().readyState = FakeWebSocket.OPEN
      lastSocket().onopen()
      return { client, statuses, errors }
    }

    it('应忽略迟到的心跳判死连接的 onclose：不重建心跳计时、不置空新连接、不额外重连', async () => {
      const { statuses } = await connectClient()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
      expect(FakeWebSocket.instances.length).toBe(1)

      // 心跳两个周期无 PONG → handleDeadConnection：停心跳、丢弃 S1、调度重连
      await vi.advanceTimersByTimeAsync(50000)
      expect(FakeWebSocket.instances.length).toBe(1)

      // 退避到期 → 建 S2 并 CONNECTED
      await vi.advanceTimersByTimeAsync(30000)
      const s2 = lastSocket()
      s2.readyState = FakeWebSocket.OPEN
      s2.onopen()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
      expect(FakeWebSocket.instances.length).toBe(2)
      const statusCount = statuses.length

      // 旧连接 S1 的 onclose **迟到**（真实浏览器中 TCP 半开的 close 事件可能晚到数秒）
      const s1 = FakeWebSocket.instances[0]
      s1.readyState = FakeWebSocket.CLOSED
      s1.onclose({})

      // 缺陷形态（修复前）：S2 的心跳被清、socket 被置空、还多调度一次重连。
      // 期望形态：S2 心跳照常发 PING，socket 仍指向 S2，状态不再变化。
      await vi.advanceTimersByTimeAsync(25000)
      expect(s2.sent.some((f) => f.includes('PING'))).toBe(true)
      expect(statuses.length).toBe(statusCount)
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
      expect(FakeWebSocket.instances.length).toBe(2)
    })

    it('应忽略迟到的心跳判死连接的 onmessage：不污染新连接的 lastPongAt', async () => {
      const { statuses, errors } = await connectClient()

      // 心跳判死 S1 → 退避到期建 S2
      await vi.advanceTimersByTimeAsync(50000)
      await vi.advanceTimersByTimeAsync(30000)
      const s2 = lastSocket()
      s2.readyState = FakeWebSocket.OPEN
      s2.onopen()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)

      // S1 迟到 onmessage：非法 JSON 只会打日志，不允许抛出未捕获异常或触发 onError
      const s1 = FakeWebSocket.instances[0]
      const errorCount = errors.length
      s1.onmessage({ data: '{not-json' })

      // S1 迟到 PONG 不应影响任何状态推进（守卫后直接忽略）
      s1.onmessage({ data: JSON.stringify({ type: 'PONG', timestamp: Date.now() }) })
      expect(errors.length).toBe(errorCount)
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)

      // S2 心跳仍正常发 PING
      await vi.advanceTimersByTimeAsync(25000)
      expect(s2.sent.some((f) => f.includes('PING'))).toBe(true)
    })

    it('应忽略迟到的心跳判死连接的 onerror：状态不退回 CONNECTION_ERROR', async () => {
      const { statuses } = await connectClient()

      await vi.advanceTimersByTimeAsync(50000)
      await vi.advanceTimersByTimeAsync(30000)
      const s2 = lastSocket()
      s2.readyState = FakeWebSocket.OPEN
      s2.onopen()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
      const statusCount = statuses.length

      const s1 = FakeWebSocket.instances[0]
      s1.onerror(new Event('error'))

      expect(statuses.length).toBe(statusCount)
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)
    })

    it('应在票据请求窗口内重入 connect 时复用同一票据请求，只建一个连接', async () => {
      // fetchWsTicket 用 deferred 控制 resolve 时机，模拟 imRequest 10s 超时窗口内的慢响应
      let resolveTicket
      imRequest.post.mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveTicket = resolve
          }),
      )

      const statuses = []
      const client = createImWebSocketClient({
        onStatusChange: (status) => statuses.push(status),
      })

      // 第一次 connect：停在等待票据，尚未建 socket
      client.connect()
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeWebSocket.instances.length).toBe(0)

      // 票据窗口内再次触发（用户点『重新连接』/ online / visibilitychange 都走这条）
      client.reconnect()
      client.connect()
      // 让微任务队列跑完，确认没有第二个票据请求、没有第二个 connect 旁路
      await vi.advanceTimersByTimeAsync(0)

      expect(imRequest.post).toHaveBeenCalledTimes(1)

      // 票据到达：只有一次 connect 真正落地 → 只建一个连接
      resolveTicket({ data: 'ticket-1' })
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeWebSocket.instances.length).toBe(1)

      lastSocket().readyState = FakeWebSocket.OPEN
      lastSocket().onopen()
      expect(statuses.at(-1)).toBe(IM_WS_STATUS.CONNECTED)

      // 稳定性：再推进两个心跳周期，仍只有一个连接实例
      await vi.advanceTimersByTimeAsync(50000)
      expect(FakeWebSocket.instances.length).toBe(1)
    })

    it('应在票据失败路径同样清理 in-flight 状态，后续 connect 可正常重试', async () => {
      let rejectTicket
      imRequest.post.mockImplementation(
        () =>
          new Promise((_resolve, reject) => {
            rejectTicket = reject
          }),
      )

      const statuses = []
      const errors = []
      const client = createImWebSocketClient({
        onStatusChange: (status) => statuses.push(status),
        onError: (error) => errors.push(error),
      })

      client.connect()
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeWebSocket.instances.length).toBe(0)

      // 票据失败：第一次 connect 走 CONNECTION_ERROR + scheduleReconnect
      rejectTicket(new Error('ticket failed'))
      await vi.advanceTimersByTimeAsync(0)
      expect(statuses).toContain(IM_WS_STATUS.CONNECTION_ERROR)
      expect(errors.some((e) => e.message === 'ticket failed')).toBe(true)

      // 在途 Promise 已清理：不等退避，立即 reconnect 应再次发起票据请求
      const before = imRequest.post.mock.calls.length
      client.reconnect()
      await vi.advanceTimersByTimeAsync(0)
      expect(imRequest.post.mock.calls.length).toBe(before + 1)
    })
  })
})
