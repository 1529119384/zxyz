import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'

import { createRealtimeDomain } from '@/store/im/realtimeDomain'
import { IM_WS_STATUS } from '@/utils/imWebSocket'

vi.mock('@/api/im', () => ({
  fetchMyPresence: vi.fn(),
}))

// 让 clientId 可预测：sendTextMessage 依次生成 clientMessageId、requestId，
// 故第一条消息为 cid-1(clientMessageId)/cid-2(requestId)，第二条为 cid-3/cid-4。
const { idCounter } = vi.hoisted(() => ({ idCounter: { n: 0 } }))
vi.mock('@/utils/id', () => ({
  createClientId: () => `cid-${++idCounter.n}`,
  normalizePositiveId: (value) => {
    const numeric = Number(value)
    return Number.isFinite(numeric) && numeric > 0 ? numeric : null
  },
}))

// 固定当前用户，避免真实 Pinia 依赖；profile 做成可变，便于测试「未登录」分支
const { currentUserState } = vi.hoisted(() => ({
  currentUserState: { profile: { id: 100, username: 'me', name: 'Me', avatar: '' } },
}))
const DEFAULT_PROFILE = { id: 100, username: 'me', name: 'Me', avatar: '' }
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: () => currentUserState,
}))

let wsClientInstance = null
let onMessageHandler = null
let onStatusChangeHandler = null
let onErrorHandler = null
vi.mock('@/utils/imWebSocket', () => ({
  IM_WS_STATUS: {
    DISCONNECTED: 'DISCONNECTED',
    CONNECTING: 'CONNECTING',
    CONNECTED: 'CONNECTED',
    RECONNECTING: 'RECONNECTING',
    CONNECTION_ERROR: 'CONNECTION_ERROR',
  },
  createImWebSocketClient: (options) => {
    onMessageHandler = options.onMessage
    onStatusChangeHandler = options.onStatusChange
    onErrorHandler = options.onError
    wsClientInstance = {
      connect: vi.fn(),
      disconnect: vi.fn(),
      reconnect: vi.fn(),
      sendEnvelope: vi.fn(() => true),
    }
    return wsClientInstance
  },
}))

function createDomain() {
  const buckets = {}
  const state = {
    wsStatus: ref(IM_WS_STATUS.DISCONNECTED),
    lastPongTime: ref(null),
    lastWsError: ref(null),
    myPresence: ref(null),
    conversations: ref([]),
    unreadCount: ref(0),
    activeConversationId: ref(null),
  }
  const deps = {
    clearReadSyncTimers: vi.fn(),
    ensureMessageBucket: vi.fn((conversationId) => {
      if (!buckets[conversationId]) buckets[conversationId] = []
      return buckets[conversationId]
    }),
    isConversationEffectivelyVisible: vi.fn(() => false),
    loadConversationMessages: vi.fn(() => Promise.resolve([])),
    mergeConversationMessage: vi.fn(),
    markBucketChanged: vi.fn(),
    scheduleReadSync: vi.fn(),
    syncConversationMessages: vi.fn(() => Promise.resolve([])),
    touchConversation: vi.fn(),
    updateConversationUnread: vi.fn(),
    updatePendingMessageStatus: vi.fn(),
  }
  const domain = createRealtimeDomain(state, deps)
  return { state, deps, domain, buckets }
}

// 通过 ensureWebSocketConnected 触发 createImWebSocketClient 捕获 onMessage 分派器
function connect(domain) {
  domain.ensureWebSocketConnected()
  return {
    onMessage: onMessageHandler,
    onStatusChange: onStatusChangeHandler,
    onError: onErrorHandler,
    wsClient: wsClientInstance,
  }
}

beforeEach(() => {
  wsClientInstance = null
  onMessageHandler = null
  onStatusChangeHandler = null
  onErrorHandler = null
  currentUserState.profile = { ...DEFAULT_PROFILE }
  idCounter.n = 0
})

