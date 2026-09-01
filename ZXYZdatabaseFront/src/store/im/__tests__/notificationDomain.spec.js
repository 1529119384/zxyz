import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

import { createNotificationDomain } from '@/store/im/notificationDomain'

vi.mock('@/api/im', () => ({
  fetchSystemNotificationUnreadCount: vi.fn(),
  fetchSystemNotifications: vi.fn(),
  markSystemNotificationRead: vi.fn(),
}))

function createDomain() {
  const state = {
    notifications: ref([]),
    unreadCount: ref(0),
  }
  const deps = {
    resolveTeamScopedParams: vi.fn((teamId) => (teamId ? { teamId } : {})),
  }
  const domain = createNotificationDomain(state, deps)
  return { state, deps, domain }
}

describe('notificationDomain', () => {
  describe('loadUnreadCount', () => {
    it('空 data 时未读数回落为 0，不抛错', async () => {
      const { state, domain } = createDomain()
      const { fetchSystemNotificationUnreadCount } = await import('@/api/im')
      fetchSystemNotificationUnreadCount.mockResolvedValue({ data: null })

      const result = await domain.loadUnreadCount(7)
      expect(result).toBe(0)
      expect(state.unreadCount.value).toBe(0)
    })

    it('正常返回 unreadCount', async () => {
      const { state, domain } = createDomain()
      const { fetchSystemNotificationUnreadCount } = await import('@/api/im')
      fetchSystemNotificationUnreadCount.mockResolvedValue({ data: { unreadCount: 9 } })

      const result = await domain.loadUnreadCount(7)
      expect(result).toBe(9)
      expect(state.unreadCount.value).toBe(9)
    })

    it('api 失败时向上抛出（当前实现无 try/catch，见风险说明）', async () => {
      const { state, domain } = createDomain()
      const { fetchSystemNotificationUnreadCount } = await import('@/api/im')
      fetchSystemNotificationUnreadCount.mockRejectedValue(new Error('网络错误'))

      await expect(domain.loadUnreadCount(7)).rejects.toThrow('网络错误')
      expect(state.unreadCount.value).toBe(0)
    })
  })

  describe('loadNotifications', () => {
    it('空 data 时通知列表置空且不抛错', async () => {
      const { state, domain } = createDomain()
      const { fetchSystemNotifications, fetchSystemNotificationUnreadCount } =
        await import('@/api/im')
      fetchSystemNotifications.mockResolvedValue({ data: null })
      fetchSystemNotificationUnreadCount.mockResolvedValue({ data: { unreadCount: 2 } })

      const result = await domain.loadNotifications(7)
      expect(result).toEqual([])
      expect(state.notifications.value).toEqual([])
      expect(state.unreadCount.value).toBe(2)
    })

    it('带 resolveTeamScopedParams 分页参数并同步刷新未读数', async () => {
      const { state, deps, domain } = createDomain()
      const { fetchSystemNotifications, fetchSystemNotificationUnreadCount } =
        await import('@/api/im')
      fetchSystemNotifications.mockResolvedValue({
        data: [{ id: 'n1', title: '通知1' }],
      })
      fetchSystemNotificationUnreadCount.mockResolvedValue({ data: { unreadCount: 0 } })

      const result = await domain.loadNotifications(7)
      expect(result).toEqual([{ id: 'n1', title: '通知1' }])
      expect(fetchSystemNotifications).toHaveBeenCalledWith({
        page: 1,
        pageSize: 50,
        teamId: 7,
      })
      expect(deps.resolveTeamScopedParams).toHaveBeenCalledWith(7)
    })

    it('api 失败时向上抛出且不写入列表', async () => {
      const { state, domain } = createDomain()
      const { fetchSystemNotifications } = await import('@/api/im')
      fetchSystemNotifications.mockRejectedValue(new Error('列表接口挂了'))

      await expect(domain.loadNotifications(7)).rejects.toThrow('列表接口挂了')
      expect(state.notifications.value).toEqual([])
    })
  })

  describe('markRead', () => {
    it('标记已读成功后刷新列表', async () => {
      const { state, domain } = createDomain()
      const { markSystemNotificationRead, fetchSystemNotifications, fetchSystemNotificationUnreadCount } =
        await import('@/api/im')
      markSystemNotificationRead.mockResolvedValue({})
      fetchSystemNotifications.mockResolvedValue({ data: [] })
      fetchSystemNotificationUnreadCount.mockResolvedValue({ data: { unreadCount: 0 } })

      await domain.markRead('n1')
      expect(markSystemNotificationRead).toHaveBeenCalledWith('n1')
      expect(fetchSystemNotifications).toHaveBeenCalled()
    })

    it('标记接口失败时向上抛出（当前实现无 try/catch，见风险说明）', async () => {
      const { domain } = createDomain()
      const { markSystemNotificationRead } = await import('@/api/im')
      markSystemNotificationRead.mockRejectedValue(new Error('标记失败'))

      await expect(domain.markRead('n1')).rejects.toThrow('标记失败')
    })
  })
})
