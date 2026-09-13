// @ts-check
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))

import request from '@/utils/request'
import {
  fetchAdminTeams,
  createAdminTeam,
  updateAdminTeamQuota,
  broadcastSystemMessage,
  scheduleSystemEmailBatch,
} from '@/api/adminTeam'

describe('adminTeam API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 审计 L11：列表接口从「一次返回全部团队」改为 PageResult 真分页。
  // 这里钉住「分页参数真的被透传」—— 否则前端会永远停在第一页而看不出报错。
  it('fetchAdminTeams 透传分页参数', async () => {
    vi.mocked(request.get).mockResolvedValue({
      data: { page: 2, pageSize: 20, total: 41, list: [] },
    })
    await fetchAdminTeams({ page: 2, pageSize: 20 })
    expect(request.get).toHaveBeenCalledWith('/api/admin/teams', {
      params: { page: 2, pageSize: 20 },
    })
  })

  it('fetchAdminTeams 不传参数时不下发 params（由后端取默认 1 / 20）', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { total: 0, list: [] } })
    await fetchAdminTeams()
    expect(request.get).toHaveBeenCalledWith('/api/admin/teams', { params: undefined })
  })

  it('createAdminTeam 应 POST 到 /api/admin/teams', async () => {
    vi.mocked(request.post).mockResolvedValue({})
    await createAdminTeam({ name: 'team-a' })
    expect(request.post).toHaveBeenCalledWith('/api/admin/teams', { name: 'team-a' })
  })

  it('updateAdminTeamQuota 应 PATCH 指定团队的 quota 子路径', async () => {
    vi.mocked(request.patch).mockResolvedValue({})
    await updateAdminTeamQuota(7, { memberLimit: 10, storageLimit: 2048 })
    expect(request.patch).toHaveBeenCalledWith('/api/admin/teams/7/quota', {
      memberLimit: 10,
      storageLimit: 2048,
    })
  })

  it('broadcastSystemMessage 与 scheduleSystemEmailBatch 端点在位', async () => {
    vi.mocked(request.post).mockResolvedValue({})
    await broadcastSystemMessage({ title: 'a', content: 'b' })
    expect(request.post).toHaveBeenCalledWith('/api/admin/teams/system-messages', {
      title: 'a',
      content: 'b',
    })
    await scheduleSystemEmailBatch({ subject: 's' })
    expect(request.post).toHaveBeenCalledWith('/api/admin/teams/system-emails/scheduled-batches', {
      subject: 's',
    })
  })
})
