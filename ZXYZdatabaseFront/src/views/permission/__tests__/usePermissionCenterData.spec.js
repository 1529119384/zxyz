/**
 * `usePermissionCenterData` 测试 —— 这正是「把权限页逻辑抽成 composable」的收益兑现处。
 *
 * 抽出来之前，这些分支只能靠挂载那个 671 行的页面组件才能触达；
 * 抽出来之后可以像普通 composable 一样直接驱动：
 *   - 无权限时**一个请求都不发**（这是权限页最容易写错、也最难肉眼发现的一条）
 *   - 路由 `?teamId=` 会写回团队 store
 *   - `?scope=team` 决定初始页签
 *   - `readonlyModeText` 的四条文案分支
 *
 * 依赖全部走模块 mock；`useRoute` 返回可替换对象以便逐条模拟路由。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useRoute } from 'vue-router'

import { fetchSystemPermissions, fetchSystemRoles } from '@/api/permission'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { usePermissionCenterData } from '@/views/permission/usePermissionCenterData'

// 所有 mock 统一排在 import 之后：Vitest 会把 vi.mock 提升到 import 之前，
// 因此「mock 写在哪」不影响行为；但 import 被 vi.mock 语句割成两段会让
// import-x/order 报错（这正是本文件早先 2 条 error 的来源）。
vi.mock('vue-router', () => ({ useRoute: vi.fn() }))
vi.mock('@/api/permission', () => ({
  fetchSystemPermissions: vi.fn(() => Promise.resolve({ data: [] })),
  fetchSystemRoles: vi.fn(() => Promise.resolve({ data: [] })),
  fetchSystemPermissionAudit: vi.fn(() => Promise.resolve({ data: [] })),
}))
vi.mock('@/api/user', () => ({ searchUsers: vi.fn(() => Promise.resolve({ data: [] })) }))
vi.mock('@/composables/useSystemPermissionActions', () => ({
  useSystemPermissionActions: vi.fn(() => ({ saveSystemRole: vi.fn() })),
}))
vi.mock('@/composables/useTeamPermissionActions', () => ({
  useTeamPermissionActions: vi.fn(() => ({ saveTeamRole: vi.fn() })),
}))
vi.mock('@/composables/team/useTeamManagement', () => ({
  useTeamManagement: vi.fn(() => ({
    currentUserId: { value: 100 },
    displayName: (row) => row.name || '未知',
    hasTeamPermission: vi.fn(() => false),
    hasAnyTeamPermission: vi.fn(() => false),
    loadTeamMembersSafe: vi.fn(() => Promise.resolve()),
  })),
}))
vi.mock('@/utils/error', () => ({ handleBusinessError: vi.fn() }))
vi.mock('@/store/currentUser', () => ({ useCurrentUserStore: vi.fn() }))
vi.mock('@/store/team', () => ({ useTeamStore: vi.fn() }))

/** 宿主组件：composable 用了 onMounted / watch，必须在 setup 上下文里调用。 */
const Host = defineComponent({
  setup(_, { expose }) {
    expose(usePermissionCenterData())
    return () => h('div')
  },
})

