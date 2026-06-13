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
      manualClose = true
      emitStatus(IM_WS_STATUS.DISCONNECTED)
      onError?.(new Error('WebSocket 重连次数已达上限'))
      return
    }
    emitStatus(IM_WS_STATUS.RECONNECTING)
    const delay = Math.min(BASE_RECONNECT_DELAY_MS * 2 ** reconnectAttempt, MAX_RECONNECT_DELAY_MS)
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

  return {
    connect,
    disconnect,
    sendEnvelope,
  }
}
