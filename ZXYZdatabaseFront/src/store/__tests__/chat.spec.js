import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useChatStore } from '@/store/chat'
import { IM_WS_STATUS } from '@/utils/imWebSocket'
import { RECALLED } from '@/constants/messageStatus'
import { fetchConversationMessages, fetchMyConversations, updateConversationRead } from '@/api/im'

// chat.js 是纯接线层：自身只有 3 个 computed + _setTeamBridge，真正的逻辑在
// store/im/*Domain 里（那几个域另有 spec）。故此处刻意**不 mock 域**，而是
// mock 最外层的 API / WebSocket / currentUser，让真实域参与运行 —— 这样断言
// 的是「接线契约真的成立」（比如桥接进去的 team 方法是否真被域用到），
// 而不是「mock 被正确调用」这种同义反复。
vi.mock('@/api/im', () => ({
  fetchImHealth: vi.fn(),
  fetchMyConversations: vi.fn(),
  fetchConversation: vi.fn(),
  createDirectConversation: vi.fn(),
  fetchTeamConversation: vi.fn(),
  fetchConversationMessages: vi.fn(),
  searchConversationMessages: vi.fn(),
  resolveMessageFileCard: vi.fn(),
  recallMessage: vi.fn(),
  updateConversationRead: vi.fn(),
  fetchSystemNotifications: vi.fn(),
  fetchSystemNotificationUnreadCount: vi.fn(),
  fetchMyPresence: vi.fn(),
  fetchUserPresence: vi.fn(),
  markSystemNotificationRead: vi.fn(),
}))

// 只创建客户端、绝不真的连；同时把回调暴露出来，供测试注入 WS 事件。
// chat.js 的 realtimeDomain deps（第 89-105 行那批箭头函数）只有在真实
// WS 事件回流时才会被执行，故必须能从这里手动触发。
const { wsStub } = vi.hoisted(() => ({ wsStub: { handlers: null, client: null } }))
vi.mock('@/utils/imWebSocket', () => ({
  IM_WS_STATUS: {
    DISCONNECTED: 'DISCONNECTED',
    CONNECTING: 'CONNECTING',
    CONNECTED: 'CONNECTED',
    RECONNECTING: 'RECONNECTING',
    CONNECTION_ERROR: 'CONNECTION_ERROR',
  },
  createImWebSocketClient: (options) => {
    wsStub.handlers = options
    wsStub.client = {
      connect: vi.fn(),
      disconnect: vi.fn(),
      reconnect: vi.fn(),
      send: vi.fn(),
      isConnected: () => false,
    }
    return wsStub.client
  },
}))

vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: () => ({ profile: { id: 100, username: 'me', name: 'Me', avatar: '' } }),
}))

/** 团队 Store 的假实现，只暴露 _setTeamBridge 约定使用的那几个成员。 */
function makeTeamStore(overrides = {}) {
  return {
    selectedTeamId: 7,
    resolveTeamScopedParams: vi.fn((teamId) => ({ teamId })),
    setSelectedTeam: vi.fn(),
    handleTeamAccessRevoked: vi.fn(),
    ...overrides,
  }
}

function makeConversation(overrides = {}) {
  return { id: 1, type: 'TEAM', teamId: 7, name: '会话', unreadCount: 0, ...overrides }
}

let store

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  wsStub.handlers = null
  wsStub.client = null
  fetchMyConversations.mockResolvedValue({ data: [] })
  fetchConversationMessages.mockResolvedValue({ data: [] })
  // scheduleReadSync 的定时器回调里会对返回值 .catch()，故必须真返回 Promise
  updateConversationRead.mockResolvedValue({})
  store = useChatStore()
})

afterEach(() => {
  // scheduleReadSync 会挂 setTimeout；清掉避免跨用例泄漏。
  store?.clearReadSyncTimers?.()
})

