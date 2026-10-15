import { computed } from 'vue'

import { FAILED, RECALLED, SENDING, STORED } from '@/constants/messageStatus'
import { useCurrentUserStore } from '@/store/currentUser'
import { createClientId } from '@/utils/id'
import { createImWebSocketClient, IM_WS_STATUS } from '@/utils/imWebSocket'
import { logger } from '@/utils/logger'

const OUTBOUND_TASK_TIMEOUT_MS = 30_000
// 54 号审计 §2.6（WS 连接锁卡死窗口）：connectionLock 的复位完全依赖
// onStatusChange 回调必然到来，但存在两条不满足该前提的异常路径：
//   ① wsClient.connect() 同步抛错（imWebSocket 构造器：URL 非法 / 浏览器禁用
//      WebSocket / 非安全上下文）—— 异常穿出 ensureWebSocketConnected，
//      CONNECTING/CONNECTION_ERROR 状态事件永远不会派发；
//   ② 连接长期停在 CONNECTING（防火墙静默吞 TCP 握手）—— 浏览器对半开握手的
//      超时没有统一保证，onerror/onclose 可能几十秒甚至永不触发。
// 两条路径都会把锁留在 true ⇒ 之后所有 ensureWebSocketConnected() 直接短路，
// IM 永久离线且无自愈。①改为 catch 后立即释放；②加看护超时强制放行下一次重连。
const CONNECTION_LOCK_WATCHDOG_MS = 30_000

