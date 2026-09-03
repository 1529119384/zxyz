import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

import { createConversationDomain } from '@/store/im/conversationDomain'
import { READ_SYNC_DELAY_MS } from '@/store/im/normalizers'
import {
  createDirectConversation,
  fetchConversation,
  fetchMyConversations,
  fetchTeamConversation,
  updateConversationRead,
} from '@/api/im'

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

    it('仅 teamId：active 指向该团队被移除的会话时清空（防止停留在空白聊天窗）', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = 1
      domain.handleConversationAccessRevoked({ teamId: 10 })
      // 修复前：filter 先移除团队会话，再 find 已找不到，activeConversationId 残留
      // 指向已被移除的会话。修复后改为过滤前判定，故会被正确清空。
      expect(state.activeConversationId.value).toBeNull()
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
    })

    it('仅 teamId：active 指向其他团队的会话时保持不变', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = 2
      domain.handleConversationAccessRevoked({ teamId: 10 })
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
      expect(state.activeConversationId.value).toBe(2)
    })

    it('仅 teamId：id 数字/字符串类型不一致时仍能正确匹配并清空', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      // activeConversationId 存字符串，会话 id 为数字（IM 路由参数常见）
      state.activeConversationId.value = '1'
      domain.handleConversationAccessRevoked({ teamId: 10 })
      // 修复前用严格相等 ===，字符串 '1' 匹配不到数字 1，导致清空失效
      expect(state.activeConversationId.value).toBeNull()
    })

    it('仅 teamId：active 为空时不清空且不影响过滤结果', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [
        { id: 1, teamId: 10 },
        { id: 2, teamId: 20 },
      ]
      state.activeConversationId.value = null
      domain.handleConversationAccessRevoked({ teamId: 10 })
      expect(state.activeConversationId.value).toBeNull()
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
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

    it('不传参数时两条路径都不执行', () => {
      const { state, deps, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 1, teamId: 10 }]
      domain.handleConversationAccessRevoked()
      expect(state.conversations.value.map((c) => c.id)).toEqual([1])
      expect(deps.handleTeamAccessRevoked).not.toHaveBeenCalled()
      expect(messageDomain.removeConversationBuckets).not.toHaveBeenCalled()
      expect(messageDomain.removeTeamBuckets).not.toHaveBeenCalled()
    })

    it('conversationId 为假值时跳过本地移除', () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 1, teamId: 10 }]
      domain.handleConversationAccessRevoked({ conversationId: 0 })
      expect(state.conversations.value).toHaveLength(1)
      expect(messageDomain.removeConversationBuckets).not.toHaveBeenCalled()
    })

    it('conversationId 与会话 id 按 Number 比较，字符串也能命中', () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 1, teamId: 10 }, { id: 2, teamId: 20 }]
      state.activeConversationId.value = 2
      domain.handleConversationAccessRevoked({ conversationId: '1' })
      expect(state.conversations.value.map((c) => c.id)).toEqual([2])
      expect(messageDomain.removeConversationBuckets).toHaveBeenCalledWith('1')
      // 被移除的不是当前会话，activeConversationId 保留
      expect(state.activeConversationId.value).toBe(2)
    })
  })

  describe('会话可见性与视图状态', () => {
    it('isConversationEffectivelyVisible 需同时满足聊天视图激活、窗口聚焦、会话匹配', () => {
      const { state, domain } = createDomain()
      state.activeConversationId.value = 5
      expect(domain.isConversationEffectivelyVisible(5)).toBe(false)
      state.chatViewActive.value = true
      expect(domain.isConversationEffectivelyVisible(5)).toBe(true)
      expect(domain.isConversationEffectivelyVisible('5')).toBe(true)
      expect(domain.isConversationEffectivelyVisible(6)).toBe(false)
      state.windowFocused.value = false
      expect(domain.isConversationEffectivelyVisible(5)).toBe(false)
    })

    it('setChatViewActive 与 setWindowFocused 会强制布尔化', () => {
      const { state, domain } = createDomain()
      domain.setChatViewActive(1)
      domain.setWindowFocused('yes')
      expect(state.chatViewActive.value).toBe(true)
      expect(state.windowFocused.value).toBe(true)
      domain.setChatViewActive(0)
      domain.setWindowFocused(undefined)
      expect(state.chatViewActive.value).toBe(false)
      expect(state.windowFocused.value).toBe(false)
    })

    it('clearActiveConversation 会把 activeConversationId 置为 null', () => {
      const { state, domain } = createDomain()
      state.activeConversationId.value = 12
      domain.clearActiveConversation()
      expect(state.activeConversationId.value).toBeNull()
    })
  })

  describe('touchConversation', () => {
    it('未命中会话时静默返回', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, updateTime: null }]
      expect(domain.touchConversation(999)).toBeUndefined()
      expect(state.conversations.value[0].updateTime).toBeNull()
    })

    it('命中会话时把 updateTime 刷新为当前 ISO 时间并保留其它字段', () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 1, name: 'a', updateTime: '2020-01-01T00:00:00.000Z' }]
      domain.touchConversation(1)
      const touched = state.conversations.value[0]
      expect(state.conversations.value).toHaveLength(1)
      expect(touched.name).toBe('a')
      expect(touched.updateTime).not.toBe('2020-01-01T00:00:00.000Z')
      expect(new Date(touched.updateTime).toISOString()).toBe(touched.updateTime)
    })
  })

  describe('isConversationAccessError', () => {
    it('IM 访问拒绝码（4030/4400/4401）返回 true', () => {
      const { domain } = createDomain()
      expect(domain.isConversationAccessError({ response: { data: { code: 4030 } } })).toBe(true)
      expect(domain.isConversationAccessError({ response: { data: { code: 4400 } } })).toBe(true)
      expect(domain.isConversationAccessError({ response: { data: { code: '4401' } } })).toBe(true)
    })

    it('其它错误码或缺少 response 时返回 false', () => {
      const { domain } = createDomain()
      expect(domain.isConversationAccessError({ response: { data: { code: 5001 } } })).toBe(false)
      expect(domain.isConversationAccessError({ response: {} })).toBe(false)
      expect(domain.isConversationAccessError(new Error('boom'))).toBe(false)
      expect(domain.isConversationAccessError(null)).toBe(false)
      expect(domain.isConversationAccessError(undefined)).toBe(false)
    })
  })

  describe('messageDomain 委托方法', () => {
    it('桶读写类方法直接复用 messageDomain 实现', () => {
      const { messageDomain, domain } = createDomain()
      expect(domain.ensureMessageBucket).toBe(messageDomain.ensureMessageBucket)
      expect(domain.getConversationMessages).toBe(messageDomain.getConversationMessages)
      expect(domain.loadConversationMessages).toBe(messageDomain.loadConversationMessages)
      messageDomain.getConversationMessages.mockReturnValue([{ messageId: 1 }])
      expect(domain.getConversationMessages(7)).toEqual([{ messageId: 1 }])
      expect(messageDomain.getConversationMessages).toHaveBeenCalledWith(7)
    })

    it('消息操作类方法原样转发参数并返回 messageDomain 的结果', () => {
      const { messageDomain, domain } = createDomain()
      messageDomain.mergeConversationMessage.mockReturnValue('merged')
      expect(domain.mergeConversationMessage(1, { a: 1 })).toBe('merged')
      expect(messageDomain.mergeConversationMessage).toHaveBeenCalledWith(1, { a: 1 })

      messageDomain.replaceConversationMessages.mockReturnValue(['r'])
      expect(domain.replaceConversationMessages(2, [1, 2])).toEqual(['r'])
      expect(messageDomain.replaceConversationMessages).toHaveBeenCalledWith(2, [1, 2])

      domain.updatePendingMessageStatus(3, 'cid', 'SENT')
      expect(messageDomain.updatePendingMessageStatus).toHaveBeenCalledWith(3, 'cid', 'SENT')

      domain.syncConversationMessages(4)
      expect(messageDomain.syncConversationMessages).toHaveBeenCalledWith(4)

      messageDomain.searchMessages.mockReturnValue(['hit'])
      expect(domain.searchMessages('kw', 5)).toEqual(['hit'])
      expect(messageDomain.searchMessages).toHaveBeenCalledWith('kw', 5)

      messageDomain.resolveFileCardMessage.mockReturnValue('card')
      expect(domain.resolveFileCardMessage(6, 7)).toBe('card')
      expect(messageDomain.resolveFileCardMessage).toHaveBeenCalledWith(6, 7)

      domain.recallConversationMessage(8, 9)
      expect(messageDomain.recallConversationMessage).toHaveBeenCalledWith(8, 9)

      domain.markBucketChanged(10)
      expect(messageDomain.markBucketChanged).toHaveBeenCalledWith(10)
    })

    it('loadConversationMessages 透传 messageDomain 的 Promise', async () => {
      const { messageDomain, domain } = createDomain()
      messageDomain.loadConversationMessages.mockResolvedValue([{ messageId: 1 }])
      await expect(domain.loadConversationMessages(11)).resolves.toEqual([{ messageId: 1 }])
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(11)
    })
  })

  describe('loadConversations', () => {
    it('默认取 selectedTeamId，按接口数据整体替换会话列表并清理孤儿桶', async () => {
      const { state, deps, messageDomain, domain } = createDomain()
      state.selectedTeamId.value = 3
      state.conversations.value = [{ id: 99 }]
      vi.mocked(fetchMyConversations).mockResolvedValue({
        data: [
          { id: 1, name: 'a', teamId: 3, unreadCount: 2 },
          { conversationId: 2, teamName: 'b' },
        ],
      })
      const result = await domain.loadConversations()
      expect(deps.resolveTeamScopedParams).toHaveBeenCalledWith(3)
      expect(fetchMyConversations).toHaveBeenCalledWith({ teamId: 3 })
      expect(result).toBe(state.conversations.value)
      expect(state.conversations.value.map((c) => c.id)).toEqual([1, 2])
      expect(state.conversations.value[0].unreadCount).toBe(2)
      expect(state.conversations.value[1].name).toBe('b')
      expect(state.conversations.value[1].type).toBe('TEAM')
      expect(messageDomain.pruneOrphanBuckets).toHaveBeenCalledTimes(1)
    })

    it('显式传 teamId 时覆盖 selectedTeamId', async () => {
      const { state, deps, domain } = createDomain()
      state.selectedTeamId.value = 3
      vi.mocked(fetchMyConversations).mockResolvedValue({ data: [] })
      await domain.loadConversations(8)
      expect(deps.resolveTeamScopedParams).toHaveBeenCalledWith(8)
      expect(fetchMyConversations).toHaveBeenCalledWith({ teamId: 8 })
    })

    it('接口返回 null 或 data 非数组时清空列表', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 1 }]
      vi.mocked(fetchMyConversations).mockResolvedValue(null)
      expect(await domain.loadConversations()).toEqual([])
      expect(state.conversations.value).toEqual([])

      state.conversations.value = [{ id: 1 }]
      vi.mocked(fetchMyConversations).mockResolvedValue({ data: 'not-array' })
      expect(await domain.loadConversations()).toEqual([])
      expect(messageDomain.pruneOrphanBuckets).toHaveBeenCalledTimes(2)
    })
  })

  describe('ensureConversation', () => {
    it('本地已存在时直接返回，不请求接口', async () => {
      const { state, domain } = createDomain()
      const existing = { id: 5, name: 'a' }
      state.conversations.value = [existing]
      // conversations 是 ref，取出的元素是响应式代理，用深度相等比较
      expect(await domain.ensureConversation(5)).toEqual(existing)
      expect(fetchConversation).not.toHaveBeenCalled()
    })

    it('本地不存在时拉取会话详情并 upsert', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchConversation).mockResolvedValue({ data: { id: 7, name: 'remote' } })
      const conversation = await domain.ensureConversation(7)
      expect(fetchConversation).toHaveBeenCalledWith(7)
      expect(conversation.name).toBe('remote')
      expect(state.conversations.value.map((c) => c.id)).toEqual([7])
    })

    it('接口无 data 时返回 null 且不写入列表', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchConversation).mockResolvedValue({})
      expect(await domain.ensureConversation(7)).toBeNull()
      vi.mocked(fetchConversation).mockResolvedValue(null)
      expect(await domain.ensureConversation(8)).toBeNull()
      expect(state.conversations.value).toEqual([])
    })
  })

  describe('loadTeamConversation', () => {
    it('teamId 非法时抛「请先选择团队」且不请求接口', async () => {
      const { domain } = createDomain()
      await expect(domain.loadTeamConversation(null)).rejects.toThrow('请先选择团队')
      await expect(domain.loadTeamConversation(0)).rejects.toThrow('请先选择团队')
      await expect(domain.loadTeamConversation('abc')).rejects.toThrow('请先选择团队')
      expect(fetchTeamConversation).not.toHaveBeenCalled()
    })

    it('按团队会话响应映射字段并 upsert', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamConversation).mockResolvedValue({
        data: {
          conversationId: 8,
          type: 'TEAM',
          teamId: 3,
          teamName: 'T',
          teamAvatar: 'avatar.png',
        },
      })
      const conversation = await domain.loadTeamConversation('3')
      expect(fetchTeamConversation).toHaveBeenCalledWith(3)
      expect(conversation).toMatchObject({
        id: 8,
        type: 'TEAM',
        teamId: 3,
        name: 'T',
        avatar: 'avatar.png',
        unreadCount: 0,
        updateTime: null,
      })
      expect(state.conversations.value.map((c) => c.id)).toEqual([8])
    })

    it('接口无 data 时抛 TypeError（normalizeConversation 不兜 null）', async () => {
      const { state, domain } = createDomain()
      vi.mocked(fetchTeamConversation).mockResolvedValue({})
      await expect(domain.loadTeamConversation(3)).rejects.toThrow(TypeError)
      expect(state.conversations.value).toEqual([])
    })
  })

  describe('createDirectConversationAndOpen', () => {
    it('teamId 非法时抛错且不请求接口', async () => {
      const { domain } = createDomain()
      await expect(domain.createDirectConversationAndOpen(undefined, 2)).rejects.toThrow(
        '请先选择团队',
      )
      expect(createDirectConversation).not.toHaveBeenCalled()
    })

    it('命中已有单聊时直接打开、清零未读并加载消息', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [
        { id: 11, type: 'DIRECT', teamId: 3, peerUserId: 7, unreadCount: 4 },
      ]
      const result = await domain.createDirectConversationAndOpen(3, '7')
      expect(createDirectConversation).not.toHaveBeenCalled()
      expect(result.id).toBe(11)
      expect(state.activeConversationId.value).toBe(11)
      expect(state.conversations.value[0].unreadCount).toBe(0)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(11)
    })

    it('命中已有单聊但加载消息失败时把错误写入 lastWsError', async () => {
      const { state, messageDomain, domain } = createDomain()
      const error = new Error('load failed')
      messageDomain.loadConversationMessages.mockRejectedValue(error)
      state.conversations.value = [
        { id: 11, type: 'DIRECT', teamId: 3, peerUserId: 7, unreadCount: 0 },
      ]
      const result = await domain.createDirectConversationAndOpen(3, 7)
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(result.id).toBe(11)
      expect(state.lastWsError.value).toBe(error)
    })

    it('无已有单聊时调用接口创建并打开新会话', async () => {
      const { state, messageDomain, domain } = createDomain()
      vi.mocked(createDirectConversation).mockResolvedValue({
        data: { id: 12, type: 'DIRECT', teamId: 3, peerUserId: 7, name: 'peer' },
      })
      const result = await domain.createDirectConversationAndOpen('3', '7')
      expect(createDirectConversation).toHaveBeenCalledWith({ teamId: 3, targetUserId: '7' })
      expect(result.id).toBe(12)
      expect(state.activeConversationId.value).toBe(12)
      expect(state.conversations.value.map((c) => c.id)).toEqual([12])
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(12)
    })

    it('新建单聊后加载消息失败时把错误写入 lastWsError', async () => {
      const { state, messageDomain, domain } = createDomain()
      const error = new Error('new load failed')
      vi.mocked(createDirectConversation).mockResolvedValue({
        data: { id: 12, type: 'DIRECT', teamId: 3, peerUserId: 7 },
      })
      messageDomain.loadConversationMessages.mockRejectedValue(error)
      await domain.createDirectConversationAndOpen(3, 7)
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(state.activeConversationId.value).toBe(12)
      expect(state.lastWsError.value).toBe(error)
    })

    it('接口无 data 时抛 TypeError 且不设置 activeConversationId', async () => {
      const { state, messageDomain, domain } = createDomain()
      vi.mocked(createDirectConversation).mockResolvedValue({})
      await expect(domain.createDirectConversationAndOpen(3, 7)).rejects.toThrow(TypeError)
      expect(state.activeConversationId.value).toBeNull()
      expect(messageDomain.loadConversationMessages).not.toHaveBeenCalled()
    })
  })

  describe('updateReadPosition', () => {
    it('conversationId 或 lastReadMessageId 为空时返回 null 且不请求接口', async () => {
      const { domain } = createDomain()
      expect(await domain.updateReadPosition(null, 5)).toBeNull()
      expect(await domain.updateReadPosition(5, 0)).toBeNull()
      expect(await domain.updateReadPosition(5, null)).toBeNull()
      expect(updateConversationRead).not.toHaveBeenCalled()
    })

    it('成功后返回 data 并把会话未读清零', async () => {
      const { state, domain } = createDomain()
      state.conversations.value = [{ id: 5, unreadCount: 9 }]
      vi.mocked(updateConversationRead).mockResolvedValue({ data: { lastReadMessageId: 100 } })
      const data = await domain.updateReadPosition(5, 100)
      expect(updateConversationRead).toHaveBeenCalledWith(5, { lastReadMessageId: 100 })
      expect(data).toEqual({ lastReadMessageId: 100 })
      expect(state.conversations.value[0].unreadCount).toBe(0)
    })

    it('接口无 data 时返回 null', async () => {
      const { domain } = createDomain()
      vi.mocked(updateConversationRead).mockResolvedValue({})
      expect(await domain.updateReadPosition(5, 100)).toBeNull()
    })
  })

  describe('scheduleReadSync', () => {
    function createVisibleDomain() {
      const context = createDomain()
      context.state.chatViewActive.value = true
      context.state.windowFocused.value = true
      context.state.activeConversationId.value = 5
      return context
    }

    it('conversationId 为空或会话不可见时直接返回', () => {
      const { messageDomain, domain } = createDomain()
      expect(domain.scheduleReadSync()).toBeUndefined()
      domain.scheduleReadSync(5)
      expect(messageDomain.getConversationMessages).not.toHaveBeenCalled()
    })

    it('可见但桶内没有带 messageId 的消息时直接返回', () => {
      const { messageDomain, domain } = createVisibleDomain()
      messageDomain.getConversationMessages.mockReturnValue([
        { id: 1, createTime: '2026-01-01T00:00:00.000Z' },
        { messageId: null, createTime: '2026-01-02T00:00:00.000Z' },
      ])
      domain.scheduleReadSync(5)
      expect(messageDomain.getConversationMessages).toHaveBeenCalledWith(5)
      expect(updateConversationRead).not.toHaveBeenCalled()
    })

    it('延迟 READ_SYNC_DELAY_MS 后按时间排序同步最后一条消息', async () => {
      const { messageDomain, domain } = createVisibleDomain()
      vi.useFakeTimers()
      try {
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 3, createTime: '2026-01-03T00:00:00.000Z' },
          { messageId: 1, createTime: '2026-01-01T00:00:00.000Z' },
          { messageId: 2, createTime: '2026-01-02T00:00:00.000Z' },
          { createTime: '2026-01-09T00:00:00.000Z' },
        ])
        vi.mocked(updateConversationRead).mockResolvedValue({ data: null })
        domain.scheduleReadSync(5)
        await vi.advanceTimersByTimeAsync(READ_SYNC_DELAY_MS - 1)
        expect(updateConversationRead).not.toHaveBeenCalled()
        await vi.advanceTimersByTimeAsync(1)
        expect(updateConversationRead).toHaveBeenCalledTimes(1)
        expect(updateConversationRead).toHaveBeenCalledWith(5, { lastReadMessageId: 3 })
      } finally {
        vi.useRealTimers()
      }
    })

    it('同一会话重复调度只保留最后一次（清除旧定时器）', async () => {
      const { messageDomain, domain } = createVisibleDomain()
      vi.useFakeTimers()
      try {
        vi.mocked(updateConversationRead).mockResolvedValue({ data: null })
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 10, createTime: '2026-01-01T00:00:00.000Z' },
        ])
        domain.scheduleReadSync(5)
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 20, createTime: '2026-01-02T00:00:00.000Z' },
        ])
        domain.scheduleReadSync(5)
        await vi.advanceTimersByTimeAsync(READ_SYNC_DELAY_MS)
        expect(updateConversationRead).toHaveBeenCalledTimes(1)
        expect(updateConversationRead).toHaveBeenCalledWith(5, { lastReadMessageId: 20 })
      } finally {
        vi.useRealTimers()
      }
    })

    it('已读同步失败时把错误写入 lastWsError', async () => {
      const { state, messageDomain, domain } = createVisibleDomain()
      const error = new Error('read sync failed')
      vi.useFakeTimers()
      try {
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 3, createTime: '2026-01-03T00:00:00.000Z' },
        ])
        vi.mocked(updateConversationRead).mockRejectedValue(error)
        domain.scheduleReadSync(5)
        await vi.advanceTimersByTimeAsync(READ_SYNC_DELAY_MS)
      } finally {
        vi.useRealTimers()
      }
      expect(state.lastWsError.value).toBe(error)
    })
  })

  describe('clearReadSyncTimers 与 cleanup', () => {
    it('clearReadSyncTimers 会取消尚未触发的已读同步', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.chatViewActive.value = true
      state.windowFocused.value = true
      state.activeConversationId.value = 5
      vi.useFakeTimers()
      try {
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 3, createTime: '2026-01-03T00:00:00.000Z' },
        ])
        vi.mocked(updateConversationRead).mockResolvedValue({ data: null })
        domain.scheduleReadSync(5)
        domain.clearReadSyncTimers()
        await vi.advanceTimersByTimeAsync(READ_SYNC_DELAY_MS)
        expect(updateConversationRead).not.toHaveBeenCalled()
      } finally {
        vi.useRealTimers()
      }
    })

    it('没有待清理的定时器时 clearReadSyncTimers 安全返回', () => {
      const { domain } = createDomain()
      expect(domain.clearReadSyncTimers()).toBeUndefined()
    })

    it('cleanup 会清理定时器并调用 messageDomain.cleanup', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.chatViewActive.value = true
      state.windowFocused.value = true
      state.activeConversationId.value = 5
      vi.useFakeTimers()
      try {
        messageDomain.getConversationMessages.mockReturnValue([
          { messageId: 3, createTime: '2026-01-03T00:00:00.000Z' },
        ])
        vi.mocked(updateConversationRead).mockResolvedValue({ data: null })
        domain.scheduleReadSync(5)
        domain.cleanup()
        await vi.advanceTimersByTimeAsync(READ_SYNC_DELAY_MS)
        expect(updateConversationRead).not.toHaveBeenCalled()
      } finally {
        vi.useRealTimers()
      }
      expect(messageDomain.cleanup).toHaveBeenCalledTimes(1)
    })
  })

  describe('openConversation / openTeamChat / openDirectChat', () => {
    it('openConversation 命中本地会话时不请求会话详情并加载消息', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 4, name: 'local' }]
      const conversation = await domain.openConversation(4)
      expect(fetchConversation).not.toHaveBeenCalled()
      expect(conversation.name).toBe('local')
      expect(state.activeConversationId.value).toBe(4)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(4)
      expect(messageDomain.evictStaleBuckets).toHaveBeenCalledTimes(1)
    })

    it('openConversation 从接口拉取会话后打开', async () => {
      const { state, messageDomain, domain } = createDomain()
      vi.mocked(fetchConversation).mockResolvedValue({ data: { id: 9, name: 'remote' } })
      const conversation = await domain.openConversation(9)
      expect(fetchConversation).toHaveBeenCalledWith(9)
      expect(conversation.id).toBe(9)
      expect(state.activeConversationId.value).toBe(9)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(9)
    })

    it('openConversation 会话不存在时 activeConversationId 置空且不加载消息', async () => {
      const { state, messageDomain, domain } = createDomain()
      vi.mocked(fetchConversation).mockResolvedValue({})
      expect(await domain.openConversation(9)).toBeNull()
      expect(state.activeConversationId.value).toBeNull()
      expect(messageDomain.loadConversationMessages).not.toHaveBeenCalled()
      expect(messageDomain.evictStaleBuckets).toHaveBeenCalledTimes(1)
    })

    it('openConversation 加载消息失败时把错误写入 lastWsError', async () => {
      const { state, messageDomain, domain } = createDomain()
      const error = new Error('open failed')
      messageDomain.loadConversationMessages.mockRejectedValue(error)
      state.conversations.value = [{ id: 4 }]
      await domain.openConversation(4)
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(state.lastWsError.value).toBe(error)
    })

    it('openTeamChat 先切换团队再打开团队会话', async () => {
      const { state, deps, messageDomain, domain } = createDomain()
      vi.mocked(fetchTeamConversation).mockResolvedValue({
        data: { conversationId: 8, type: 'TEAM', teamId: 3, teamName: 'T', teamAvatar: 'a.png' },
      })
      const conversation = await domain.openTeamChat('3')
      expect(deps.setSelectedTeam).toHaveBeenCalledWith(3)
      expect(fetchTeamConversation).toHaveBeenCalledWith(3)
      expect(conversation.id).toBe(8)
      expect(state.activeConversationId.value).toBe(8)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(8)
    })

    it('openTeamChat 团队会话无 id 时不打开会话', async () => {
      const { state, deps, messageDomain, domain } = createDomain()
      vi.mocked(fetchTeamConversation).mockResolvedValue({ data: { type: 'TEAM', teamId: 3 } })
      expect(await domain.openTeamChat(3)).toBeNull()
      expect(deps.setSelectedTeam).toHaveBeenCalledWith(3)
      expect(state.activeConversationId.value).toBeNull()
      expect(messageDomain.loadConversationMessages).not.toHaveBeenCalled()
    })

    it('openTeamChat teamId 非法时抛错且不切换团队', async () => {
      const { deps, domain } = createDomain()
      await expect(domain.openTeamChat(null)).rejects.toThrow('请先选择团队')
      expect(deps.setSelectedTeam).not.toHaveBeenCalled()
      expect(fetchTeamConversation).not.toHaveBeenCalled()
    })

    it('未注入 setSelectedTeam 时回退到更新 selectedTeamId 的默认实现', async () => {
      const { state, deps, messageDomain, domain: _unused } = createDomain()
      const domain = createConversationDomain(state, { ...deps, setSelectedTeam: undefined })
      vi.mocked(fetchTeamConversation).mockResolvedValue({
        data: { conversationId: 8, type: 'TEAM', teamId: 3 },
      })
      expect(await domain.openTeamChat('3')).toMatchObject({ id: 8 })
      expect(state.selectedTeamId.value).toBe(3)
      expect(state.activeConversationId.value).toBe(8)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(8)
    })

    it('openDirectChat 等价于 openConversation', async () => {
      const { state, messageDomain, domain } = createDomain()
      state.conversations.value = [{ id: 6, type: 'DIRECT' }]
      const conversation = await domain.openDirectChat(6)
      expect(conversation.id).toBe(6)
      expect(state.activeConversationId.value).toBe(6)
      expect(messageDomain.loadConversationMessages).toHaveBeenCalledWith(6)
    })
  })
})