describe('useChatStore 初始状态与别名', () => {
  it('未连接时 wsStatus 为 DISCONNECTED，且 status 是同一只读视图', () => {
    expect(store.wsStatus).toBe(IM_WS_STATUS.DISCONNECTED)
    expect(store.status).toBe(IM_WS_STATUS.DISCONNECTED)
  })

  it('connected 由 realtimeDomain.wsConnected 派生，初始为 false', () => {
    expect(store.connected).toBe(false)
  })

  it('列表类状态初始为空', () => {
    expect(store.conversations).toEqual([])
    expect(store.notifications).toEqual([])
    expect(store.searchResults).toEqual([])
    expect(store.unreadCount).toBe(0)
    expect(store.myPresence).toBeNull()
    expect(store.lastPongTime).toBeNull()
    expect(store.lastWsError).toBeNull()
  })

  it('activeConversationId / activeConversation / activeMessages 初始为 null 与空数组', () => {
    expect(store.activeConversationId).toBeNull()
    expect(store.activeConversation).toBeNull()
    expect(store.activeMessages).toEqual([])
  })

  it('state 内部字段（selectedTeamId / windowFocused / chatViewActive）不对外暴露，仅供四个域读取', () => {
    // 它们在 state 里（域要用），但**不在 store 的返回对象里**。外部只能经
    // setWindowFocused / setChatViewActive / _setTeamBridge 间接影响，并用
    // isConversationEffectivelyVisible 观察结果。若将来有人以为
    // store.selectedTeamId 可用，这条会立刻炸出来。
    expect(store.selectedTeamId).toBeUndefined()
    expect(store.windowFocused).toBeUndefined()
    expect(store.chatViewActive).toBeUndefined()
  })
})

describe('useChatStore 对团队 Store 的桥接（_setTeamBridge）', () => {
  it('注入后 _selectedTeamId 被写成团队 Store 的值（用 loadConversations 的默认参数验证）', async () => {
    const teamStore = makeTeamStore({ selectedTeamId: 42 })
    store._setTeamBridge(teamStore)

    // loadConversations(teamId = selectedTeamId.value)：不传参即取 state 里的值，
    // 所以实参 42 恰好证明 _setTeamBridge 确实写入了 selectedTeamId。
    await store.loadConversations()

    expect(teamStore.resolveTeamScopedParams).toHaveBeenCalledWith(42)
  })

  it('桥接后 loadConversations 真的走团队 Store 的 resolveTeamScopedParams', async () => {
    const teamStore = makeTeamStore()
    store._setTeamBridge(teamStore)
    fetchMyConversations.mockResolvedValue({ data: [makeConversation()] })

    await store.loadConversations(7)

    expect(teamStore.resolveTeamScopedParams).toHaveBeenCalledWith(7)
    expect(fetchMyConversations).toHaveBeenCalledWith({ teamId: 7 })
    expect(store.conversations).toHaveLength(1)
  })

  it('未桥接时 resolveTeamScopedParams 回退为空对象（占位实现，不抛错）', async () => {
    await store.loadConversations(7)
    expect(fetchMyConversations).toHaveBeenCalledWith({})
  })

  it('桥接可重复调用，后一次覆盖前一次', async () => {
    const first = makeTeamStore({
      selectedTeamId: 1,
      resolveTeamScopedParams: vi.fn(() => ({ scope: 'first' })),
    })
    const second = makeTeamStore({
      selectedTeamId: 2,
      resolveTeamScopedParams: vi.fn(() => ({ scope: 'second' })),
    })
    store._setTeamBridge(first)
    store._setTeamBridge(second)

    await store.loadConversations()

    expect(second.resolveTeamScopedParams).toHaveBeenCalledWith(2)
    expect(first.resolveTeamScopedParams).not.toHaveBeenCalled()
    expect(fetchMyConversations).toHaveBeenLastCalledWith({ scope: 'second' })
  })

  it('桥接只换依赖，不动已有会话数据', async () => {
    fetchMyConversations.mockResolvedValue({ data: [makeConversation({ id: 9 })] })
    await store.loadConversations()
    store._setTeamBridge(makeTeamStore())
    expect(store.conversations).toHaveLength(1)
    expect(store.conversations[0].id).toBe(9)
  })
})

