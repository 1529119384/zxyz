import { computed } from 'vue'

import { fetchMyPresence } from '@/api/im'
import { SYSTEM, TEAM_NOTIFICATION } from '@/constants/conversationTypes'
import { FAILED, RECALLED, SENDING, STORED } from '@/constants/messageStatus'
import { useCurrentUserStore } from '@/store/currentUser'
import { createClientId } from '@/utils/id'
import { createImWebSocketClient, IM_WS_STATUS } from '@/utils/imWebSocket'
import { logger } from '@/utils/logger'

const OUTBOUND_TASK_TIMEOUT_MS = 30_000

export function createRealtimeDomain(state, deps) {
  const {
    wsStatus,
    lastPongTime,
    lastWsError,
    myPresence,
    conversations,
    unreadCount,
    activeConversationId,
  } = state
  const {
    clearReadSyncTimers,
    ensureMessageBucket,
    isConversationEffectivelyVisible,
    loadConversationMessages,
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
  }

  function enqueueOutboundTask(task) {
    outboundTaskByRequestId.set(task.requestId, task)
    outboundQueue.push(task)
    task._timeoutId = setTimeout(() => {
      if (outboundTaskByRequestId.has(task.requestId)) {
        resolveOutboundTask(task.requestId, false, new Error('消息发送超时'))
      }
    }, OUTBOUND_TASK_TIMEOUT_MS)
    drainOutboundQueue()
  }

  async function loadMyPresence() {
    const response = await fetchMyPresence()
    myPresence.value = response?.data || null
    return myPresence.value
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
    if (!selfMessage && [SYSTEM, TEAM_NOTIFICATION].includes(receivedConversation?.type)) {
      unreadCount.value = Math.max(0, Number(unreadCount.value || 0) + 1)
    }
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
      const conversation = conversations.value.find((item) => item.id === conversationId)
      if ([SYSTEM, TEAM_NOTIFICATION].includes(conversation?.type)) {
        unreadCount.value = 0
      }
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
        recallTime: envelope.payload?.recallTime || null,
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
          connectionLock = false
          if (status === IM_WS_STATUS.CONNECTED && previousStatus !== IM_WS_STATUS.CONNECTED) {
            drainOutboundQueue()
            syncConversationMessages().catch((error) => {
              logger.warn('[realtimeDomain] syncConversationMessages failed:', error)
              lastWsError.value = error?.message || String(error)
            })
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
          }
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
    wsClient.connect()
  }

  function disconnectWebSocket() {
    clearReadSyncTimers()
    failAllOutboundTasks(new Error('实时连接已关闭'))
    if (wsClient) {
      wsClient.disconnect()
    }
    wsStatus.value = IM_WS_STATUS.DISCONNECTED
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
        modifyTime: item.modifyTime || null,
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
    loadMyPresence,
    ensureWebSocketConnected,
    disconnectWebSocket,
    sendTextMessage,
    sendFileCardMessage,
    buildFileCardPayload,
  }
}
