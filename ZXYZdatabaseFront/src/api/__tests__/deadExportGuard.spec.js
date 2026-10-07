// @ts-check
// ⚠️ 首行 `// @ts-check` 是必需的：`src/api/` 属
// scripts/check-typecheck-scope.mjs 的 FULLY_LIT_DIRS（规则 1），
// 该目录下所有 .js 都必须显式点亮，否则 `npm run typecheck:scope` 会失败
// （CI 的 quality-check-frontend 会红）。
//
// 为什么这个文件覆盖**全部**导出（而不是只测 C-9 保留的那几个）：
//   `api/configAdmin.js` 与 `api/project.js` 此前**没有任何测试**（C-9 核对报告
//   原话：「这两个文件目前完全靠人眼守」）。本文件是第一个 import 它们的用例，
//   于是它们从「0 文件加载 ⇒ 不进覆盖率分母」变成「进入分母」。
//   若只测其中 5 个函数，`src/api/**` 的行覆盖率会从 100% 掉到 92%，
//   直接击穿 vite.config.mjs:139 的 96% 棘轮阈值（CI 的 Test 步骤会红）。
//   ⇒ 正确做法是把这两个文件的**每个导出都测到**，而不是绕过阈值。
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
import {
  fetchAllConfigs,
  fetchConfig,
  createConfig,
  updateConfig,
  fetchAuditLogs,
} from '@/api/configAdmin'
import {
  fetchTeamProjects,
  createTeamProject,
  submitProjectCreateRequest,
  fetchPendingProjectCreateRequests,
  approveProjectCreateRequest,
  rejectProjectCreateRequest,
  fetchProjectMembers,
  addProjectMember,
  transferProjectLeader,
  updateProjectQuota,
  archiveProject,
} from '@/api/project'

describe('api/configAdmin 请求契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应 GET 全部配置列表', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })

    await fetchAllConfigs()

    expect(request.get).toHaveBeenCalledWith('/api/admin/configs')
  })

  it('应保留 fetchConfig 并按 key 请求配置详情端点（C-9 保留项）', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: {} })

    await fetchConfig('smtp.host')

    expect(request.get).toHaveBeenCalledWith('/api/admin/configs/smtp.host')
  })

  it('应 POST 创建配置', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await createConfig({ key: 'a', value: 'b' })

    expect(request.post).toHaveBeenCalledWith('/api/admin/configs', { key: 'a', value: 'b' })
  })

  it('应 PUT 更新配置且请求体包成 { value }', async () => {
    vi.mocked(request.put).mockResolvedValue({ code: 1 })

    await updateConfig('a', 'b')

    expect(request.put).toHaveBeenCalledWith('/api/admin/configs/a', { value: 'b' })
  })

  describe('fetchAuditLogs 的分页信封拍平', () => {
    it('应把 { page, total, list } 信封拍平为 data=list 且提升 total', async () => {
      vi.mocked(request.get).mockResolvedValue({
        code: 1,
        data: { page: 1, pageSize: 10, total: 42, list: [{ id: 1 }] },
      })

      const result = await fetchAuditLogs({ page: 2, pageSize: 20 })

      expect(request.get).toHaveBeenCalledWith('/api/admin/configs/audit', {
        params: { page: 2, pageSize: 20 },
      })
      expect(result.data).toEqual([{ id: 1 }])
      expect(result.total).toBe(42)
      // `code` 经展开透传（不写 `expect(result.code)`：该函数的返回类型是
      // axios 响应的展开结果，TS 推断里没有 `code` —— configAdmin.js 的注释
      // 已解释「不写 @returns 是因为拦截器信封与 axios 类型不同源」，
      // 这里用 hasOwnProperty 断言透传行为，避免制造无意义的类型断言）。
      expect(Object.prototype.hasOwnProperty.call(result, 'code')).toBe(true)
    })

    it('应兼容后端回退成裸数组（此时不带 total）', async () => {
      vi.mocked(request.get).mockResolvedValue({ code: 1, data: [{ id: 9 }] })

      const result = await fetchAuditLogs()

      // 不传分页参数时 params 为空对象（不塞入 undefined）
      expect(request.get).toHaveBeenCalledWith('/api/admin/configs/audit', { params: {} })
      expect(result.data).toEqual([{ id: 9 }])
      expect(Object.prototype.hasOwnProperty.call(result, 'total')).toBe(false)
    })

    it('data 既非数组也非分页信封时应降级为空数组', async () => {
      vi.mocked(request.get).mockResolvedValue({ code: 1, data: null })

      const result = await fetchAuditLogs()

      expect(result.data).toEqual([])
      expect(Object.prototype.hasOwnProperty.call(result, 'total')).toBe(false)
    })
  })
})