describe('useChatStore.totalConversationUnreadCount', () => {
  it('空列表为 0', () => {
    expect(store.totalConversationUnreadCount).toBe(0)
  })

  it('累加各会话未读数', async () => {
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, unreadCount: 3 }), makeConversation({ id: 2, unreadCount: 4 })],
    })
    await store.loadConversations()
    expect(store.totalConversationUnreadCount).toBe(7)
  })

  it('负数未读被夹到 0（normalizeConversation 不夹，由本 computed 兜底）', async () => {
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, unreadCount: -5 }), makeConversation({ id: 2, unreadCount: 2 })],
    })
    await store.loadConversations()
    expect(store.conversations[0].unreadCount).toBe(-5)
    expect(store.totalConversationUnreadCount).toBe(2)
  })

  it('null/缺失未读按 0 计', async () => {
    fetchMyConversations.mockResolvedValue({
      data: [
        makeConversation({ id: 1, unreadCount: null }),
        { id: 2, type: 'TEAM', teamId: 7, name: 'B' },
      ],
    })
    await store.loadConversations()
    expect(store.totalConversationUnreadCount).toBe(0)
  })

  it('数字字符串被数值化后参与累加', async () => {
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, unreadCount: '3' })],
    })
    await store.loadConversations()
    expect(store.totalConversationUnreadCount).toBe(3)
  })

  it('非数值未读：归一化层留下 NaN，但合计被 || 0 兜底为 0（固化现状）', async () => {
    // normalizeConversation 是 Number(raw.unreadCount || 0) → 'abc' 变 NaN；
    // 合计处的 Number(conv.unreadCount || 0) 因 NaN 为假值而回落 0。
    // 结果：脏数据不会把合计污染成 NaN，但会话自身的字段确实是 NaN。
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, unreadCount: 'abc' })],
    })
    await store.loadConversations()
    expect(Number.isNaN(store.conversations[0].unreadCount)).toBe(true)
    expect(store.totalConversationUnreadCount).toBe(0)
  })

  it('重新加载会话后合计随之更新（computed 挂在同一份响应式数据上）', async () => {
    fetchMyConversations.mockResolvedValue({ data: [makeConversation({ id: 1, unreadCount: 1 })] })
    await store.loadConversations()
    expect(store.totalConversationUnreadCount).toBe(1)

    fetchMyConversations.mockResolvedValue({ data: [makeConversation({ id: 1, unreadCount: 6 })] })
    await store.loadConversations()
    expect(store.totalConversationUnreadCount).toBe(6)
  })
})

describe('useChatStore.activeConversation / activeMessages / 可见性', () => {
  beforeEach(async () => {
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, name: '甲' }), makeConversation({ id: 2, name: '乙' })],
    })
    await store.loadConversations()
  })

  it('activeConversation 按 id 命中，未命中返回 null', async () => {
    await store.openConversation(2)
    store.clearReadSyncTimers()
    expect(store.activeConversationId).toBe(2)
    expect(store.activeConversation).toMatchObject({ id: 2, name: '乙' })
  })

  it('clearActiveConversation 后 activeConversation 回到 null', async () => {
    await store.openConversation(1)
    store.clearReadSyncTimers()
    expect(store.activeConversation).not.toBeNull()

    store.clearActiveConversation()
    expect(store.activeConversationId).toBeNull()
    expect(store.activeConversation).toBeNull()
    expect(store.activeMessages).toEqual([])
  })

  it('activeMessages 跟随 activeConversationId 切换（域内消息桶）', async () => {
    expect(store.activeMessages).toEqual([])
    await store.openConversation(1)
    store.clearReadSyncTimers()
    expect(store.activeMessages).toEqual([])
    expect(fetchConversationMessages).toHaveBeenCalled()
  })

  it('会话不可见：chat 视图未激活时 isConversationEffectivelyVisible 为 false', async () => {
    await store.openConversation(1)
    store.clearReadSyncTimers()
    store.setChatViewActive(false)
    store.setWindowFocused(true)
    expect(store.isConversationEffectivelyVisible(1)).toBe(false)
  })

  it('会话不可见：窗口失焦时同样为 false', async () => {
    await store.openConversation(1)
    store.clearReadSyncTimers()
    store.setChatViewActive(true)
    store.setWindowFocused(false)
    expect(store.isConversationEffectivelyVisible(1)).toBe(false)
  })

  it('会话可见：视图激活 + 窗口聚焦 + id 命中，三者齐备才为 true', async () => {
    await store.openConversation(1)
    store.clearReadSyncTimers()
    store.setChatViewActive(true)
    store.setWindowFocused(true)
    expect(store.isConversationEffectivelyVisible(1)).toBe(true)
  })

  it('id 不匹配（含字符串/数字混用）时判定为不可见', async () => {
    await store.openConversation(1)
    store.clearReadSyncTimers()
    store.setChatViewActive(true)
    store.setWindowFocused(true)
    expect(store.isConversationEffectivelyVisible(2)).toBe(false)
    // 域内用 Number(...) 比较，故字符串 '1' 视为同一会话
    expect(store.isConversationEffectivelyVisible('1')).toBe(true)
  })
})

