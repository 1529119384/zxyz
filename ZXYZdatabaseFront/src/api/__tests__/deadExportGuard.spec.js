// @ts-check
// ⚠️ 首行 `// @ts-check` 是必需的：`src/api/` 属
// scripts/check-typecheck-scope.mjs 的 FULLY_LIT_DIRS（规则 1），
// 该目录下所有 .js 都必须显式点亮，否则 `npm run typecheck:scope` 会失败
// （CI 的 quality-check-frontend 第 7 步会红）。
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
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: {} })

    await fetchConfig('smtp.host')

    expect(request.get).toHaveBeenCalledWith('/api/admin/configs/smtp.host')
  })

  it('应保留 fetchPendingProjectCreateRequests 并请求待审批列表端点', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })

    await fetchPendingProjectCreateRequests(7)

    expect(request.get).toHaveBeenCalledWith('/api/project-create-requests/teams/7/pending')
  })

  it('应保留 fetchProjectMembers 并请求项目成员列表端点', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })

    await fetchProjectMembers(88)

    expect(request.get).toHaveBeenCalledWith('/api/project-members/projects/88/members')
  })

  it('应保留 addProjectMember 并 POST 项目成员添加端点', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await addProjectMember(88, { userId: 3 })

    expect(request.post).toHaveBeenCalledWith('/api/project-members/projects/88/members', {
      userId: 3,
    })
  })

  it('应保留 transferProjectLeader 并 PATCH 项目负责人移交端点', async () => {
    vi.mocked(request.patch).mockResolvedValue({ code: 1 })

    await transferProjectLeader(88, { targetUserId: 5 })

    expect(request.patch).toHaveBeenCalledWith('/api/project-members/projects/88/leader', {
      targetUserId: 5,
    })
  })

  it('应已按 C-9 裁定下线 CONVERSATION_TYPE 与 MESSAGE_STATUS 的对外导出', async () => {
    const conversationTypes = await import('@/constants/conversationTypes')
    const messageStatus = await import('@/constants/messageStatus')

    // ⚠️ 这里刻意用 hasOwnProperty 而不是 `expect(mod.X).toBeUndefined()`：
    //    在 `@ts-check` 下直接访问一个**已被移除**的导出会报 TS2339
    //    （属性不存在于模块类型上）；且「断言 undefined」无法区分
    //    「真的没导出」与「导出了但值是 undefined」。用属性存在性判定更准，
    //    也让该文件在 typecheck 门禁下保持干净。
    expect(Object.prototype.hasOwnProperty.call(conversationTypes, 'CONVERSATION_TYPE')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(messageStatus, 'MESSAGE_STATUS')).toBe(false)

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
