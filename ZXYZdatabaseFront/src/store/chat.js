import { computed, readonly, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'

import { IM_WS_STATUS } from '@/utils/imWebSocket'

import { createConversationDomain } from './im/conversationDomain'
import { createMessageDomain } from './im/messageDomain'
import { createNotificationDomain } from './im/notificationDomain'
import { createRealtimeDomain } from './im/realtimeDomain'

export const useChatStore = defineStore('chat', () => {
  const notifications = ref([])
  const unreadCount = ref(0)
  const wsStatus = ref(IM_WS_STATUS.DISCONNECTED)
  const lastPongTime = ref(null)
  const lastWsError = ref(null)
  const myPresence = ref(null)
  const conversations = ref([])
  const messagesByConversation = shallowRef({})
  const activeConversationId = ref(null)
  const chatViewActive = ref(false)
  const windowFocused = ref(
    typeof document === 'undefined' ? true : document.visibilityState === 'visible',
  )
  const searchResults = ref([])

  // 团队状态通过 _setTeamBridge 注入，不直接依赖 useTeamStore。
  // 占位 ref 在桥接注入前保证 computed 不会报错。
  const _selectedTeamId = ref(null)
  const selectedTeamId = computed(() => _selectedTeamId.value)
  const totalConversationUnreadCount = computed(() =>
    conversations.value.reduce(
      (sum, conversation) => sum + Math.max(0, Number(conversation.unreadCount || 0)),
      0,
    ),
  )
  const activeConversation = computed(
    () => conversations.value.find((item) => item.id === activeConversationId.value) || null,
  )

  const state = {
    selectedTeamId,
    notifications,
    unreadCount,
    wsStatus,
    lastPongTime,
    lastWsError,
    myPresence,
    conversations,
    messagesByConversation,
    activeConversationId,
    chatViewActive,
    windowFocused,
    searchResults,
  }

  // 团队 Store 的方法通过 deps 对象注入；闭包在运行时解析，允许延迟绑定。
  const teamDeps = {
    resolveTeamScopedParams: () => ({}),
    setSelectedTeam: () => {},
    handleTeamAccessRevoked: () => {},
  }

  const notificationDomain = createNotificationDomain(state, {
    resolveTeamScopedParams: (teamId) => teamDeps.resolveTeamScopedParams(teamId),
  })

  // 消息域管理消息桶的 CRUD、加载和搜索，与会话域解耦。
  const messageDomain = createMessageDomain(state, {
    getConversations: () => conversations.value,
    isConversationEffectivelyVisible: (conversationId) => {
      // 延迟绑定：此时 conversationDomain 尚未创建，通过闭包延迟解析。
      return conversationDomain.isConversationEffectivelyVisible(conversationId)
    },
    scheduleReadSync: (conversationId) => {
      return conversationDomain.scheduleReadSync(conversationId)
    },
  })

  const conversationDomain = createConversationDomain(state, {
    messageDomain,
    resolveTeamScopedParams: (teamId) => teamDeps.resolveTeamScopedParams(teamId),
    setSelectedTeam: (teamId) => teamDeps.setSelectedTeam(teamId),
    // 团队状态清理由团队 Store 执行，聊天域只发出明确的访问撤销意图。
    handleTeamAccessRevoked: (teamId) => teamDeps.handleTeamAccessRevoked(teamId),
  })

  const realtimeDomain = createRealtimeDomain(state, {
    clearReadSyncTimers: () => conversationDomain.clearReadSyncTimers(),
    ensureMessageBucket: (conversationId) => conversationDomain.ensureMessageBucket(conversationId),
    isConversationEffectivelyVisible: (conversationId) =>
      conversationDomain.isConversationEffectivelyVisible(conversationId),
    loadConversationMessages: (conversationId, params) =>
      conversationDomain.loadConversationMessages(conversationId, params),
    mergeConversationMessage: (conversationId, messageLike) =>
      conversationDomain.mergeConversationMessage(conversationId, messageLike),
    scheduleReadSync: (conversationId) => conversationDomain.scheduleReadSync(conversationId),
    syncConversationMessages: (conversationId) =>
      conversationDomain.syncConversationMessages(conversationId),
    touchConversation: (conversationId) => conversationDomain.touchConversation(conversationId),
    updateConversationUnread: (conversationId, unreadValue) =>
      conversationDomain.updateConversationUnread(conversationId, unreadValue),
    updatePendingMessageStatus: (conversationId, clientMessageId, patch) =>
      conversationDomain.updatePendingMessageStatus(conversationId, clientMessageId, patch),
    markBucketChanged: () => conversationDomain.markBucketChanged(),
  })

  /**
   * 由 Pinia plugin（chatBridge）在 store 创建后调用，注入团队 Store 依赖。
   * 通过可变 deps 对象实现延迟绑定：domain 闭包捕获 teamDeps 引用，
   * 替换其属性后所有闭包自动使用新实现。
   */
  function _setTeamBridge(teamStore) {
    _selectedTeamId.value = teamStore.selectedTeamId
    teamDeps.resolveTeamScopedParams = (teamId) => teamStore.resolveTeamScopedParams(teamId)
    teamDeps.setSelectedTeam = (teamId) => teamStore.setSelectedTeam(teamId)
    teamDeps.handleTeamAccessRevoked = (teamId) => teamStore.handleTeamAccessRevoked(teamId)
  }

  const activeMessages = computed(() =>
    conversationDomain.getConversationMessages(activeConversationId.value),
  )

  return {
    notifications: readonly(notifications),
    unreadCount: readonly(unreadCount),
    wsStatus: readonly(wsStatus),
    status: readonly(wsStatus),
    lastPongTime: readonly(lastPongTime),
    lastWsError: readonly(lastWsError),
    myPresence: readonly(myPresence),
    conversations: readonly(conversations),
    activeConversationId: readonly(activeConversationId),
    activeConversation,
    activeMessages,
    searchResults: readonly(searchResults),
    totalConversationUnreadCount,
    connected: realtimeDomain.wsConnected,
    setChatViewActive: conversationDomain.setChatViewActive,
    setWindowFocused: conversationDomain.setWindowFocused,
    clearActiveConversation: conversationDomain.clearActiveConversation,
    isConversationEffectivelyVisible: conversationDomain.isConversationEffectivelyVisible,
    loadConversations: conversationDomain.loadConversations,
    loadNotifications: notificationDomain.loadNotifications,
    loadUnreadCount: notificationDomain.loadUnreadCount,
    markRead: notificationDomain.markRead,
    loadConversationMessages: conversationDomain.loadConversationMessages,
    openConversation: conversationDomain.openConversation,
    searchMessages: conversationDomain.searchMessages,
    createDirectConversationAndOpen: conversationDomain.createDirectConversationAndOpen,
    sendTextMessage: realtimeDomain.sendTextMessage,
    sendFileCardMessage: realtimeDomain.sendFileCardMessage,
    resolveFileCardMessage: conversationDomain.resolveFileCardMessage,
    recallConversationMessage: conversationDomain.recallConversationMessage,
    loadMyPresence: realtimeDomain.loadMyPresence,
    ensureConnected: realtimeDomain.ensureWebSocketConnected,
    disconnect: realtimeDomain.disconnectWebSocket,
    clearReadSyncTimers: conversationDomain.clearReadSyncTimers,
    cleanup: conversationDomain.cleanup,
    _setTeamBridge,
  }
})