describe('useChatStore 对外暴露的域方法透传', () => {
  it('消息发送与重连入口都是函数（reconnectWebSocket 为 P1-E2 断连横幅入口）', () => {
    for (const name of [
      'sendTextMessage',
      'sendFileCardMessage',
      'ensureConnected',
      'disconnect',
      'reconnectWebSocket',
      'loadConversations',
      'loadNotifications',
      'loadUnreadCount',
      'markRead',
      'openConversation',
      'searchMessages',
      'createDirectConversationAndOpen',
      'resolveFileCardMessage',
      'recallConversationMessage',
      'loadMyPresence',
      'cleanup',
    ]) {
      expect(typeof store[name], `${name} 应为函数`).toBe('function')
    }
  })

  it('通知域方法打到对应 API', async () => {
    const { fetchSystemNotifications } = await import('@/api/im')
    fetchSystemNotifications.mockResolvedValue({ data: { records: [], total: 0 } })
    await store.loadNotifications()
    expect(fetchSystemNotifications).toHaveBeenCalled()
  })

  it('cleanup 不抛错', () => {
    expect(() => store.cleanup()).not.toThrow()
  })
})

// chat.js 的 realtimeDomain deps（第 89-105 行）是一批绑定到会话域的箭头函数，
// 只有真实 WS 事件回流时才执行。这里用假 WS 客户端注入事件，断言的是**结果**
// （store 暴露的只读状态变化），而不是"某个 mock 被调用"。
describe('useChatStore 的实时链路接线（WS 事件 → 域 → store 状态）', () => {
  async function connectWithConversation() {
    fetchMyConversations.mockResolvedValue({
      data: [makeConversation({ id: 1, name: '甲', unreadCount: 0 })],
    })
    await store.loadConversations()
    // 先置为可见再 openConversation：这样 loadConversationMessages 的无游标分支
    // 会走上 scheduleReadSync dep（messageDomain 第 193-194 行）。
    store.setChatViewActive(true)
    store.setWindowFocused(true)
    await store.openConversation(1)
    store.clearReadSyncTimers()
    store.ensureConnected()
    return wsStub.handlers
  }

  it('ensureConnected 会创建 WS 客户端并把它接上', async () => {
    await connectWithConversation()
    expect(wsStub.client).not.toBeNull()
    expect(wsStub.client.connect).toHaveBeenCalled()
  })

  it('状态回调置为 CONNECTED 后 connected 变 true；置回 DISCONNECTED 后变 false', async () => {
    const handlers = await connectWithConversation()

    handlers.onStatusChange(IM_WS_STATUS.CONNECTED)
    expect(store.wsStatus).toBe(IM_WS_STATUS.CONNECTED)
    expect(store.connected).toBe(true)

    handlers.onStatusChange(IM_WS_STATUS.DISCONNECTED)
    expect(store.connected).toBe(false)
  })

  it('PONG 事件写入 lastPongTime', async () => {
    const handlers = await connectWithConversation()
    handlers.onMessage({ type: 'PONG', timestamp: 1700000000000 })
    expect(store.lastPongTime).toBe(1700000000000)
  })

  it('AUTH_OK 事件写入 myPresence', async () => {
    const handlers = await connectWithConversation()
    handlers.onMessage({ type: 'AUTH_OK', payload: { userId: 100, connectionCount: 2 } })
    expect(store.myPresence).toMatchObject({ userId: 100, online: true, connectionCount: 2 })
  })

  it('MESSAGE_RECEIVED 会经会话/消息域落进当前会话的消息桶（驱动 mergeConversationMessage 等 dep）', async () => {
    const handlers = await connectWithConversation()
    store.setChatViewActive(true)
    store.setWindowFocused(true)

    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-in-1',
      payload: { messageId: 500, senderUserId: 999, content: '来自对端', messageType: 'TEXT' },
    })

    expect(store.activeMessages.length).toBeGreaterThan(0)
    expect(store.activeMessages.at(-1)).toMatchObject({ messageId: 500, content: '来自对端' })
  })

  it('MESSAGE_RECEIVED 在会话不可见时把未读 +1；可见时清零', async () => {
    const handlers = await connectWithConversation()

    // 不可见：chat 视图未激活
    store.setChatViewActive(false)
    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-in-2',
      payload: { messageId: 501, senderUserId: 999, content: 'x', messageType: 'TEXT' },
    })
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBeGreaterThan(0)
    expect(store.totalConversationUnreadCount).toBeGreaterThan(0)

    // 可见：自动已读，未读归零
    store.setChatViewActive(true)
    store.setWindowFocused(true)
    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-in-3',
      payload: { messageId: 502, senderUserId: 999, content: 'y', messageType: 'TEXT' },
    })
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(0)
    store.clearReadSyncTimers()
  })

  it('自己发的消息（senderUserId 等于当前用户）不增加未读', async () => {
    const handlers = await connectWithConversation()
    store.setChatViewActive(false)

    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-self',
      payload: { messageId: 600, senderUserId: 100, content: '我自己', messageType: 'TEXT' },
    })

    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(0)
    store.clearReadSyncTimers()
  })

  it('MESSAGE_ACK 驱动 updatePendingMessageStatus dep 且不抛错', async () => {
    const handlers = await connectWithConversation()
    expect(() =>
      handlers.onMessage({
        type: 'MESSAGE_ACK',
        conversationId: 1,
        clientMessageId: 'cid-out-1',
        requestId: 'req-1',
        payload: { messageId: 700 },
      }),
    ).not.toThrow()
  })

  it('READ_UPDATED（他人已读）在会话可见时会重新拉取消息，但不改我的未读', async () => {
    const handlers = await connectWithConversation()
    // 先制造一条未读
    store.setChatViewActive(false)
    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-unread',
      payload: { messageId: 503, senderUserId: 999, content: 'z', messageType: 'TEXT' },
    })
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(1)

    // 置为可见后收到"对端已读"→ 走 loadConversationMessages；对端已读与我的未读无关
    store.setChatViewActive(true)
    store.setWindowFocused(true)
    const callsBefore = fetchConversationMessages.mock.calls.length
    handlers.onMessage({
      type: 'READ_UPDATED',
      conversationId: 1,
      payload: { readerUserId: 999, lastReadMessageId: 503 },
    })
    expect(fetchConversationMessages.mock.calls.length).toBeGreaterThan(callsBefore)
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(1)
    store.clearReadSyncTimers()
  })

  it('READ_UPDATED（自己已读）把未读清零，且不额外拉取消息', async () => {
    const handlers = await connectWithConversation()
    store.setChatViewActive(false)
    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-unread-2',
      payload: { messageId: 504, senderUserId: 999, content: 'w', messageType: 'TEXT' },
    })
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(1)

    const callsBefore = fetchConversationMessages.mock.calls.length
    handlers.onMessage({
      type: 'READ_UPDATED',
      conversationId: 1,
      payload: { readerUserId: 100, lastReadMessageId: 504 },
    })
    expect(fetchConversationMessages.mock.calls.length).toBe(callsBefore)
    expect(store.conversations.find((c) => c.id === 1).unreadCount).toBe(0)
  })

  it('MESSAGE_RECALLED 命中桶内消息时驱动 markBucketChanged dep，消息转为已撤回且正文清空', async () => {
    const handlers = await connectWithConversation()
    handlers.onMessage({
      type: 'MESSAGE_RECEIVED',
      conversationId: 1,
      clientMessageId: 'cid-recall',
      payload: { messageId: 800, senderUserId: 999, content: '待撤回', messageType: 'TEXT' },
    })
    expect(store.activeMessages.some((m) => Number(m.messageId) === 800)).toBe(true)

    handlers.onMessage({
      type: 'MESSAGE_RECALLED',
      conversationId: 1,
      payload: { messageId: 800, recallByUserId: 999, recallReason: '误发' },
    })

    const recalled = store.activeMessages.find((m) => Number(m.messageId) === 800)
    expect(recalled.status).toBe(RECALLED)
    expect(recalled.content).toBe('')
    expect(recalled.recallReason).toBe('误发')
  })

  it('MESSAGE_RECALLED 未命中（桶内无此消息）时静默跳过，不抛错', async () => {
    const handlers = await connectWithConversation()
    expect(() =>
      handlers.onMessage({
        type: 'MESSAGE_RECALLED',
        conversationId: 1,
        payload: { messageId: 99999 },
      }),
    ).not.toThrow()
  })

  it('未知类型事件被忽略，不抛错', async () => {
    const handlers = await connectWithConversation()
    expect(() => handlers.onMessage({ type: 'SOMETHING_ELSE' })).not.toThrow()
    expect(store.wsStatus).toBe(IM_WS_STATUS.DISCONNECTED)
  })

  it('disconnect 会驱动 clearReadSyncTimers dep 并断开客户端', async () => {
    await connectWithConversation()
    store.disconnect()
    expect(wsStub.client.disconnect).toHaveBeenCalled()
    expect(store.wsStatus).toBe(IM_WS_STATUS.DISCONNECTED)
  })
})
