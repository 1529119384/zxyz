import { describe, it, expect, vi } from 'vitest'
import { ref, shallowRef } from 'vue'

import { createMessageDomain } from '@/store/im/messageDomain'
import { STORED } from '@/constants/messageStatus'

// messageDomain 顶层会 import @/api/im，统一 mock 掉避免真实网络调用
vi.mock('@/api/im', () => ({
  fetchConversationMessages: vi.fn(),
  searchConversationMessages: vi.fn(),
  resolveMessageFileCard: vi.fn(),
  recallMessage: vi.fn(),
}))

function createDomain() {
  const state = {
    messagesByConversation: shallowRef({}),
    activeConversationId: ref(null),
    chatViewActive: ref(false),
    windowFocused: ref(true),
    searchResults: ref([]),
    lastWsError: ref(null),
  }
  const deps = {
    getConversations: vi.fn(() => []),
    isConversationEffectivelyVisible: vi.fn(() => false),
    scheduleReadSync: vi.fn(),
  }
  const domain = createMessageDomain(state, deps)
  return { state, deps, domain }
}

describe('messageDomain', () => {
  describe('mergeConversationMessage', () => {
    it('无 conversationId 时直接返回 null，不触碰桶', () => {
      const { state, domain } = createDomain()
      expect(domain.mergeConversationMessage(null, { content: 'x' })).toBeNull()
      expect(domain.mergeConversationMessage(undefined, { content: 'x' })).toBeNull()
      expect(state.messagesByConversation.value).toEqual({})
    })

    it('按 messageId 命中已有消息时覆盖合并', () => {
      const { state, domain } = createDomain()
      domain.mergeConversationMessage(1, { messageId: 10, content: 'old', status: STORED })
      const merged = domain.mergeConversationMessage(1, {
        messageId: 10,
        content: 'new',
        senderName: '张三',
      })
      expect(merged.content).toBe('new')
      expect(merged.senderName).toBe('张三')
      expect(state.messagesByConversation.value[1]).toHaveLength(1)
      // 未提供的字段保留旧值
      expect(state.messagesByConversation.value[1][0].status).toBe(STORED)
    })

    it('按 clientMessageId 命中去重', () => {
      const { state, domain } = createDomain()
      // 第一条：只有 clientMessageId、没有 messageId（发送中状态）
      domain.mergeConversationMessage('c1', {
        clientMessageId: 'client-abc',
        content: 'v1',
        status: 'SENDING',
      })
      const merged = domain.mergeConversationMessage('c1', {
        clientMessageId: 'client-abc',
        content: 'v2',
        status: STORED,
      })
      expect(merged.clientMessageId).toBe('client-abc')
      expect(merged.content).toBe('v2')
      expect(state.messagesByConversation.value.c1).toHaveLength(1)
    })

    it('全新的消息直接 push 进桶', () => {
      const { state, domain } = createDomain()
      domain.mergeConversationMessage('c1', { messageId: 1, content: 'a' })
      domain.mergeConversationMessage('c1', { messageId: 2, content: 'b' })
      expect(state.messagesByConversation.value.c1).toHaveLength(2)
      expect(state.messagesByConversation.value.c1.map((m) => m.messageId)).toEqual([1, 2])
    })

    it('FILE_CARD 新消息无 fileCard 时保留旧 fileCard', () => {
      const { state, domain } = createDomain()
      const oldCard = { fileId: 100, originalName: 'a.pdf' }
      domain.mergeConversationMessage('c1', {
        messageId: 5,
        messageType: 'FILE_CARD',
        fileCard: oldCard,
      })
      const merged = domain.mergeConversationMessage('c1', {
        messageId: 5,
        messageType: 'FILE_CARD',
        content: '',
      })
      expect(merged.fileCard).toEqual(oldCard)
      expect(state.messagesByConversation.value.c1[0].fileCard).toEqual(oldCard)
    })

    it('非 FILE_CARD 消息的 fileCard 允许被覆盖为 null', () => {
      const { state, domain } = createDomain()
      domain.mergeConversationMessage('c1', {
        messageId: 5,
        messageType: 'FILE_CARD',
        fileCard: { fileId: 1 },
      })
      const merged = domain.mergeConversationMessage('c1', {
        messageId: 5,
        messageType: 'TEXT',
        content: '回退成文本',
      })
      expect(merged.fileCard).toBeNull()
    })

    it('合并后按 compareMessages（createTime 升序）排序', () => {
      const { state, domain } = createDomain()
      domain.mergeConversationMessage('c1', {
        messageId: 1,
        createTime: '2026-01-02T00:00:00Z',
        content: 'later',
      })
      domain.mergeConversationMessage('c1', {
        messageId: 2,
        createTime: '2026-01-01T00:00:00Z',
        content: 'earlier',
      })
      expect(state.messagesByConversation.value.c1.map((m) => m.content)).toEqual([
        'earlier',
        'later',
      ])
    })
  })

  describe('replaceConversationMessages', () => {
    it('非数组输入时写入空桶', () => {
      const { state, domain } = createDomain()
      const result = domain.replaceConversationMessages('c1', 'not-an-array')
      expect(result).toEqual([])
      expect(state.messagesByConversation.value.c1).toEqual([])
    })

    it('注入 conversationId 与 status: STORED', () => {
      const { state, domain } = createDomain()
      domain.replaceConversationMessages('c1', [
        { messageId: 1, content: 'a', status: 'SENDING' },
        { messageId: 2, content: 'b' },
      ])
      const bucket = state.messagesByConversation.value.c1
      expect(bucket).toHaveLength(2)
      bucket.forEach((m) => {
        expect(m.conversationId).toBe('c1')
        expect(m.status).toBe(STORED)
      })
    })

    it('替换后按 compareMessages 排序', () => {
      const { state, domain } = createDomain()
      const result = domain.replaceConversationMessages('c1', [
        { messageId: 1, createTime: '2026-01-02T00:00:00Z' },
        { messageId: 2, createTime: '2026-01-01T00:00:00Z' },
        { messageId: 3, createTime: '2026-01-03T00:00:00Z' },
      ])
      expect(result.map((m) => m.messageId)).toEqual([2, 1, 3])
    })

    it('直接整体覆盖旧桶', () => {
      const { state, domain } = createDomain()
      domain.mergeConversationMessage('c1', { messageId: 1, content: 'old' })
      domain.replaceConversationMessages('c1', [{ messageId: 9, content: 'fresh' }])
      expect(state.messagesByConversation.value.c1).toHaveLength(1)
      expect(state.messagesByConversation.value.c1[0].messageId).toBe(9)
    })
  })

  describe('ensureMessageBucket', () => {
    it('无 conversationId 时返回空数组且不建桶', () => {
      const { state, domain } = createDomain()
      expect(domain.ensureMessageBucket(null)).toEqual([])
      expect(state.messagesByConversation.value).toEqual({})
    })

    it('桶不存在时创建空桶并返回', () => {
      const { state, domain } = createDomain()
      const bucket = domain.ensureMessageBucket('c1')
      expect(bucket).toEqual([])
      expect(state.messagesByConversation.value.c1).toEqual([])
    })

    it('桶已存在时直接返回原引用', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('c1').push({ messageId: 1 })
      const again = domain.ensureMessageBucket('c1')
      expect(again).toHaveLength(1)
    })
  })

  describe('getConversationMessages', () => {
    it('无 conversationId 时返回空数组', () => {
      const { state, domain } = createDomain()
      expect(domain.getConversationMessages(null)).toEqual([])
      expect(domain.getConversationMessages(undefined)).toEqual([])
    })

    it('返回对应桶（缺失时返回空数组）', () => {
      const { state, domain } = createDomain()
      expect(domain.getConversationMessages('c1')).toEqual([])
      domain.ensureMessageBucket('c1').push({ messageId: 1 })
      expect(domain.getConversationMessages('c1')).toHaveLength(1)
    })
  })

  describe('updatePendingMessageStatus', () => {
    it('桶内找不到 clientMessageId 时返回 null', () => {
      const { state, domain } = createDomain()
      expect(domain.updatePendingMessageStatus('c1', 'no-such', { status: STORED })).toBeNull()
    })

    it('命中后合并 patch、排序并返回更新后的消息', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('c1').push({
        clientMessageId: 'cli-1',
        content: 'old',
        createTime: '2026-01-01T00:00:00Z',
      })
      domain.ensureMessageBucket('c1').push({
        clientMessageId: 'cli-2',
        content: 'b',
        createTime: '2026-01-02T00:00:00Z',
      })
      const updated = domain.updatePendingMessageStatus('c1', 'cli-1', { status: STORED, content: 'new' })
      expect(updated.status).toBe(STORED)
      expect(updated.content).toBe('new')
      expect(updated.clientMessageId).toBe('cli-1')
    })
  })

  describe('markBucketChanged', () => {
    it('调用后不抛错且桶数据保持不变', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('c1').push({ messageId: 1 })
      expect(() => domain.markBucketChanged()).not.toThrow()
      expect(state.messagesByConversation.value.c1).toHaveLength(1)
    })
  })

  describe('removeConversationBuckets', () => {
    it('无 conversationId 时为空操作', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('c1').push({ messageId: 1 })
      domain.removeConversationBuckets(null)
      expect(state.messagesByConversation.value.c1).toHaveLength(1)
    })

    it('删除指定桶并从 recentAccess 清理', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('c1').push({ messageId: 1 })
      domain.ensureMessageBucket('c2').push({ messageId: 2 })
      domain.removeConversationBuckets('c1')
      expect(state.messagesByConversation.value.c1).toBeUndefined()
      expect(state.messagesByConversation.value.c2).toHaveLength(1)
    })
  })

  describe('removeTeamBuckets', () => {
    it('仅保留 allowedConversationIds 集合内的桶', () => {
      const { state, domain } = createDomain()
      domain.ensureMessageBucket('1').push({ messageId: 1 })
      domain.ensureMessageBucket('2').push({ messageId: 2 })
      domain.ensureMessageBucket('3').push({ messageId: 3 })
      domain.removeTeamBuckets(new Set([1, 2]))
      expect(state.messagesByConversation.value['1']).toHaveLength(1)
      expect(state.messagesByConversation.value['2']).toHaveLength(1)
      expect(state.messagesByConversation.value['3']).toBeUndefined()
    })
  })

  describe('pruneOrphanBuckets', () => {
    it('删除不在 getConversations 返回列表中的孤儿桶', () => {
      const { state, deps, domain } = createDomain()
      deps.getConversations.mockReturnValue([{ id: 5 }, { id: 6 }])
      domain.ensureMessageBucket('5').push({ messageId: 1 })
      domain.ensureMessageBucket('6').push({ messageId: 2 })
      domain.ensureMessageBucket('7').push({ messageId: 3 })
      domain.pruneOrphanBuckets()
      expect(state.messagesByConversation.value['5']).toHaveLength(1)
      expect(state.messagesByConversation.value['6']).toHaveLength(1)
      expect(state.messagesByConversation.value['7']).toBeUndefined()
    })

    it('无孤儿桶时保持原状', () => {
      const { state, deps, domain } = createDomain()
      deps.getConversations.mockReturnValue([{ id: 1 }])
      domain.ensureMessageBucket('1').push({ messageId: 1 })
      domain.pruneOrphanBuckets()
      expect(state.messagesByConversation.value['1']).toHaveLength(1)
    })
  })

  describe('evictStaleBuckets', () => {
    function seedBuckets(state, ids) {
      const next = { ...state.messagesByConversation.value }
      ids.forEach((id) => {
        next[id] = state.messagesByConversation.value[id] || []
      })
      state.messagesByConversation.value = next
    }

    it('桶数量未超过上限时直接返回不驱逐', () => {
      const { state, domain } = createDomain()
      seedBuckets(state, ['1', '2', '3', '4', '5'])
      domain.evictStaleBuckets()
      expect(Object.keys(state.messagesByConversation.value).sort()).toEqual(
        ['1', '2', '3', '4', '5']
      )
    })

    it('超过上限时驱逐访问时间最旧的一个桶', () => {
      const { state, domain } = createDomain()
      // 21 个桶，recentAccess 为空（全 0），按 key 顺序驱逐第一个
      const ids = Array.from({ length: 21 }, (_, i) => String(i + 1))
      seedBuckets(state, ids)
      domain.evictStaleBuckets()
      const remaining = Object.keys(state.messagesByConversation.value).map(Number).sort((a, b) => a - b)
      expect(remaining).toHaveLength(20)
      expect(remaining).not.toContain(1)
    })

    it('含 SENDING 消息的桶不会被驱逐', () => {
      const { state, domain } = createDomain()
      const ids = Array.from({ length: 21 }, (_, i) => String(i + 1))
      seedBuckets(state, ids)
      // 给 2 号桶塞一条发送中的消息
      state.messagesByConversation.value['2'] = [{ status: 'SENDING', messageId: 99 }]
      domain.evictStaleBuckets()
      expect(state.messagesByConversation.value['2']).toBeDefined()
      expect(state.messagesByConversation.value['2'][0].status).toBe('SENDING')
    })

    it('当前激活会话的桶不会被驱逐', () => {
      const { state, domain } = createDomain()
      const ids = Array.from({ length: 21 }, (_, i) => String(i + 1))
      seedBuckets(state, ids)
      state.activeConversationId.value = '3'
      domain.evictStaleBuckets()
      expect(state.messagesByConversation.value['3']).toBeDefined()
    })
  })

  describe('loadConversationMessages', () => {
    it('无游标时整体替换并（可见则）触发已读同步', async () => {
      const { deps, domain } = createDomain()
      const { fetchConversationMessages } = await import('@/api/im')
      fetchConversationMessages.mockResolvedValue({
        data: [
          { messageId: 1, createTime: '2026-01-02T00:00:00Z' },
          { messageId: 2, createTime: '2026-01-01T00:00:00Z' },
        ],
      })
      deps.isConversationEffectivelyVisible.mockReturnValue(true)
      const result = await domain.loadConversationMessages('c1', {})
      expect(result.map((m) => m.messageId)).toEqual([2, 1])
      expect(deps.scheduleReadSync).toHaveBeenCalledWith('c1')
    })

    it('无游标且响应 data 非数组时写入空桶', async () => {
      const { state, deps, domain } = createDomain()
      const { fetchConversationMessages } = await import('@/api/im')
      fetchConversationMessages.mockResolvedValue({ data: undefined })
      deps.isConversationEffectivelyVisible.mockReturnValue(false)
      const result = await domain.loadConversationMessages('c1', {})
      expect(result).toEqual([])
      expect(state.messagesByConversation.value.c1).toEqual([])
      expect(deps.scheduleReadSync).not.toHaveBeenCalled()
    })

    it('带 afterMessageId 游标时逐条合并并返回桶内容', async () => {
      const { state, domain } = createDomain()
      const { fetchConversationMessages } = await import('@/api/im')
      fetchConversationMessages.mockResolvedValue({
        data: [{ messageId: 7, createTime: '2026-01-03T00:00:00Z' }],
      })
      const result = await domain.loadConversationMessages('c1', { afterMessageId: 5 })
      expect(result).toHaveLength(1)
      expect(result[0].messageId).toBe(7)
      expect(state.messagesByConversation.value.c1).toHaveLength(1)
    })

    it('带 beforeMessageId 游标时走合并分支', async () => {
      const { state, domain } = createDomain()
      const { fetchConversationMessages } = await import('@/api/im')
      fetchConversationMessages.mockResolvedValue({
        data: [{ messageId: 3, createTime: '2026-01-01T00:00:00Z' }],
      })
      const result = await domain.loadConversationMessages('c1', { beforeMessageId: 4 })
      expect(result).toHaveLength(1)
    })
  })

  describe('syncConversationMessages', () => {
    it('无 conversationId 时返回空数组', async () => {
      const { domain } = createDomain()
      expect(await domain.syncConversationMessages(null)).toEqual([])
    })

    it('默认使用 activeConversationId 并带游标增量拉取', async () => {
      const { state, domain } = createDomain()
      state.activeConversationId.value = 'c1'
      domain.ensureMessageBucket('c1').push({
        messageId: 10,
        createTime: '2026-01-05T00:00:00Z',
      })
      const { fetchConversationMessages } = await import('@/api/im')
      fetchConversationMessages.mockResolvedValue({
        data: [{ messageId: 11, createTime: '2026-01-06T00:00:00Z' }],
      })
      const result = await domain.syncConversationMessages()
      expect(fetchConversationMessages).toHaveBeenCalledWith('c1', {
        afterMessageId: 10,
        afterTime: '2026-01-05T00:00:00Z',
        limit: 100,
      })
      expect(result).toHaveLength(2)
    })
  })

  describe('searchMessages', () => {
    it('把响应 data 归一化后写入 searchResults 并返回', async () => {
      const { state, domain } = createDomain()
      const { searchConversationMessages } = await import('@/api/im')
      searchConversationMessages.mockResolvedValue({
        data: [{ messageId: 1, content: 'hit' }],
      })
      const result = await domain.searchMessages('c1', 'hit')
      expect(result).toHaveLength(1)
      expect(result[0].messageId).toBe(1)
      expect(state.searchResults.value).toHaveLength(1)
    })

    it('响应 data 非数组时清零 searchResults', async () => {
      const { state, domain } = createDomain()
      const { searchConversationMessages } = await import('@/api/im')
      searchConversationMessages.mockResolvedValue({ data: undefined })
      const result = await domain.searchMessages('c1', 'x')
      expect(result).toEqual([])
      expect(state.searchResults.value).toEqual([])
    })
  })

  describe('resolveFileCardMessage', () => {
    it('返回响应 data，缺失时返回 null', async () => {
      const { domain } = createDomain()
      const { resolveMessageFileCard } = await import('@/api/im')
      resolveMessageFileCard.mockResolvedValue({ data: { fileId: 42 } })
      expect(await domain.resolveFileCardMessage(42)).toEqual({ fileId: 42 })
      resolveMessageFileCard.mockResolvedValue({})
      expect(await domain.resolveFileCardMessage(42)).toBeNull()
    })
  })

  describe('recallConversationMessage', () => {
    it('返回响应 data', async () => {
      const { domain } = createDomain()
      const { recallMessage } = await import('@/api/im')
      recallMessage.mockResolvedValue({ data: { recalled: true } })
      const result = await domain.recallConversationMessage(9, { reason: 'spam' })
      expect(result).toEqual({ recalled: true })
      expect(recallMessage).toHaveBeenCalledWith(9, { reason: 'spam' })
    })
  })

  describe('cleanup', () => {
    it('清空 recentAccess 且不抛错', () => {
      const { domain } = createDomain()
      expect(() => domain.cleanup()).not.toThrow()
    })
  })
})