describe('realtimeDomain', () => {
  describe('handleReceivedMessage（经 onMessage 分派）', () => {
    it('无 conversationId 时早退，不触碰任何依赖', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECEIVED',
        payload: { senderUserId: 200, content: 'x' },
      })
      expect(deps.mergeConversationMessage).not.toHaveBeenCalled()
      expect(deps.updateConversationUnread).not.toHaveBeenCalled()
      expect(deps.touchConversation).not.toHaveBeenCalled()
    })

    it('自我消息：unread 置 0、全局 unreadCount 不变、scheduleReadSync 被调用', () => {
      const { state, deps, domain } = createDomain()
      state.conversations.value = [{ id: 'c1', type: 'TEAM', unreadCount: 3 }]
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECEIVED',
        conversationId: 'c1',
        clientMessageId: 'client-1',
        payload: { senderUserId: 100, content: 'hi', messageType: 'TEXT' },
      })
      expect(deps.mergeConversationMessage).toHaveBeenCalledWith(
        'c1',
        expect.objectContaining({ conversationId: 'c1', status: 'STORED' }),
      )
      expect(deps.updateConversationUnread).toHaveBeenCalledWith('c1', 0)
      expect(deps.scheduleReadSync).toHaveBeenCalledWith('c1')
      expect(state.unreadCount.value).toBe(0)
    })

    it('非自我且会话可见：unread 置 0、scheduleReadSync 被调用', () => {
      const { state, deps, domain } = createDomain()
      state.conversations.value = [{ id: 'c1', type: 'TEAM', unreadCount: 5 }]
      deps.isConversationEffectivelyVisible.mockReturnValue(true)
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECEIVED',
        conversationId: 'c1',
        payload: { senderUserId: 200, content: 'x' },
      })
      expect(deps.updateConversationUnread).toHaveBeenCalledWith('c1', 0)
      expect(deps.scheduleReadSync).toHaveBeenCalledWith('c1')
    })

    it('非自我且不可见：unread +1、不触发 scheduleReadSync', () => {
      const { state, deps, domain } = createDomain()
      state.conversations.value = [{ id: 'c1', type: 'TEAM', unreadCount: 3 }]
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECEIVED',
        conversationId: 'c1',
        payload: { senderUserId: 200, content: 'x' },
      })
      expect(deps.updateConversationUnread).toHaveBeenCalledWith('c1', 4)
      expect(deps.scheduleReadSync).not.toHaveBeenCalled()
      expect(state.unreadCount.value).toBe(0)
    })

    it('非自我的 SYSTEM 会话：全局 unreadCount 累加', () => {
      const { state, deps, domain } = createDomain()
      state.conversations.value = [{ id: 'c1', type: 'SYSTEM', unreadCount: 0 }]
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECEIVED',
        conversationId: 'c1',
        payload: { senderUserId: 200, content: 'x' },
      })
      expect(deps.updateConversationUnread).toHaveBeenCalledWith('c1', 1)
      expect(state.unreadCount.value).toBe(1)
    })
  })

  describe('handleAck（经 onMessage 分派）', () => {
    it('缺 conversationId / clientMessageId 时早退', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'MESSAGE_ACK', payload: { messageId: 1 } })
      onMessageHandler({
        type: 'MESSAGE_ACK',
        conversationId: 'c1',
        payload: { messageId: 1 },
      })
      expect(deps.updatePendingMessageStatus).not.toHaveBeenCalled()
    })

    it('成功后 updatePendingMessageStatus 置 STORED 并释放出站任务', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      // 先发一条消息入队，拿到 requestId / clientMessageId
      const clientMessageId = domain.sendTextMessage('c1', 'hello')
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(1)
      const sentEnvelope = wsClient.sendEnvelope.mock.calls[0][0]

      // 服务端回 ACK
      onMessageHandler({
        type: 'MESSAGE_ACK',
        conversationId: 'c1',
        clientMessageId,
        requestId: sentEnvelope.requestId,
        payload: { messageId: 500 },
      })
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        clientMessageId,
        { messageId: 500, status: 'STORED' },
      )

      // 任务被 resolveOutboundTask 释放，下一条消息可继续发送
      domain.sendTextMessage('c1', 'again')
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(2)
    })
  })

  describe('handleMessageRecalled（经 onMessage 分派）', () => {
    it('命中后置 RECALLED 并清空 content / fileCard', () => {
      const { deps, domain, buckets } = createDomain()
      buckets.c1 = [
        {
          messageId: 10,
          content: 'hello',
          fileCard: { fileId: 1 },
          status: 'STORED',
        },
      ]
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECALLED',
        conversationId: 'c1',
        payload: { messageId: 10, recallByUserId: 200, recallTime: '2026-01-01T00:00:00Z' },
      })
      const msg = buckets.c1[0]
      expect(msg.status).toBe('RECALLED')
      expect(msg.content).toBe('')
      expect(msg.fileCard).toBeNull()
      expect(msg.recallByUserId).toBe(200)
      expect(msg.recallTime).toBe('2026-01-01T00:00:00Z')
      expect(deps.markBucketChanged).toHaveBeenCalled()
    })

    it('消息不存在时不改动桶也不触发 markBucketChanged', () => {
      const { deps, domain, buckets } = createDomain()
      buckets.c1 = [{ messageId: 10, content: 'x', status: 'STORED' }]
      connect(domain)
      onMessageHandler({
        type: 'MESSAGE_RECALLED',
        conversationId: 'c1',
        payload: { messageId: 999 },
      })
      expect(buckets.c1[0].status).toBe('STORED')
      expect(deps.markBucketChanged).not.toHaveBeenCalled()
    })

    it('缺 conversationId / messageId 时早退', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'MESSAGE_RECALLED', payload: {} })
      expect(deps.markBucketChanged).not.toHaveBeenCalled()
    })
  })

  describe('handleReadUpdated（经 onMessage 分派）', () => {
    it('读者是当前用户：清空该会话未读', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({
        type: 'READ_UPDATED',
        conversationId: 'c1',
        payload: { readerUserId: 100 },
      })
      expect(deps.updateConversationUnread).toHaveBeenCalledWith('c1', 0)
      expect(deps.loadConversationMessages).not.toHaveBeenCalled()
    })

    it('读者是当前用户且为 SYSTEM 会话：全局 unreadCount 清零', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 'c1', type: 'SYSTEM' }]
      state.unreadCount.value = 5
      connect(domain)
      onMessageHandler({
        type: 'READ_UPDATED',
        conversationId: 'c1',
        payload: { readerUserId: 100 },
      })
      expect(state.unreadCount.value).toBe(0)
    })

    it('读者非当前用户且会话可见：触发重载会话消息', () => {
      const { deps, domain } = createDomain()
      deps.isConversationEffectivelyVisible.mockReturnValue(true)
      connect(domain)
      onMessageHandler({
        type: 'READ_UPDATED',
        conversationId: 'c1',
        payload: { readerUserId: 200 },
      })
      expect(deps.loadConversationMessages).toHaveBeenCalledWith('c1')
    })

    it('读者非当前用户且不可见：不重载', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({
        type: 'READ_UPDATED',
        conversationId: 'c1',
        payload: { readerUserId: 200 },
      })
      expect(deps.loadConversationMessages).not.toHaveBeenCalled()
    })

    it('重载会话消息失败时记录 lastWsError 而不抛出', async () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      deps.isConversationEffectivelyVisible.mockReturnValue(true)
      deps.loadConversationMessages.mockReturnValue(Promise.reject(new Error('重载失败')))
      onMessageHandler({
        type: 'READ_UPDATED',
        conversationId: 'c1',
        payload: { readerUserId: 200 },
      })
      await Promise.resolve()
      await Promise.resolve()
      expect(state.lastWsError.value).toBe('重载失败')
    })

    it('缺 conversationId / readerUserId 时早退', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'READ_UPDATED', payload: {} })
      expect(deps.updateConversationUnread).not.toHaveBeenCalled()
    })
  })

  describe('sendTextMessage', () => {
    it('空内容返回 null，不 merge 不 touch', () => {
      const { deps, domain } = createDomain()
      expect(domain.sendTextMessage('c1', '   ')).toBeNull()
      expect(domain.sendTextMessage('c1', '')).toBeNull()
      expect(domain.sendTextMessage('c1', null)).toBeNull()
      expect(deps.mergeConversationMessage).not.toHaveBeenCalled()
      expect(deps.touchConversation).not.toHaveBeenCalled()
    })

    it('未连接时置 FAILED 并抛错', () => {
      const { deps, domain } = createDomain()
      expect(() => domain.sendTextMessage('c1', 'hello')).toThrow('实时连接未建立')
      expect(deps.mergeConversationMessage).toHaveBeenCalledTimes(1)
      expect(deps.touchConversation).toHaveBeenCalledWith('c1')
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        expect.any(String),
        { status: 'FAILED' },
      )
    })

    it('已连接时入队发送并返回 clientMessageId', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      const clientMessageId = domain.sendTextMessage('c1', '  hello  ')
      expect(typeof clientMessageId).toBe('string')
      expect(clientMessageId.length).toBeGreaterThan(0)
      expect(deps.mergeConversationMessage).toHaveBeenCalledWith(
        'c1',
        expect.objectContaining({
          conversationId: 'c1',
          messageType: 'TEXT',
          content: 'hello',
          status: 'SENDING',
        }),
      )
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(1)
      const envelope = wsClient.sendEnvelope.mock.calls[0][0]
      expect(envelope.type).toBe('SEND_TEXT')
      expect(envelope.conversationId).toBe('c1')
      expect(envelope.payload).toEqual({ content: 'hello', mentions: [] })
    })

    it('携带 mentions 时一并写入合并消息与信封', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      domain.sendTextMessage('c1', 'hi @all', [100, 200])
      expect(deps.mergeConversationMessage).toHaveBeenCalledWith(
        'c1',
        expect.objectContaining({ mentions: [100, 200] }),
      )
      const envelope = wsClient.sendEnvelope.mock.calls[0][0]
      expect(envelope.payload.mentions).toEqual([100, 200])
    })

    it('非字符串内容视为空，返回 null', () => {
      const { domain } = createDomain()
      expect(domain.sendTextMessage('c1', 123)).toBeNull()
      expect(domain.sendTextMessage('c1', undefined)).toBeNull()
    })
  })

  describe('出站队列', () => {
    it('已有在途任务时后续消息排队，前一个 resolve 后自动出队', () => {
      const { state, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      domain.sendTextMessage('c1', 'first')
      domain.sendTextMessage('c1', 'second')
      // 第一条在途，第二条仍在队列
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(1)
      const firstEnvelope = wsClient.sendEnvelope.mock.calls[0][0]

      onMessageHandler({
        type: 'MESSAGE_ACK',
        conversationId: 'c1',
        clientMessageId: firstEnvelope.clientMessageId,
        requestId: firstEnvelope.requestId,
        payload: { messageId: 1 },
      })
      // 释放后第二条被 drain 出去
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(2)
      expect(wsClient.sendEnvelope.mock.calls[1][0].payload.content).toBe('second')
    })

    it('sendEnvelope 失败时置 FAILED、记录错误并继续 drain', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      wsClient.sendEnvelope.mockReturnValue(false)

      domain.sendTextMessage('c1', 'hello')
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        expect.any(String),
        { status: 'FAILED' },
      )
      expect(state.lastWsError.value).toBe('实时连接未建立')
    })

    it('未连接时入队任务不会被发送', () => {
      const { domain } = createDomain()
      const { wsClient } = connect(domain)
      // wsStatus 保持 DISCONNECTED：sendTextMessage 会直接抛错，不入队
      expect(() => domain.sendTextMessage('c1', 'hello')).toThrow('实时连接未建立')
      expect(wsClient.sendEnvelope).not.toHaveBeenCalled()
    })

    it('ACK 携带未注册 requestId 时直接返回，不释放在途任务', () => {
      const { state, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'first') // 在途 requestId=cid-2

      onMessageHandler({
        type: 'MESSAGE_ACK',
        conversationId: 'c1',
        clientMessageId: 'not-registered',
        requestId: 'unknown-request-id',
        payload: { messageId: 1 },
      })
      // 在途任务仍在，第二条只能排队
      domain.sendTextMessage('c1', 'second')
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(1)
    })

    it('解析队列中（非在途）的任务时从队列移除且不影响在途任务', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'first') // 在途 requestId=cid-2
      domain.sendTextMessage('c1', 'second') // 入队 requestId=cid-4

      // 直接失败队列中那条，走 outboundQueue.splice 分支
      onMessageHandler({
        type: 'ERROR',
        requestId: 'cid-4',
        payload: { message: '队列任务失败' },
      })
      expect(state.lastWsError.value).toBe('队列任务失败')
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        'cid-3',
        { status: 'FAILED' },
      )
      // 在途任务不受影响，队列任务移除后也不会被补发
      expect(wsClient.sendEnvelope).toHaveBeenCalledTimes(1)
    })
  })

  describe('ERROR 信封与出站任务失败', () => {
    it('带已知 requestId 的 ERROR 会失败该任务并记录错误', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      domain.sendTextMessage('c1', 'hello')
      const envelope = wsClient.sendEnvelope.mock.calls[0][0]

      onMessageHandler({
        type: 'ERROR',
        requestId: envelope.requestId,
        payload: { message: '服务端拒绝' },
      })
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        envelope.clientMessageId,
        { status: 'FAILED' },
      )
      expect(state.lastWsError.value).toBe('服务端拒绝')
    })

    it('无匹配 requestId 的 ERROR 只记录全局错误', () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      onMessageHandler({
        type: 'ERROR',
        requestId: 'unknown-request',
        payload: { message: '未知请求出错' },
      })
      expect(state.lastWsError.value).toBe('未知请求出错')
      expect(deps.updatePendingMessageStatus).not.toHaveBeenCalled()
    })

    it('ERROR 无 message 时使用默认文案', () => {
      const { state, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'ERROR', payload: {} })
      expect(state.lastWsError.value).toBe('IM 实时消息处理失败')
    })
  })

  describe('PONG / AUTH_OK 信封', () => {
    it('PONG 记录 lastPongTime', () => {
      const { state, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'PONG', timestamp: 123456 })
      expect(state.lastPongTime.value).toBe(123456)
    })

    it('PONG 无 timestamp 时回落到当前时间', () => {
      const { state, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'PONG' })
      expect(typeof state.lastPongTime.value).toBe('number')
    })

    it('AUTH_OK 写入 myPresence', () => {
      const { state, domain } = createDomain()
      connect(domain)
      onMessageHandler({
        type: 'AUTH_OK',
        payload: { userId: 100, connectionCount: 3 },
      })
      expect(state.myPresence.value).toEqual({
        userId: 100,
        online: true,
        connectionCount: 3,
        lastActiveTime: null,
      })
    })

    it('AUTH_OK 缺 payload 时使用默认值', () => {
      const { state, domain } = createDomain()
      connect(domain)
      onMessageHandler({ type: 'AUTH_OK' })
      expect(state.myPresence.value).toEqual({
        userId: null,
        online: true,
        connectionCount: 1,
        lastActiveTime: null,
      })
    })
  })

  describe('连接生命周期', () => {
    it('未登录时不建连接并主动断开', () => {
      currentUserState.profile = null
      const { state, domain } = createDomain()
      domain.ensureWebSocketConnected()
      expect(wsClientInstance).toBeNull()
      expect(state.wsStatus.value).toBe(IM_WS_STATUS.DISCONNECTED)
    })

    it('已连接状态下再次调用不会重复 connect（连接锁）', () => {
      const { domain } = createDomain()
      const { wsClient } = connect(domain)
      domain.ensureWebSocketConnected()
      expect(wsClient.connect).toHaveBeenCalledTimes(1)
    })

    it('状态变为 CONNECTED 时 drain 队列并同步会话消息', () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      state.wsStatus.value = IM_WS_STATUS.DISCONNECTED
      onStatusChangeHandler(IM_WS_STATUS.CONNECTED)
      expect(state.wsStatus.value).toBe(IM_WS_STATUS.CONNECTED)
      expect(deps.syncConversationMessages).toHaveBeenCalled()
    })

    it('syncConversationMessages 失败时记录 lastWsError', async () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      deps.syncConversationMessages.mockReturnValue(Promise.reject(new Error('同步失败')))
      onStatusChangeHandler(IM_WS_STATUS.CONNECTED)
      await Promise.resolve()
      await Promise.resolve()
      expect(state.lastWsError.value).toBe('同步失败')
    })

    it('disconnectWebSocket 清空读同步定时器、失败全部在途任务并断开', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'hello')

      domain.disconnectWebSocket()
      expect(deps.clearReadSyncTimers).toHaveBeenCalled()
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        expect.any(String),
        { status: 'FAILED' },
      )
      expect(wsClient.disconnect).toHaveBeenCalled()
      expect(state.wsStatus.value).toBe(IM_WS_STATUS.DISCONNECTED)
      expect(state.lastWsError.value).toBe('实时连接已关闭')
    })

    it('disconnectWebSocket 时队列中的待发任务也会失败', () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'first') // 在途
      domain.sendTextMessage('c1', 'second') // 入队

      domain.disconnectWebSocket()
      // 在途 1 条 + 队列 1 条都被置 FAILED
      const failedCalls = deps.updatePendingMessageStatus.mock.calls.filter(
        (call) => call[2]?.status === 'FAILED',
      )
      expect(failedCalls).toHaveLength(2)
    })

    it('未建连接时 disconnectWebSocket 不报错', () => {
      const { state, deps, domain } = createDomain()
      expect(() => domain.disconnectWebSocket()).not.toThrow()
      expect(deps.clearReadSyncTimers).toHaveBeenCalled()
      expect(state.wsStatus.value).toBe(IM_WS_STATUS.DISCONNECTED)
    })

    it('reconnectWebSocket 在未连接时为空操作，已连接时调用 reconnect', () => {
      const { domain } = createDomain()
      expect(() => domain.reconnectWebSocket()).not.toThrow()
      const { wsClient } = connect(domain)
      domain.reconnectWebSocket()
      expect(wsClient.reconnect).toHaveBeenCalled()
    })

    it('onError 回调记录 lastWsError', () => {
      const { state, domain } = createDomain()
      const { onError } = connect(domain)
      onError(new Error('连接异常'))
      expect(state.lastWsError.value).toBe('连接异常')
    })

    it('onError 回调对非 Error 入参做字符串兜底', () => {
      const { state, domain } = createDomain()
      const { onError } = connect(domain)
      onError('plain string error')
      expect(state.lastWsError.value).toBe('plain string error')
    })
  })

  describe('出站任务超时（30s）', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })
    afterEach(() => {
      vi.useRealTimers()
    })

    it('超时后任务失败并记录错误', () => {
      const { state, deps, domain } = createDomain()
      connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'hello')

      vi.advanceTimersByTime(30_000)
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        expect.any(String),
        { status: 'FAILED' },
      )
      expect(state.lastWsError.value).toBe('消息发送超时')
    })

    it('任务已完成后再超时不会重复失败', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      domain.sendTextMessage('c1', 'hello')
      const envelope = wsClient.sendEnvelope.mock.calls[0][0]
      onMessageHandler({
        type: 'MESSAGE_ACK',
        conversationId: 'c1',
        clientMessageId: envelope.clientMessageId,
        requestId: envelope.requestId,
        payload: { messageId: 1 },
      })
      const before = deps.updatePendingMessageStatus.mock.calls.length

      vi.advanceTimersByTime(30_000)
      // 没有新增 FAILED 调用
      const after = deps.updatePendingMessageStatus.mock.calls.filter(
        (call) => call[2]?.status === 'FAILED',
      ).length
      expect(after).toBe(0)
      expect(deps.updatePendingMessageStatus.mock.calls.length).toBe(before)
    })
  })

  describe('loadMyPresence', () => {
    it('返回并写入 myPresence', async () => {
      const { state, domain } = createDomain()
      const { fetchMyPresence } = await import('@/api/im')
      fetchMyPresence.mockResolvedValue({ data: { userId: 100, online: true } })
      const result = await domain.loadMyPresence()
      expect(result).toEqual({ userId: 100, online: true })
      expect(state.myPresence.value).toEqual({ userId: 100, online: true })
    })

    it('响应无 data 时写入 null', async () => {
      const { state, domain } = createDomain()
      const { fetchMyPresence } = await import('@/api/im')
      fetchMyPresence.mockResolvedValue({})
      const result = await domain.loadMyPresence()
      expect(result).toBeNull()
      expect(state.myPresence.value).toBeNull()
    })
  })

  describe('buildFileCardPayload', () => {
    it('单个文件夹 → SINGLE_FOLDER', () => {
      const { domain } = createDomain()
      const payload = domain.buildFileCardPayload([
        { id: 1, type: 0, fileName: 'docs', parentId: null },
      ])
      expect(payload.shareType).toBe('SINGLE_FOLDER')
      expect(payload.entryCount).toBe(1)
      expect(payload.parentId).toBeNull()
    })

    it('单个文件 → SINGLE_FILE', () => {
      const { domain } = createDomain()
      const payload = domain.buildFileCardPayload([
        { id: 2, type: 1, fileName: 'a.pdf', parentId: 9, fileSize: 100 },
      ])
      expect(payload.shareType).toBe('SINGLE_FILE')
      expect(payload.parentId).toBe(9)
      expect(payload.entries[0]).toEqual(
        expect.objectContaining({ fileId: 2, fileType: 1, originalName: 'a.pdf' }),
      )
    })

    it('多个同目录文件 → MULTI_FILE', () => {
      const { domain } = createDomain()
      const payload = domain.buildFileCardPayload([
        { id: 2, type: 1, fileName: 'a.pdf', parentId: 9 },
        { id: 3, type: 1, fileName: 'b.pdf', parentId: 9 },
      ])
      expect(payload.shareType).toBe('MULTI_FILE')
      expect(payload.entryCount).toBe(2)
    })

    it('空列表抛错', () => {
      const { domain } = createDomain()
      expect(() => domain.buildFileCardPayload([])).toThrow('请选择要分享的文件或文件夹')
      expect(() => domain.buildFileCardPayload()).toThrow('请选择要分享的文件或文件夹')
      expect(() => domain.buildFileCardPayload('not-an-array')).toThrow(
        '请选择要分享的文件或文件夹',
      )
    })

    it('过滤掉无 id 的项后为空则抛错', () => {
      const { domain } = createDomain()
      expect(() => domain.buildFileCardPayload([{ fileName: 'x' }, null])).toThrow(
        '请选择要分享的文件或文件夹',
      )
    })

    it('跨目录多文件抛错', () => {
      const { domain } = createDomain()
      expect(() =>
        domain.buildFileCardPayload([
          { id: 2, type: 1, fileName: 'a.pdf', parentId: 9 },
          { id: 3, type: 1, fileName: 'b.pdf', parentId: 10 },
        ]),
      ).toThrow('多文件分享必须来自同一文件夹')
    })
  })

  describe('sendFileCardMessage', () => {
    it('已连接时入队发送并返回 clientMessageId', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED

      const clientMessageId = domain.sendFileCardMessage('c1', [
        { id: 2, type: 1, fileName: 'a.pdf', parentId: 9 },
      ])
      expect(typeof clientMessageId).toBe('string')
      expect(deps.mergeConversationMessage).toHaveBeenCalledWith(
        'c1',
        expect.objectContaining({ messageType: 'FILE_CARD', status: 'SENDING' }),
      )
      const envelope = wsClient.sendEnvelope.mock.calls[0][0]
      expect(envelope.type).toBe('SEND_FILE_CARD')
      expect(envelope.payload.fileIds).toEqual([2])
    })

    it('未连接时置 FAILED 并抛错', () => {
      const { deps, domain } = createDomain()
      connect(domain)
      expect(() =>
        domain.sendFileCardMessage('c1', [{ id: 2, type: 1, fileName: 'a.pdf' }]),
      ).toThrow('实时连接未建立')
      expect(deps.updatePendingMessageStatus).toHaveBeenCalledWith(
        'c1',
        expect.any(String),
        { status: 'FAILED' },
      )
    })

    it('文件列表非法时 buildFileCardPayload 抛错且不入队', () => {
      const { state, deps, domain } = createDomain()
      const { wsClient } = connect(domain)
      state.wsStatus.value = IM_WS_STATUS.CONNECTED
      expect(() => domain.sendFileCardMessage('c1', [])).toThrow('请选择要分享的文件或文件夹')
      expect(wsClient.sendEnvelope).not.toHaveBeenCalled()
    })
  })
})
