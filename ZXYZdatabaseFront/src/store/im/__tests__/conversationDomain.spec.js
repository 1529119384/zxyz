import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

import { createConversationDomain } from '@/store/im/conversationDomain'

vi.mock('@/api/im', () => ({
  createDirectConversation: vi.fn(),
  fetchConversation: vi.fn(),
  fetchMyConversations: vi.fn(),
  fetchTeamConversation: vi.fn(),
  updateConversationRead: vi.fn(),
}))

function createDomain() {
  const state = {
    selectedTeamId: ref(null),
    conversations: ref([]),
    activeConversationId: ref(null),
    lastWsError: ref(null),
    chatViewActive: ref(false),
    windowFocused: ref(true),
  }
  const messageDomain = {
    removeConversationBuckets: vi.fn(),
    removeTeamBuckets: vi.fn(),
    pruneOrphanBuckets: vi.fn(),
    ensureMessageBucket: vi.fn(() => []),
    getConversationMessages: vi.fn(() => []),
    loadConversationMessages: vi.fn(() => Promise.resolve([])),
    mergeConversationMessage: vi.fn(),
    replaceConversationMessages: vi.fn(() => []),
    updatePendingMessageStatus: vi.fn(),
    syncConversationMessages: vi.fn(),
    searchMessages: vi.fn(),
    resolveFileCardMessage: vi.fn(),
    recallConversationMessage: vi.fn(),
    markBucketChanged: vi.fn(),
    evictStaleBuckets: vi.fn(),
    cleanup: vi.fn(),
  }
  const deps = {
    messageDomain,
    resolveTeamScopedParams: vi.fn((teamId) => (teamId ? { teamId } : {})),
    setSelectedTeam: vi.fn(),
    handleTeamAccessRevoked: vi.fn(),
  }
  const domain = createConversationDomain(state, deps)
  return { state, deps, messageDomain, domain }
}

describe('conversationDomain', () => {
  describe('upsertConversation', () => {
    it('id 为空时返回 null，不写入', () => {
      const { state, domain } = createDomain()
      expect(domain.upsertConversation({})).toBeNull()
      expect(domain.upsertConversation(undefined)).toBeNull()
      expect(state.conversations.value).toEqual([])
    })

    it('传 null 会抛 TypeError（normalizeConversation 默认参数只兜 undefined）', () => {
      const { domain } = createDomain()
      expect(() => domain.upsertConversation(null)).toThrow()
    })

    it('已存在时合并字段并保持长度不变', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, name: 'old', unreadCount: 5 }]
      const merged = domain.upsertConversation({ id: 1, name: 'new', teamId: 9 })
      expect(merged.name).toBe('new')
      expect(merged.teamId).toBe(9)
      // 注意：normalizeConversation 会给 unreadCount 填默认值 0，
      // merge 后旧 unreadCount 5 会被覆盖为 0（真实行为）
      expect(merged.unreadCount).toBe(0)
      expect(state.conversations.value).toHaveLength(1)
    })

    it('不存在时 unshift 到最前', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, name: 'a' }]
      const inserted = domain.upsertConversation({ id: 2, name: 'b' })
      expect(inserted.id).toBe(2)
      expect(state.conversations.value.map((c) => c.id)).toEqual([2, 1])
    })
  })

  describe('updateConversationUnread', () => {
    it('未命中会话时静默返回', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, unreadCount: 5 }]
      expect(domain.updateConversationUnread(999, 3)).toBeUndefined()
      expect(state.conversations.value[0].unreadCount).toBe(5)
    })

    it('unreadCount 有 Math.max(0, ...) 下限', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, unreadCount: 5 }]
      domain.updateConversationUnread(1, -3)
      expect(state.conversations.value[0].unreadCount).toBe(0)
      domain.updateConversationUnread(1, 8)
      expect(state.conversations.value[0].unreadCount).toBe(8)
    })

    it('数字字符串会转成 Number', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, unreadCount: 0 }]
      domain.updateConversationUnread(1, '7')
      expect(state.conversations.value[0].unreadCount).toBe(7)
    })
  })

  describe('handleConversationAccessRevoked', () => {
    it('仅 conversationId：本地移除该会话并清理 activeConversationId', () => {
      const { state, deps, messageDomain, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = 1
      domain.handleConversationAccessRevoked({ conversationId: 1 })
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
      expect(messageDomain.removeConversationBuckets).toHaveBeenCalledWith(1)
      expect(state.activeConversationId.value).toBeNull()
      expect(deps.handleTeamAccessRevoked).not.toHaveBeenCalled()
      expect(messageDomain.removeTeamBuckets).not.toHaveBeenCalled()
    })

    it('仅 teamId：撤销团队访问、清掉该团队全部会话、清理对应消息桶', () => {
      const { state, deps, messageDomain, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 10 },
        { id: 3, teamId: 20 },
      ]
      domain.handleConversationAccessRevoked({ teamId: 10 })
      expect(deps.handleTeamAccessRevoked).toHaveBeenCalledWith(10)
      expect(state.conversations.value.map((c) => c.id)).toEqual([3])
      expect(messageDomain.removeTeamBuckets).toHaveBeenCalledTimes(1)
      // 剩余会话 id 组成的 Set 传给 removeTeamBuckets
      const allowedSet = messageDomain.removeTeamBuckets.mock.calls[0][0]
      expect([...allowedSet]).toEqual([3])
      expect(messageDomain.removeConversationBuckets).not.toHaveBeenCalled()
    })

    it('仅 teamId：active 指向已被移除的会话时不会清空（真实行为，见风险说明）', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = 1
      domain.handleConversationAccessRevoked({ teamId: 10 })
      // teamId 分支先过滤掉该团队会话，再 find 已找不到该会话，
      // 因此 activeConversationId 会残留指向已被移除的会话（疑似设计缺陷）
      expect(state.activeConversationId.value).toBe(1)
    })

    it('conversationId 与 teamId 同时提供时两条路径都执行', () => {
      const { state, deps, messageDomain, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = 1
      domain.handleConversationAccessRevoked({ conversationId: 1, teamId: 10 })
      expect(messageDomain.removeConversationBuckets).toHaveBeenCalledWith(1)
      expect(deps.handleTeamAccessRevoked).toHaveBeenCalledWith(10)
      expect(messageDomain.removeTeamBuckets).toHaveBeenCalled()
      // conversationId 路径已把 activeConversationId 清空
      expect(state.activeConversationId.value).toBeNull()
      // teamId 路径再按团队过滤，最终只剩会话 2
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
    })
  })
})
