import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

import { createRealtimeDomain } from '@/store/im/realtimeDomain'
import { IM_WS_STATUS } from '@/utils/imWebSocket'

vi.mock('@/api/im', () => ({
  fetchMyPresence: vi.fn(),
}))

// 固定当前用户，避免真实 Pinia 依赖
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: () => ({
    profile: { id: 100, username: 'me', name: 'Me', avatar: '' },
  }),
}))

let wsClientInstance = null
let onMessageHandler = null
let onStatusChangeHandler = null
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
  return { onMessage: onMessageHandler, wsClient: wsClientInstance }
}

beforeEach(() => {
  wsClientInstance = null
  onMessageHandler = null
  onStatusChangeHandler = null
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
  })
})