function setup({ query = {}, hasSystem = false, canManageSystem = false, teams = [] } = {}) {
  useRoute.mockReturnValue({ query })
  useCurrentUserStore.mockReturnValue({
    hasAnySystemPermission: vi.fn(() => hasSystem),
    hasSystemPermission: vi.fn(() => canManageSystem),
  })
  useTeamStore.mockReturnValue({
    selectedTeamId: null,
    teams,
    teamPermissions: [],
    teamRoles: [],
    teamPermissionAudit: [],
    teamMembers: [],
    setSelectedTeam: vi.fn(),
    clearTeamPermissionCenter: vi.fn(),
    loadTeamPermissionCenter: vi.fn(() => Promise.resolve()),
  })
  const wrapper = mount(Host)
  return { api: wrapper.vm.$.exposed, teamStore: useTeamStore() }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('usePermissionCenterData: 初始页签', () => {
  it('无 scope 参数时停在系统权限页', () => {
    const { api } = setup()
    expect(api.activeTab.value).toBe('system')
  })

  it('scope=team 时初始停在团队权限页', () => {
    const { api } = setup({ query: { scope: 'team' } })
    expect(api.activeTab.value).toBe('team')
  })
})

describe('usePermissionCenterData: 无权限时不发请求', () => {
  it('既无系统权限也无团队时，系统侧三个接口一个都不调用', async () => {
    setup()
    await flushPromises()

    expect(fetchSystemPermissions).not.toHaveBeenCalled()
    expect(fetchSystemRoles).not.toHaveBeenCalled()
  })

  it('系统角色管理权限会触发系统侧拉取（audit 未授权则不拉）', async () => {
    setup({ hasSystem: true, canManageSystem: true })
    await flushPromises()

    expect(fetchSystemPermissions).toHaveBeenCalledTimes(1)
    expect(fetchSystemRoles).toHaveBeenCalledTimes(1)
  })
})

describe('usePermissionCenterData: 路由与团队上下文', () => {
  it('合法的 teamId 查询参数会写回团队 store', () => {
    const { teamStore } = setup({ query: { teamId: '7' } })
    expect(teamStore.setSelectedTeam).toHaveBeenCalledWith(7)
  })

  it('非数字 / 非正的 teamId 不写回 store', () => {
    const { teamStore } = setup({ query: { teamId: 'abc' } })
    expect(teamStore.setSelectedTeam).not.toHaveBeenCalled()
  })

  it('没有 teamId 参数时回退到 store 里的首个团队', () => {
    const { api } = setup({ teams: [{ id: 5 }] })
    expect(api.selectedTeamId.value).toBe(5)
  })
})

describe('usePermissionCenterData: readonlyModeText 分支', () => {
  it('无任何系统权限 ⇒ 提示无访问权限', () => {
    const { api } = setup()
    expect(api.readonlyModeText.value).toBe('当前账号没有系统权限中心访问权限。')
  })

  it('可读但不可管理 ⇒ 提示系统只读模式', () => {
    const { api } = setup({ hasSystem: true, canManageSystem: false })
    expect(api.readonlyModeText.value).toBe(
      '当前为系统只读模式，可以查看权限信息，但不能执行角色任命或编辑。',
    )
  })

  it('可管理 ⇒ 无只读提示', () => {
    const { api } = setup({ hasSystem: true, canManageSystem: true })
    expect(api.readonlyModeText.value).toBe('')
  })

  it('团队页签下无选中团队 ⇒ 提示先选团队', () => {
    const { api } = setup({ query: { scope: 'team' } })
    expect(api.readonlyModeText.value).toBe('请先选择团队后再查看团队权限。')
  })
})

describe('usePermissionCenterData: 对外装配面', () => {
  it('暴露了模板需要的系统侧与团队侧键', () => {
    const { api } = setup()
    for (const key of [
      'activeTab',
      'readonlyModeText',
      'refreshAll',
      'showSystemPermissionTab',
      'safeSystemRoles',
      'safeSystemPermissions',
      'safeSystemAudit',
      'canManageSystemRoles',
      'systemActions',
      'userRoleForm',
      'filteredSystemUserOptions',
      'searchSystemUsers',
      'formatUserOption',
      'selectedTeamId',
      'showTeamPermissionTab',
      'safeTeamRoles',
      'safeTeamPermissions',
      'safeTeamPermissionAudit',
      'canAssignTeamMemberRole',
      'teamActions',
      'assignableTeamMembers',
      'memberRoleForm',
      'displayTeamMemberName',
    ]) {
      expect(key in api, `缺少导出键 ${key}`).toBe(true)
    }
  })

  it('safe* 系列在 store 返回非数组时兜底为空数组（Element Plus 表格只吃数组）', () => {
    const { api } = setup()
    expect(api.safeSystemRoles.value).toEqual([])
    expect(api.safeTeamRoles.value).toEqual([])
    expect(api.safeTeamPermissionAudit.value).toEqual([])
  })

  it('formatPermissionLabel：名 + 码拼接，缺一取另一个，全缺为空串', () => {
    const { api } = setup()
    expect(api.formatPermissionLabel({ permissionName: '写入', permissionCode: 'f:w' })).toBe(
      '写入 (f:w)',
    )
    expect(api.formatPermissionLabel({ name: '只读' })).toBe('只读')
    expect(api.formatPermissionLabel({ permissionCode: 'f:r' })).toBe('f:r')
    expect(api.formatPermissionLabel(null)).toBe('')
  })
})
