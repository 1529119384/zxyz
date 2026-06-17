import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/imRequest', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}))

import imRequest from '@/utils/imRequest'
import {
  fetchMyConversations,
  fetchConversationMessages,
  searchConversationMessages,
  updateConversationRead,
  recallMessage,
  fetchSystemNotifications,
  fetchMyPresence,
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
})