describe('api/project 请求契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应 GET 团队项目列表并透传 abort signal', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })
    const controller = new AbortController()

    await fetchTeamProjects(7, { signal: controller.signal })

    expect(request.get).toHaveBeenCalledWith('/api/project-catalog/teams/7/projects', {
      signal: controller.signal,
    })
  })

  it('应 POST 创建团队项目', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await createTeamProject(7, { name: 'p' })

    expect(request.post).toHaveBeenCalledWith('/api/project-catalog/teams/7/projects', { name: 'p' })
  })

  it('应 POST 提交项目创建申请', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await submitProjectCreateRequest(7, { reason: 'r' })

    expect(request.post).toHaveBeenCalledWith('/api/project-create-requests/teams/7', {
      reason: 'r',
    })
  })

  it('应保留 fetchPendingProjectCreateRequests 并请求待审批列表端点（C-9 保留项）', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })

    await fetchPendingProjectCreateRequests(7)

    expect(request.get).toHaveBeenCalledWith('/api/project-create-requests/teams/7/pending')
  })

  it('应 POST 通过申请（不传体时默认空对象）', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await approveProjectCreateRequest(11)

    expect(request.post).toHaveBeenCalledWith('/api/project-create-requests/11/approve', {})
  })

  it('应 POST 驳回申请并透传请求体', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await rejectProjectCreateRequest(11, { reason: 'no' })

    expect(request.post).toHaveBeenCalledWith('/api/project-create-requests/11/reject', {
      reason: 'no',
    })
  })

  it('应保留 fetchProjectMembers 并请求项目成员列表端点（C-9 保留项）', async () => {
    vi.mocked(request.get).mockResolvedValue({ code: 1, data: [] })

    await fetchProjectMembers(88)

    expect(request.get).toHaveBeenCalledWith('/api/project-members/projects/88/members')
  })

  it('应保留 addProjectMember 并 POST 项目成员添加端点（C-9 保留项）', async () => {
    vi.mocked(request.post).mockResolvedValue({ code: 1 })

    await addProjectMember(88, { userId: 3 })

    expect(request.post).toHaveBeenCalledWith('/api/project-members/projects/88/members', {
      userId: 3,
    })
  })

  it('应保留 transferProjectLeader 并 PATCH 项目负责人移交端点（C-9 保留项）', async () => {
    vi.mocked(request.patch).mockResolvedValue({ code: 1 })

    await transferProjectLeader(88, { targetUserId: 5 })

    expect(request.patch).toHaveBeenCalledWith('/api/project-members/projects/88/leader', {
      targetUserId: 5,
    })
  })

  it('应 PATCH 项目配额', async () => {
    vi.mocked(request.patch).mockResolvedValue({ code: 1 })

    await updateProjectQuota(88, { storageLimit: 100 })

    expect(request.patch).toHaveBeenCalledWith('/api/project-quotas/projects/88', {
      storageLimit: 100,
    })
  })

  it('应 PATCH 归档项目', async () => {
    vi.mocked(request.patch).mockResolvedValue({ code: 1 })

    await archiveProject(88)

    expect(request.patch).toHaveBeenCalledWith('/api/project-lifecycle/projects/88/archive')
  })
})

describe('C-9 死导出裁定守门', () => {
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
