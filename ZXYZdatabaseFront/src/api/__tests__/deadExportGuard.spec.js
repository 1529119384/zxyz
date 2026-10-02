import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
  },
}))

import request from '@/utils/request'
import { fetchConfig } from '@/api/configAdmin'
// C-9 裁定守门：5 个「后端端点存在、前端暂无消费者」的 API 封装被刻意保留，
// 本用例防止有人误删导出或改动请求契约（此前 api/configAdmin.js、api/project.js 无任何测试）。
import {
  fetchPendingProjectCreateRequests,
  fetchProjectMembers,
  addProjectMember,
  transferProjectLeader,
} from '@/api/project'

describe('C-9 死导出裁定守门', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应保留 fetchConfig 并按 key 请求配置详情端点', async () => {
    request.get.mockResolvedValue({ code: 1, data: {} })

    await fetchConfig('smtp.host')

    expect(request.get).toHaveBeenCalledWith('/api/admin/configs/smtp.host')
  })

  it('应保留 fetchPendingProjectCreateRequests 并请求待审批列表端点', async () => {
    request.get.mockResolvedValue({ code: 1, data: [] })

    await fetchPendingProjectCreateRequests(7)

    expect(request.get).toHaveBeenCalledWith('/api/project-create-requests/teams/7/pending')
  })

  it('应保留 fetchProjectMembers 并请求项目成员列表端点', async () => {
    request.get.mockResolvedValue({ code: 1, data: [] })

    await fetchProjectMembers(88)

    expect(request.get).toHaveBeenCalledWith('/api/project-members/projects/88/members')
  })

  it('应保留 addProjectMember 并 POST 项目成员添加端点', async () => {
    request.post.mockResolvedValue({ code: 1 })

    await addProjectMember(88, { userId: 3 })

    expect(request.post).toHaveBeenCalledWith('/api/project-members/projects/88/members', {
      userId: 3,
    })
  })

  it('应保留 transferProjectLeader 并 PATCH 项目负责人移交端点', async () => {
    request.patch.mockResolvedValue({ code: 1 })

    await transferProjectLeader(88, { targetUserId: 5 })

    expect(request.patch).toHaveBeenCalledWith('/api/project-members/projects/88/leader', {
      targetUserId: 5,
    })
  })

  it('应已按 C-9 裁定下线 CONVERSATION_TYPE 与 MESSAGE_STATUS 的对外导出', async () => {
    const conversationTypes = await import('@/constants/conversationTypes')
    const messageStatus = await import('@/constants/messageStatus')

    expect(conversationTypes.CONVERSATION_TYPE).toBeUndefined()
    expect(messageStatus.MESSAGE_STATUS).toBeUndefined()

    // 具名导出必须保持：它们才是全仓 13+6 个消费点的真实依赖
    expect(conversationTypes.SYSTEM).toBe('SYSTEM')
    expect(conversationTypes.DIRECT).toBe('DIRECT')
    expect(conversationTypes.TEAM).toBe('TEAM')
    expect(conversationTypes.PROJECT).toBe('PROJECT')
    expect(conversationTypes.TEAM_NOTIFICATION).toBe('TEAM_NOTIFICATION')
    expect(messageStatus.SENDING).toBe('SENDING')
    expect(messageStatus.FAILED).toBe('FAILED')
    expect(messageStatus.STORED).toBe('STORED')
    expect(messageStatus.RECALLED).toBe('RECALLED')
  })
})