export function createRealtimeDomain(state, deps) {
  const {
    wsStatus,
    lastPongTime,
    lastWsError,
    myPresence,
    conversations,
    activeConversationId,
  } = state
  const {
    clearReadSyncTimers,
    ensureMessageBucket,
    isConversationEffectivelyVisible,
    loadConversationMessages,
    loadConversations,
    loadUnreadCount,
    mergeConversationMessage,
    markBucketChanged,
    scheduleReadSync,
    syncConversationMessages,
    touchConversation,
    updateConversationUnread,
    updatePendingMessageStatus,
  } = deps

  const wsConnected = computed(() => wsStatus.value === IM_WS_STATUS.CONNECTED)
  let wsClient = null
  let connectionLock = false
  // 54 号审计 §2.6：connectionLock 的看护定时器句柄，防 CONNECTING 挂起（见常量处注释）。
  let connectionLockWatchdog = null
  const outboundQueue = []
  let activeOutboundTask = null
  const outboundTaskByRequestId = new Map()

  function resolveOutboundTask(requestId, success, error = null) {
    if (!requestId) {
      return
    }
    const task = outboundTaskByRequestId.get(requestId)
    if (!task) {
      return
    }
    if (task._timeoutId) {
      clearTimeout(task._timeoutId)
    }
    outboundTaskByRequestId.delete(requestId)
    if (activeOutboundTask?.requestId === requestId) {
      activeOutboundTask = null
    } else {
      const index = outboundQueue.findIndex((item) => item.requestId === requestId)
      if (index >= 0) {
        outboundQueue.splice(index, 1)
      }
    }
    if (!success) {
      updatePendingMessageStatus(task.conversationId, task.clientMessageId, { status: FAILED })
      if (error) {
        logger.warn('[realtimeDomain] outbound task failed:', error)
        lastWsError.value = error?.message || String(error)
      }
    }
    drainOutboundQueue()
  }

  function failAllOutboundTasks(error = null) {
    if (activeOutboundTask) {
      if (activeOutboundTask._timeoutId) {
        clearTimeout(activeOutboundTask._timeoutId)
      }
      updatePendingMessageStatus(
        activeOutboundTask.conversationId,
        activeOutboundTask.clientMessageId,
        { status: FAILED },
      )
      outboundTaskByRequestId.delete(activeOutboundTask.requestId)
      activeOutboundTask = null
    }
    while (outboundQueue.length) {
      const task = outboundQueue.shift()
      if (task._timeoutId) {
        clearTimeout(task._timeoutId)
      }
      updatePendingMessageStatus(task.conversationId, task.clientMessageId, { status: FAILED })
      outboundTaskByRequestId.delete(task.requestId)
    }
    if (error) {
      logger.warn('[realtimeDomain] all outbound tasks failed:', error)
      lastWsError.value = error?.message || String(error)
    }
  }

  function drainOutboundQueue() {
    if (activeOutboundTask || !wsConnected.value || !outboundQueue.length) {
      return
    }
    const task = outboundQueue.shift()
    const sent = wsClient?.sendEnvelope(task.envelope)
    if (!sent) {
      outboundTaskByRequestId.delete(task.requestId)
      updatePendingMessageStatus(task.conversationId, task.clientMessageId, { status: FAILED })
      logger.warn('[realtimeDomain] send failed: connection not established')
      lastWsError.value = '实时连接未建立'
      drainOutboundQueue()
      return
    }
    activeOutboundTask = task
    // 超时起点 = 实际发送时刻（J-3）：此前 setTimeout 在 enqueueOutboundTask 入队时就挂上，
    // 排队任务要等前面所有任务各自 30s 超时/ACK 依次释放，等真正发出去时剩余等待窗口已被
    // 排队时间吃掉 —— 半开连接下连发 N 条，第 2 条起实际只等 30s-(N-2)×30s 甚至刚发出就判
    // 超时。现在每条任务从「真正 send」起算完整的 30s ACK 等待。
    task._timeoutId = setTimeout(() => {
      if (outboundTaskByRequestId.has(task.requestId)) {
        resolveOutboundTask(task.requestId, false, new Error('消息发送超时'))
      }
    }, OUTBOUND_TASK_TIMEOUT_MS)
  }

  function enqueueOutboundTask(task) {
    outboundTaskByRequestId.set(task.requestId, task)
    outboundQueue.push(task)
    drainOutboundQueue()
  }

  function handleAck(envelope) {
    const conversationId = envelope?.conversationId
    const clientMessageId = envelope?.clientMessageId
    const messageId = envelope?.payload?.messageId ?? null
    if (!conversationId || !clientMessageId) {
      return
    }
    updatePendingMessageStatus(conversationId, clientMessageId, {
      messageId,
      status: STORED,
    })
    resolveOutboundTask(envelope?.requestId, true)
  }

  function handleReceivedMessage(envelope) {
    const conversationId = envelope?.conversationId
    const currentUserId = useCurrentUserStore().profile?.id ?? null
    const senderUserId = envelope?.payload?.senderUserId ?? null
    if (!conversationId) {
      return
    }
    mergeConversationMessage(conversationId, {
      ...envelope.payload,
      clientMessageId: envelope.clientMessageId,
      conversationId,
      status: STORED,
    })
    resolveOutboundTask(envelope?.requestId, true)
    const receivedConversation = conversations.value.find((item) => item.id === conversationId)
    const selfMessage = Boolean(currentUserId && senderUserId === currentUserId)
    // 已读必须以真实可见为准，不能只看 activeConversationId。
    const shouldAutoRead = isConversationEffectivelyVisible(conversationId) || selfMessage
    updateConversationUnread(
      conversationId,
      shouldAutoRead ? 0 : (receivedConversation?.unreadCount || 0) + 1,
    )
    touchConversation(conversationId)
    if (shouldAutoRead) {
      scheduleReadSync(conversationId)
    }
    // J-10：不再对全局 unreadCount 做实时累加 —— 它是服务端系统通知未读数的真源
    // （notificationDomain.loadUnreadCount 整体覆盖写入），此前同一条通知会在这里
    // 再 +1 一次，两个口径互相打架。侧栏徽标实际展示的
    // totalConversationUnreadCount 由会话未读求和得出，上面 updateConversationUnread 已覆盖。
  }

  function handleReadUpdated(envelope) {
    const conversationId = envelope?.conversationId
    const readerUserId = envelope?.payload?.readerUserId
    const currentUserId = useCurrentUserStore().profile?.id ?? null
    if (!conversationId || !readerUserId) {
      return
    }
    if (readerUserId === currentUserId) {
      updateConversationUnread(conversationId, 0)
      return
    }
    if (isConversationEffectivelyVisible(conversationId)) {
      loadConversationMessages(conversationId).catch((error) => {
        logger.warn('[realtimeDomain] loadConversationMessages failed:', error)
        lastWsError.value = error?.message || String(error)
      })
    }
  }

  function handleMessageRecalled(envelope) {
    const conversationId = envelope?.conversationId
    const messageId = envelope?.payload?.messageId
    if (!conversationId || !messageId) {
      return
    }
    const bucket = ensureMessageBucket(conversationId)
    const index = bucket.findIndex((item) => Number(item.messageId) === Number(messageId))
    if (index >= 0) {
      bucket.splice(index, 1, {
        ...bucket[index],
        status: RECALLED,
        content: '',
        fileCard: null,
        recallByUserId: envelope.payload?.recallByUserId ?? null,
        recallTime: envelope.payload?.recallTime ?? null,
        recallReason: envelope.payload?.recallReason || '',
      })
      markBucketChanged()
    }
  }

  function ensureWebSocketConnected() {
    // 认证已改由 HttpOnly Cookie 管理，通过 profile 判断登录状态
    const currentUserStore = useCurrentUserStore()
    if (!currentUserStore.profile) {
      disconnectWebSocket()
      return
    }
    if (!wsClient) {
      wsClient = createImWebSocketClient({
        onStatusChange: (status) => {
          const previousStatus = wsStatus.value
          wsStatus.value = status
          // 54 号审计 §2.6：状态回调是连接锁的正常复位路径，改经 releaseConnectionLock
          // 统一释放（同时清掉看护定时器，避免正常路径下看护空转/误报）。
          releaseConnectionLock()
          if (status === IM_WS_STATUS.CONNECTED && previousStatus !== IM_WS_STATUS.CONNECTED) {
            drainOutboundQueue()
            syncConversationMessages().catch((error) => {
              logger.warn('[realtimeDomain] syncConversationMessages failed:', error)
              lastWsError.value = error?.message || String(error)
            })
            // 审计 frontend 🟠-1：断连期只对当前激活会话做了增量补拉
            // （syncConversationMessages 默认取 activeConversationId），**其他**会话在断连期
            // 收到的消息不产生 MESSAGE_RECEIVED 也不补拉 ⇒ 未读数/排序在长连接存续期内
            // 停留在断连前的旧值，直到下次切团队/重进页面才被服务端校准，用户感知「丢消息」。
            // 重连成功后全量刷新会话列表（服务端未读数为真源，conversationDomain 自带
            // 过期响应令牌防竞态）与系统通知未读数；失败只记 lastWsError，不影响连接状态。
            if (loadConversations) {
              loadConversations().catch((error) => {
                logger.warn('[realtimeDomain] loadConversations failed:', error)
                lastWsError.value = error?.message || String(error)
              })
            }
            if (loadUnreadCount) {
              loadUnreadCount().catch((error) => {
                logger.warn('[realtimeDomain] loadUnreadCount failed:', error)
                lastWsError.value = error?.message || String(error)
              })
            }
            return
          }
          // 不在断连时立即失败所有待发任务，保留它们等待重连后重试
          // 只有在 disconnectWebSocket() 手动断开时才会清空队列
        },
        onMessage: (envelope) => {
          if (envelope?.type === 'PONG') {
            lastPongTime.value = envelope.timestamp || Date.now()
            return
          }
          if (envelope?.type === 'AUTH_OK') {
            myPresence.value = {
              userId: envelope.payload?.userId ?? null,
              online: true,
              connectionCount: envelope.payload?.connectionCount ?? 1,
              lastActiveTime: null,
            }
            return
          }
          if (envelope?.type === 'MESSAGE_ACK') {
            handleAck(envelope)
            return
          }
          if (envelope?.type === 'MESSAGE_RECEIVED') {
            handleReceivedMessage(envelope)
            return
          }
          if (envelope?.type === 'READ_UPDATED') {
            handleReadUpdated(envelope)
            return
          }
          if (envelope?.type === 'MESSAGE_RECALLED') {
            handleMessageRecalled(envelope)
            return
          }
          if (envelope?.type === 'ERROR') {
            const message = envelope?.payload?.message || 'IM 实时消息处理失败'
            logger.warn('[realtimeDomain] server ERROR envelope:', message)
            if (envelope?.requestId && outboundTaskByRequestId.has(envelope.requestId)) {
              resolveOutboundTask(envelope.requestId, false, new Error(message))
              return
            }
            lastWsError.value = message
            return
          }
          // 未识别类型不再静默丢弃：服务端新增事件而前端未同步升级时，
          // 只有留下一条日志才能从「消息凭空消失」定位到协议漂移（审计 12-第三节低危项）。
          logger.warn(
            '[realtimeDomain] 收到未知的 WebSocket 信封类型，已忽略:',
            envelope?.type ?? '(无 type 字段)',
          )
        },
        onError: (error) => {
          logger.warn('[realtimeDomain] WebSocket error:', error)
          lastWsError.value = error?.message || String(error)
        },
      })
    }
    if (connectionLock) {
      return
    }
    connectionLock = true
    // 看护兜底（54 号审计 §2.6）：若 CONNECTION_ERROR/CONNECTED 迟迟不来
    // （CONNECTING 挂起路径），到点强制释放锁，让下一次 ensureWebSocketConnected
    // （UI 重连按钮 / online 事件）能再次发起 connect；正常路径下状态回调先到、
    // 会先释放锁并清掉本定时器，看护不会产生副作用。
    if (connectionLockWatchdog) {
      clearTimeout(connectionLockWatchdog)
    }
    connectionLockWatchdog = setTimeout(() => {
      connectionLockWatchdog = null
      if (connectionLock) {
        connectionLock = false
        logger.warn(
          '[realtimeDomain] 连接锁看护超时（连接长期无状态回调），已强制释放连接锁',
        )
      }
    }, CONNECTION_LOCK_WATCHDOG_MS)
    try {
      wsClient.connect()
    } catch (error) {
      // 同步异常路径（54 号审计 §2.6）：imWebSocket 构造器对 URL 非法等情形会
      // 直接 throw，此前锁已置 true 且无任何状态回调 ⇒ 锁死。立即释放锁并清看护，
      // 让后续重试（下一次 ensureWebSocketConnected）仍有机会重连。
      releaseConnectionLock('connect() 同步异常')
      lastWsError.value = error?.message || String(error)
      logger.warn('[realtimeDomain] wsClient.connect() 同步抛错，已释放连接锁:', error)
    }
  }

  /**
   * 释放连接锁并清掉看护定时器（54 号审计 §2.6）。
   * 状态回调（onStatusChange）与 connect() 同步异常两条复位路径共用，
   * reason 仅用于日志定位释放来源。
   */
  function releaseConnectionLock(reason = '') {
    connectionLock = false
    if (connectionLockWatchdog) {
      clearTimeout(connectionLockWatchdog)
      connectionLockWatchdog = null
    }
    if (reason) {
      logger.debug(`[realtimeDomain] connectionLock released: ${reason}`)
    }
  }

  function disconnectWebSocket() {
    clearReadSyncTimers()
    failAllOutboundTasks(new Error('实时连接已关闭'))
    // 54 号审计 §2.6：手动断开也必须释放连接锁（若断开发生在 CONNECTING 挂起期间，
    // 状态回调可能不会再触发），并清掉看护定时器 —— 否则下次登录 IM 无法重连。
    releaseConnectionLock('disconnectWebSocket')
    if (wsClient) {
      wsClient.disconnect()
    }
    wsStatus.value = IM_WS_STATUS.DISCONNECTED
  }

  /**
   * 手动重新连接：包一层 wsClient.reconnect()，供 UI 在重连达上限的终态
   * 提供『重新连接』按钮时调用（P1-E2）。
   */
  function reconnectWebSocket() {
    if (wsClient) {
      wsClient.reconnect()
    }
  }

  function sendTextMessage(conversationId, content, mentions = []) {
    const normalizedContent = typeof content === 'string' ? content.trim() : ''
    if (!normalizedContent) {
      return null
    }
    const currentUserStore = useCurrentUserStore()
    const clientMessageId = createClientId()
    const requestId = createClientId()
    mergeConversationMessage(conversationId, {
      conversationId,
      senderUserId: currentUserStore.profile?.id ?? null,
      senderUsername: currentUserStore.profile?.username || '',
      senderName: currentUserStore.profile?.name || '',
      senderAvatar: currentUserStore.profile?.avatar || '',
      content: normalizedContent,
      messageType: 'TEXT',
      mentions,
      clientMessageId,
      createTime: new Date().toISOString(),
      status: SENDING,
      readByPeer: false,
      readCount: 0,
    })
    touchConversation(conversationId)
    const envelope = {
      type: 'SEND_TEXT',
      requestId,
      clientMessageId,
      conversationId,
      payload: {
        content: normalizedContent,
        mentions,
      },
      timestamp: Date.now(),
    }
    if (!wsConnected.value) {
      updatePendingMessageStatus(conversationId, clientMessageId, { status: FAILED })
      throw new Error('实时连接未建立')
    }
    enqueueOutboundTask({ requestId, clientMessageId, conversationId, envelope })
    return clientMessageId
  }

  function buildFileCardPayload(items = []) {
    const list = Array.isArray(items) ? items.filter((item) => item?.id) : []
    if (!list.length) {
      throw new Error('请选择要分享的文件或文件夹')
    }
    const parentId = list[0].parentId ?? null
    if (list.some((item) => (item.parentId ?? null) !== parentId)) {
      throw new Error('多文件分享必须来自同一文件夹')
    }
    return {
      shareType:
        list.length === 1 ? (list[0].type === 0 ? 'SINGLE_FOLDER' : 'SINGLE_FILE') : 'MULTI_FILE',
      ownerUserId: useCurrentUserStore().profile?.id ?? null,
      parentId,
      entryCount: list.length,
      entries: list.map((item) => ({
        fileId: item.id,
        fileType: item.type,
        originalName: item.fileName,
        category: item.category ?? null,
        fileSize: item.fileSize ?? null,
        parentId: item.parentId ?? null,
        storePath: item.storePath || '',
        modifyTime: item.modifyTime ?? null,
      })),
    }
  }

  function sendFileCardMessage(conversationId, items) {
    const currentUserStore = useCurrentUserStore()
    const fileCard = buildFileCardPayload(items)
    const clientMessageId = createClientId()
    const requestId = createClientId()
    mergeConversationMessage(conversationId, {
      conversationId,
      senderUserId: currentUserStore.profile?.id ?? null,
      senderUsername: currentUserStore.profile?.username || '',
      senderName: currentUserStore.profile?.name || '',
      senderAvatar: currentUserStore.profile?.avatar || '',
      content: '',
      fileCard,
      messageType: 'FILE_CARD',
      clientMessageId,
      createTime: new Date().toISOString(),
      status: SENDING,
      readByPeer: false,
      readCount: 0,
    })
    touchConversation(conversationId)
    const envelope = {
      type: 'SEND_FILE_CARD',
      requestId,
      clientMessageId,
      conversationId,
      payload: {
        fileIds: items.map((item) => item.id),
      },
      timestamp: Date.now(),
    }
    if (!wsConnected.value) {
      updatePendingMessageStatus(conversationId, clientMessageId, { status: FAILED })
      throw new Error('实时连接未建立')
    }
    enqueueOutboundTask({ requestId, clientMessageId, conversationId, envelope })
    return clientMessageId
  }

  return {
    wsConnected,
    ensureWebSocketConnected,
    disconnectWebSocket,
    reconnectWebSocket,
    sendTextMessage,
    sendFileCardMessage,
    buildFileCardPayload,
  }
}
