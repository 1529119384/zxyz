import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('vue-router', () => ({
  useRoute: vi.fn(),
}))
vi.mock('pinia', () => ({
  storeToRefs: vi.fn((store) => store),
}))
vi.mock('@/store/currentId', () => ({
  useCurrentIdStore: vi.fn(),
}))
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(),
}))
vi.mock('@/store/team', () => ({
  useTeamStore: vi.fn(),
}))
vi.mock('@/utils/projectVirtualFolder', () => ({
  isProjectRootId: vi.fn(() => false),
}))

import { useRoute } from 'vue-router'

import {
  useCurrentSpaceContext,
  resolveSpaceRequestParams,
  normalizeSpaceRequestParams,
} from '@/composables/useCurrentSpaceContext'
import { SPACE_TYPE } from '@/models/space'
import { useCurrentIdStore } from '@/store/currentId'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'

describe('useCurrentSpaceContext', () => {
  const defaultRoute = { meta: {}, params: {}, query: {} }

  function createMockStores(overrides = {}) {
    const currentUserStore = {
      canWrite: ref(overrides.canWrite ?? false),
    }

    const teamStore = {
      teams: ref(overrides.teams ?? [{ id: 100 }]),
      selectedTeamId: ref(overrides.selectedTeamId ?? null),
      hasTeamPermission: vi.fn((teamId, permission) => {
        const perms = overrides.teamPermissions ?? []
        return perms.includes(permission)
      }),
    }

    const currentIdStore = {
      currentId: overrides.currentFolderId ?? -1,
    }

    return { currentUserStore, teamStore, currentIdStore }
  }

  function setupContext(routeOverrides = {}, storeOverrides = {}) {
    const route = { ...defaultRoute, ...routeOverrides }
    useRoute.mockReturnValue(route)

    const { currentUserStore, teamStore, currentIdStore } = createMockStores(storeOverrides)
    useCurrentIdStore.mockReturnValue(currentIdStore)
    useCurrentUserStore.mockReturnValue(currentUserStore)
    useTeamStore.mockReturnValue(teamStore)

    return useCurrentSpaceContext({ route })
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('默认个人空间：isPersonalSpace 为 true，teamId 为 null', () => {
    const { isPersonalSpace, isTeamSpace, isProjectSpace, teamId, spaceType } = setupContext()

    expect(isPersonalSpace.value).toBe(true)
    expect(isTeamSpace.value).toBe(false)
    expect(isProjectSpace.value).toBe(false)
    expect(teamId.value).toBeNull()
    expect(spaceType.value).toBe(SPACE_TYPE.PERSONAL)
  })

  it('团队空间：路由 meta.space 为 team 时正确识别', () => {
    const { isTeamSpace, isPersonalSpace, spaceType, teamId } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: 42 },
    )

    expect(isTeamSpace.value).toBe(true)
    expect(isPersonalSpace.value).toBe(false)
    expect(spaceType.value).toBe(SPACE_TYPE.TEAM)
    expect(teamId.value).toBe(42)
  })

  it('项目空间：路由 meta.space 为 project 时正确识别', () => {
    const { isProjectSpace, spaceType, projectId } = setupContext(
      { meta: { space: 'project' }, params: { projectId: '7' } },
      {},
    )

    expect(isProjectSpace.value).toBe(true)
    expect(spaceType.value).toBe(SPACE_TYPE.PROJECT)
    expect(projectId.value).toBe(7)
  })

  it('resolveRequestParams 返回标准化参数', () => {
    const { resolveRequestParams } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: 10 },
    )

    const params = resolveRequestParams()
    expect(params).toEqual({ teamId: 10, spaceType: SPACE_TYPE.TEAM, projectId: null })
  })

  it('resolveRequestParams 支持 overrides 参数', () => {
    const { resolveRequestParams } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: 10 },
    )

    const params = resolveRequestParams({ projectId: 99 })
    expect(params).toEqual({ teamId: 10, spaceType: SPACE_TYPE.TEAM, projectId: 99 })
  })

  it('团队空间且有写权限时 canWrite 为 true', () => {
    const { canWrite } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: 10, teamPermissions: ['team:file:write'] },
    )

    expect(canWrite.value).toBe(true)
  })

  it('个人空间无写权限时 canWrite 为 false', () => {
    const { canWrite } = setupContext({}, { canWrite: false })

    expect(canWrite.value).toBe(false)
  })

  it('团队无写权限时 canWrite 为 false', () => {
    const { canWrite } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: 10, teamPermissions: [] },
    )

    expect(canWrite.value).toBe(false)
  })

  it('未选中团队时回退到第一个团队的 ID', () => {
    const { teamId } = setupContext(
      { meta: { space: 'team' } },
      { selectedTeamId: null, teams: [{ id: 55 }] },
    )

    expect(teamId.value).toBe(55)
  })
})

describe('resolveSpaceRequestParams（独立函数）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('优先使用 spaceContext.resolveRequestParams', () => {
    const context = { resolveRequestParams: vi.fn(() => ({ teamId: 1, spaceType: 2, projectId: 3 })) }

    const result = resolveSpaceRequestParams(context, { teamId: 10 })

    expect(context.resolveRequestParams).toHaveBeenCalledWith({})
    expect(result).toEqual({ teamId: 1, spaceType: 2, projectId: 3 })
  })

  it('无 spaceContext 时使用回退参数', () => {
    const result = resolveSpaceRequestParams(null, { teamId: '5', spaceType: '2' })

    expect(result).toEqual({ teamId: 5, spaceType: 2, projectId: null })
  })

  it('overrides 覆盖回退参数', () => {
    const result = resolveSpaceRequestParams(
      null,
      { teamId: '5', spaceType: '2' },
      { projectId: '99' },
    )

    expect(result).toEqual({ teamId: 5, spaceType: 2, projectId: 99 })
  })

  it('无效参数标准化为 null', () => {
    const result = resolveSpaceRequestParams(null, { teamId: 'abc', spaceType: '99' })

    expect(result.teamId).toBeNull()
    expect(result.spaceType).toBe(SPACE_TYPE.PERSONAL)
  })
})
