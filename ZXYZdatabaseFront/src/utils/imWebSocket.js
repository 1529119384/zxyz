import { requireViteEnv, resolveWebSocketUrl } from '@/utils/env'
import { createClientId } from '@/utils/id'
import imRequest from '@/utils/imRequest'

export const IM_WS_STATUS = {
  DISCONNECTED: 'DISCONNECTED',
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  RECONNECTING: 'RECONNECTING',
  CONNECTION_ERROR: 'CONNECTION_ERROR',
}

const HEARTBEAT_INTERVAL_MS = 25000
// 心跳看护（L6）：发出 PING 后若在宽限期内收不到 PONG，说明连接已「假活」——
// 服务端进程崩溃、中间设备静默丢包这类半开连接不会触发 onclose，
// 只看 onclose 会让 UI 永久停在「已连接」却收不到任何消息。
const PONG_TIMEOUT_MS = 15000
const BASE_RECONNECT_DELAY_MS = 1000
const MAX_RECONNECT_DELAY_MS = 30000

/**
 * 指数退避重连延迟：基线 1s，每多一次失败翻倍，封顶 MAX_RECONNECT_DELAY_MS。
 * 叠加 ±30% 抖动（jitter），避免服务端重启后大量客户端在同一时刻齐步重连造成惊群（P1-E2）。
 * 抽出为纯函数以便单测覆盖指数增长与封顶边界（P1-E4）；jitterFactor 可注入以便断言确定值。
 * @param {number} attempt 已失败/即将尝试的次数（从 0 起）
 * @param {number} jitterFactor [0,1) 抖动因子，默认取随机数
 * @returns {number} 本轮应等待的毫秒数
 */
function computeReconnectDelay(attempt, jitterFactor = Math.random()) {
  return Math.min(
    BASE_RECONNECT_DELAY_MS * 2 ** attempt * (0.7 + jitterFactor * 0.6),
    MAX_RECONNECT_DELAY_MS,
  )
}

function getImWebSocketUrl() {
  return resolveWebSocketUrl(requireViteEnv('VITE_IM_WS_URL'))
}

function createEnvelope(type, payload = {}) {
  return {
    type,
    requestId: createClientId(),
    clientMessageId: null,
    conversationId: null,
    payload,
    timestamp: Date.now(),
  }
}

async function fetchWsTicket() {
  const resp = await imRequest.post('/api/im/ws/ticket')
  return resp?.data || ''
}

