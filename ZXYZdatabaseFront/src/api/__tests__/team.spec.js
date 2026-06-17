import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  UPLOAD_REQUEST_TIMEOUT: 30000,
}))

import request from '@/utils/request'
import {
  fetchMyTeams,
  updateTeam,
  fetchTeamMembers,
  createTeamMember,
  removeTeamMember,
  leaveTeam,
} from '@/api/team'

describe('team API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应调用 GET 获取我的团队列表', async () => {
    request.get.mockResolvedValue({ data: [] })
    await fetchMyTeams()
    expect(request.get).toHaveBeenCalledWith('/api/teams/my')
  })

  it('应调用 PATCH 更新团队信息', async () => {
    request.patch.mockResolvedValue({})
    await updateTeam(1, { name: 'new name' })
    expect(request.patch).toHaveBeenCalledWith('/api/teams/1', { name: 'new name' })
  })

  it('应调用 GET 获取团队成员', async () => {
    request.get.mockResolvedValue({ data: [] })
    await fetchTeamMembers(1)
    expect(request.get).toHaveBeenCalledWith('/api/teams/1/members')
  })

  it('应调用 POST 添加团队成员', async () => {
    request.post.mockResolvedValue({})
    await createTeamMember(1, { userId: 42 })
    expect(request.post).toHaveBeenCalledWith('/api/teams/1/members', { userId: 42 })
  })

  it('应调用 DELETE 移除团队成员', async () => {
    request.delete.mockResolvedValue({})
    await removeTeamMember(1, 42)
    expect(request.delete).toHaveBeenCalledWith('/api/teams/1/members/42')
  })

  it('应调用 POST 退出团队', async () => {
    request.post.mockResolvedValue({})
    await leaveTeam(1)
    expect(request.post).toHaveBeenCalledWith('/api/teams/1/leave')
  })
})
