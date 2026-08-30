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
const BASE_RECONNECT_DELAY_MS = 1000
const MAX_RECONNECT_DELAY_MS = 30000
const MAX_RECONNECT_ATTEMPTS = 20

/**
 * 指数退避重连延迟：基线 1s，每多一次失败翻倍，封顶 MAX_RECONNECT_DELAY_MS。
 * 抽出为纯函数以便单测覆盖指数增长与封顶边界（P1-E4）。
 * @param {number} attempt 已失败/即将尝试的次数（从 0 起）
 * @returns {number} 本轮应等待的毫秒数
 */
function computeReconnectDelay(attempt) {
  return Math.min(BASE_RECONNECT_DELAY_MS * 2 ** attempt, MAX_RECONNECT_DELAY_MS)
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

  function startHeartbeat() {
    clearHeartbeat()
    heartbeatTimer = setInterval(() => {
      sendEnvelope(createEnvelope('PING'))
    }, HEARTBEAT_INTERVAL_MS)
  }

  function scheduleReconnect() {
    if (manualClose || reconnectTimer) {
      return
    }
    if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
      // 达到重连上限：停止自动重试，转为需要用户手动干预的终态。
      // 用 CONNECTION_ERROR 而非 DISCONNECTED，以便 UI 区分「服务端/网络持续不可用
      // 需点击『重新连接』」与「用户主动断开」，并提供 WebSocket.reconnect() 的手动恢复路径（P1-E2）。
      manualClose = true
      emitStatus(IM_WS_STATUS.CONNECTION_ERROR)
      onError?.(new Error('WebSocket 重连次数已达上限，请手动重新连接'))
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
    socket = new WebSocket(getImWebSocketUrl(), ['Bearer', ticket])

    socket.onopen = () => {
      reconnectAttempt = 0
      emitStatus(IM_WS_STATUS.CONNECTED)
      startHeartbeat()
    }

    socket.onmessage = (event) => {
      try {
        const envelope = JSON.parse(event.data)
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

  function disconnect() {
    manualClose = true
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
   * 手动重新连接：从终态（重连次数达上限 / 主动断开）恢复。
   * 清除 manualClose 并重置重连计数，让 UI 的『重新连接』按钮可以真正重新拉取
   * 并建立连接，而不是停在 CONNECTION_ERROR 终态（P1-E2）。
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