export function createImWebSocketClient(options = {}) {
  const { onStatusChange, onMessage, onError } = options
  let socket = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let reconnectAttempt = 0
  let manualClose = false
  let sendQueue = Promise.resolve()
  // 最近一次收到 PONG 的本地时间；心跳看护据此判断连接是否还有响应（L6）
  let lastPongAt = 0

  function emitStatus(status) {
    onStatusChange?.(status)
  }

  function clearHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function clearReconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function sendEnvelope(envelope) {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return false
    }
    const frame = JSON.stringify(envelope)
    sendQueue = sendQueue
      .then(() => {
        if (socket && socket.readyState === WebSocket.OPEN) {
          socket.send(frame)
        }
      })
      .catch((error) => {
        onError?.(error)
      })
    return true
  }

  /**
   * 心跳看护（L6）：每个周期先检查上一轮 PING 是否得到了 PONG，
   * 超时即判定连接已死并主动重建。
   */
  function startHeartbeat() {
    clearHeartbeat()
    lastPongAt = Date.now()
    heartbeatTimer = setInterval(() => {
      if (Date.now() - lastPongAt > HEARTBEAT_INTERVAL_MS + PONG_TIMEOUT_MS) {
        handleDeadConnection()
        return
      }
      sendEnvelope(createEnvelope('PING'))
    }, HEARTBEAT_INTERVAL_MS)
  }

  /**
   * 心跳超时后的收尾：停心跳、丢弃旧 socket、按退避重连。
   * 旧 socket 的 onclose 晚到时 socket 已被置空，且 reconnectTimer 已存在，
   * scheduleReconnect 内部的守卫会拦住重复调度。
   */
  function handleDeadConnection() {
    clearHeartbeat()
    const deadSocket = socket
    socket = null
    if (deadSocket) {
      try {
        deadSocket.close()
      } catch {
        // 关闭已失效的连接可能抛错，忽略即可
      }
    }
    onError?.(new Error('WebSocket 心跳超时，连接无响应，正在重连'))
    scheduleReconnect()
  }

  // 无限重连：不再设置尝试次数上限，长时间断网（如笔记本合盖过夜）后无需用户手动干预
  // 也能自动恢复；延迟已被 MAX_RECONNECT_DELAY_MS 封顶（30s），空转成本可控（P1-E2）。
  function scheduleReconnect() {
    if (manualClose || reconnectTimer) {
      return
    }
    emitStatus(IM_WS_STATUS.RECONNECTING)
    const delay = computeReconnectDelay(reconnectAttempt)
    reconnectAttempt += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  async function connect() {
    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) {
      return
    }
    // 首次发起连接时绑定网络/可见性恢复监听，disconnect() 负责解绑
    bindRecoveryListeners()
    let ticket
    try {
      ticket = await fetchWsTicket()
    } catch (err) {
      emitStatus(IM_WS_STATUS.CONNECTION_ERROR)
      onError?.(err)
      scheduleReconnect()
      return
    }
    if (!ticket) {
      emitStatus(IM_WS_STATUS.CONNECTION_ERROR)
      onError?.(new Error('获取 WebSocket 凭证失败'))
      scheduleReconnect()
      return
    }

    const wasManualClose = manualClose
    manualClose = false
    if (wasManualClose) {
      reconnectAttempt = 0
    }
    emitStatus(reconnectAttempt > 0 ? IM_WS_STATUS.RECONNECTING : IM_WS_STATUS.CONNECTING)
    try {
      // 构造器可能同步抛错：URL 非法、浏览器禁用 WebSocket、页面处于非安全上下文等。
      // 不捕获的话异常会冒泡出 connect()，而 connect() 的调用方都是 fire-and-forget
      // （onopen/定时器/事件回调），异常无人接手，UI 将永久停在 CONNECTING 且不再重连（L6）。
      socket = new WebSocket(getImWebSocketUrl(), ['Bearer', ticket])
    } catch (error) {
      socket = null
      emitStatus(IM_WS_STATUS.CONNECTION_ERROR)
      onError?.(error)
      scheduleReconnect()
      return
    }

    socket.onopen = () => {
      reconnectAttempt = 0
      emitStatus(IM_WS_STATUS.CONNECTED)
      startHeartbeat()
    }

    socket.onmessage = (event) => {
      try {
        const envelope = JSON.parse(event.data)
        // 记录 PONG 时刻，供心跳看护判断连接是否仍有响应（L6）
        if (envelope?.type === 'PONG') {
          lastPongAt = Date.now()
        }
        onMessage?.(envelope)
      } catch (error) {
        onError?.(error)
      }
    }

    socket.onerror = (event) => {
      emitStatus(IM_WS_STATUS.CONNECTION_ERROR)
      onError?.(event)
    }

    socket.onclose = () => {
      clearHeartbeat()
      socket = null
      if (manualClose) {
        emitStatus(IM_WS_STATUS.DISCONNECTED)
        return
      }
      scheduleReconnect()
    }
  }

  /**
   * 页面重新可见时补一次重连：后台标签页的定时器会被浏览器节流，
   * 回到前台可能仍停在等待中，此处立即尝试恢复（P1-E2）。
   */
  function handleVisibility() {
    if (document.visibilityState !== 'visible') {
      return
    }
    // 用户主动断开、或已连接/正在连接时不打扰
    if (manualClose) {
      return
    }
    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) {
      return
    }
    reconnect()
  }

  // 保存绑定状态，避免重复 addEventListener；handler 用具名函数以便 disconnect() 精确解绑。
  let recoveryListenersBound = false

  function bindRecoveryListeners() {
    if (recoveryListenersBound) {
      return
    }
    recoveryListenersBound = true
    // 网络恢复（online）与页面回到前台（visibilitychange）都是重连的好时机；
    // reconnect() 内部会 clearReconnect() 并走 connect() 的 OPEN/CONNECTING 守卫，重复触发是安全的。
    window.addEventListener('online', reconnect)
    document.addEventListener('visibilitychange', handleVisibility)
  }

  function unbindRecoveryListeners() {
    if (!recoveryListenersBound) {
      return
    }
    recoveryListenersBound = false
    window.removeEventListener('online', reconnect)
    document.removeEventListener('visibilitychange', handleVisibility)
  }

  function disconnect() {
    manualClose = true
    unbindRecoveryListeners()
    clearHeartbeat()
    clearReconnect()
    if (socket) {
      socket.close()
      socket = null
    }
    sendQueue = Promise.resolve()
    emitStatus(IM_WS_STATUS.DISCONNECTED)
  }

  /**
   * 手动/自动重新连接：从主动断开或退避等待中立即恢复。
   * 清除 manualClose 并重置重连计数，让 UI 的『重新连接』按钮与 online/visibilitychange
   * 事件都能跳过剩余退避时间直接重建连接（P1-E2）。
   */
  function reconnect() {
    manualClose = false
    reconnectAttempt = 0
    clearReconnect()
    connect()
  }

  return {
    connect,
    reconnect,
    disconnect,
    sendEnvelope,
  }
}

export { computeReconnectDelay }
