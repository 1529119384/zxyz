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
})
