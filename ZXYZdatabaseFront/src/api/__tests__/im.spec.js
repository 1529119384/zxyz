import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/imRequest', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}))

import imRequest from '@/utils/imRequest'
import {
  fetchImHealth,
  fetchMyConversations,
  fetchConversation,
  createDirectConversation,
  fetchTeamConversation,
  fetchConversationMessages,
  searchConversationMessages,
  resolveMessageFileCard,
  updateConversationRead,
  recallMessage,
  fetchSystemNotifications,
  fetchSystemNotificationUnreadCount,
  fetchMyPresence,
  fetchUserPresence,
  markSystemNotificationRead,
} from '@/api/im'

describe('im API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应调用 GET 获取会话列表', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await fetchMyConversations({ page: 1 })
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/conversations', { params: { page: 1 } })
  })

  it('应调用 GET 获取会话消息', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await fetchConversationMessages(10, { limit: 50 })
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/conversations/10/messages', {
      params: { limit: 50 },
    })
  })

  it('应调用 GET 搜索会话消息', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await searchConversationMessages(10, { keyword: 'hello' })
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/conversations/10/messages/search', {
      params: { keyword: 'hello' },
    })
  })

  it('应调用 POST 标记已读', async () => {
    imRequest.post.mockResolvedValue({})
    await updateConversationRead(10, { lastMessageId: 100 })
    expect(imRequest.post).toHaveBeenCalledWith('/api/im/conversations/10/read', {
      lastMessageId: 100,
    })
  })

  it('应调用 POST 撤回消息', async () => {
    imRequest.post.mockResolvedValue({})
    await recallMessage(55, { reason: '发错了' })
    expect(imRequest.post).toHaveBeenCalledWith('/api/im/messages/55/recall', { reason: '发错了' })
  })

  it('应调用 GET 获取系统通知', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await fetchSystemNotifications({ status: 0 })
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/system-notifications', {
      params: { status: 0 },
    })
  })

  it('应调用 GET 获取在线状态', async () => {
    imRequest.get.mockResolvedValue({ data: {} })
    await fetchMyPresence()
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/presence/me')
  })

  it('应调用 GET 检查 IM 服务健康状态', async () => {
    imRequest.get.mockResolvedValue({ data: { status: 'UP' } })
    await fetchImHealth()
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/health')
  })

  it('应调用 GET 获取单个会话详情', async () => {
    imRequest.get.mockResolvedValue({ data: {} })
    await fetchConversation(7)
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/conversations/7')
  })

  it('应调用 POST 创建单聊会话', async () => {
    imRequest.post.mockResolvedValue({ data: {} })
    await createDirectConversation({ targetUserId: 42 })
    expect(imRequest.post).toHaveBeenCalledWith('/api/im/direct-conversations', {
      targetUserId: 42,
    })
  })

  it('应调用 GET 获取团队会话', async () => {
    imRequest.get.mockResolvedValue({ data: {} })
    await fetchTeamConversation(10)
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/teams/10/conversation')
  })

  it('应调用 POST 解析消息文件卡片', async () => {
    imRequest.post.mockResolvedValue({ data: {} })
    await resolveMessageFileCard(99, { messageId: 99 })
    expect(imRequest.post).toHaveBeenCalledWith('/api/im/messages/99/file-card/resolve', {
      messageId: 99,
    })
  })

  it('resolveMessageFileCard 省略 payload 时传空对象', async () => {
    imRequest.post.mockResolvedValue({ data: {} })
    await resolveMessageFileCard(99)
    expect(imRequest.post).toHaveBeenCalledWith('/api/im/messages/99/file-card/resolve', {})
  })

  it('应调用 GET 获取系统通知未读数', async () => {
    imRequest.get.mockResolvedValue({ data: { unreadCount: 3 } })
    await fetchSystemNotificationUnreadCount({ teamId: 5 })
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/system-notifications/unread-count', {
      params: { teamId: 5 },
    })
  })

  it('应调用 GET 批量查询用户在线状态（userIds 逗号拼接）', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await fetchUserPresence([1, 2, 3])
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/presence/users', {
      params: { userIds: '1,2,3' },
    })
  })

  it('fetchUserPresence 默认空数组时拼接为空串', async () => {
    imRequest.get.mockResolvedValue({ data: [] })
    await fetchUserPresence()
    expect(imRequest.get).toHaveBeenCalledWith('/api/im/presence/users', {
      params: { userIds: '' },
    })
  })

  it('应调用 PATCH 标记单条系统通知已读', async () => {
    imRequest.patch.mockResolvedValue({})
    await markSystemNotificationRead(11)
    expect(imRequest.patch).toHaveBeenCalledWith('/api/im/system-notifications/11/read')
  })
})
