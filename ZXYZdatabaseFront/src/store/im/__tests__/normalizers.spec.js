import { describe, it, expect } from 'vitest'
import {
  requireTeamId,
  normalizeConversation,
  normalizeTeam,
  normalizeTeamMember,
  normalizeMessage,
  compareMessages,
  READ_SYNC_DELAY_MS,
  IM_ACCESS_DENIED_CODES,
  DEFAULT_TEAM_ID_KEY,
} from '@/store/im/normalizers'

describe('normalizers', () => {
  describe('constants', () => {
    it('should export READ_SYNC_DELAY_MS', () => {
      expect(READ_SYNC_DELAY_MS).toBe(300)
    })

    it('should export IM_ACCESS_DENIED_CODES', () => {
      expect(IM_ACCESS_DENIED_CODES.has(4030)).toBe(true)
      expect(IM_ACCESS_DENIED_CODES.has(4000)).toBe(false)
    })

    it('should export DEFAULT_TEAM_ID_KEY', () => {
      expect(DEFAULT_TEAM_ID_KEY).toBe('defaultTeamId')
    })
  })

  describe('requireTeamId', () => {
    it('should return teamId for valid positive number', () => {
      expect(requireTeamId(42)).toBe(42)
      expect(requireTeamId('42')).toBe(42)
    })

    it('should throw for null/undefined/0', () => {
      expect(() => requireTeamId(null)).toThrow('请先选择团队')
      expect(() => requireTeamId(undefined)).toThrow('请先选择团队')
      expect(() => requireTeamId(0)).toThrow('请先选择团队')
    })
  })

  describe('normalizeConversation', () => {
    it('should normalize with defaults', () => {
      const result = normalizeConversation({})
      expect(result.id).toBeNull()
      expect(result.type).toBeDefined()
      expect(result.name).toBe('')
      expect(result.unreadCount).toBe(0)
    })

    it('should map conversationId to id', () => {
      const result = normalizeConversation({ conversationId: 99 })
      expect(result.id).toBe(99)
    })

    it('should map teamName fallback to name', () => {
      const result = normalizeConversation({ teamName: 'My Team' })
      expect(result.name).toBe('My Team')
    })
  })

  describe('normalizeTeam', () => {
    it('should normalize with defaults', () => {
      const result = normalizeTeam({})
      expect(result.id).toBeNull()
      expect(result.name).toBe('')
      expect(result.myPermissions).toEqual([])
    })

    it('should map myRole fallback to myRoleCode', () => {
      const result = normalizeTeam({ myRole: 'admin' })
      expect(result.myRoleCode).toBe('admin')
    })
  })

  describe('normalizeTeamMember', () => {
    it('should normalize with defaults', () => {
      const result = normalizeTeamMember({})
      expect(result.userId).toBeNull()
      expect(result.username).toBe('')
    })

    it('should map role fallback to roleCode', () => {
      const result = normalizeTeamMember({ role: 'member' })
      expect(result.roleCode).toBe('member')
    })
  })

  describe('normalizeMessage', () => {
    it('should normalize with defaults', () => {
      const result = normalizeMessage({})
      expect(result.messageId).toBeNull()
      expect(result.messageType).toBe('TEXT')
      expect(result.mentions).toEqual([])
      expect(result.createTime).toBeNull()
    })

    it('should map id to messageId', () => {
      const result = normalizeMessage({ id: 123 })
      expect(result.messageId).toBe(123)
    })

    it('should apply overrides', () => {
      const result = normalizeMessage({ content: 'hello' }, { content: 'override' })
      expect(result.content).toBe('override')
    })
  })

  describe('compareMessages', () => {
    it('should sort by createTime ascending', () => {
      const a = { createTime: '2026-01-01T00:00:00Z', messageId: 1 }
      const b = { createTime: '2026-01-02T00:00:00Z', messageId: 2 }
      expect(compareMessages(a, b)).toBeLessThan(0)
      expect(compareMessages(b, a)).toBeGreaterThan(0)
    })

    it('should fallback to messageId when createTime equal', () => {
      const a = { createTime: '2026-01-01T00:00:00Z', messageId: 1 }
      const b = { createTime: '2026-01-01T00:00:00Z', messageId: 2 }
      expect(compareMessages(a, b)).toBeLessThan(0)
    })

    it('should fallback to clientMessageId when messageId equal', () => {
      const a = { createTime: '2026-01-01T00:00:00Z', messageId: 1, clientMessageId: 'aaa' }
      const b = { createTime: '2026-01-01T00:00:00Z', messageId: 1, clientMessageId: 'bbb' }
      expect(compareMessages(a, b)).toBeLessThan(0)
    })

    it('should handle null/missing fields', () => {
      expect(compareMessages(null, null)).toBe(0)
      expect(compareMessages({}, {})).toBe(0)
    })
  })
})
