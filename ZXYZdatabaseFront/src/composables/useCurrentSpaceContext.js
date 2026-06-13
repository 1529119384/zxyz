import { computed, inject, provide, unref } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'

import { SPACE_TYPE, normalizeSpaceType } from '@/models/space'
import { useCurrentIdStore } from '@/store/currentId'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { normalizePositiveId } from '@/utils/id'
import { isProjectRootId } from '@/utils/projectVirtualFolder'

export const currentSpaceContextKey = Symbol('currentSpaceContext')

function resolveValue(value) {
  return typeof value === 'function' ? value() : unref(value)
}

function resolveTeamList(teamList) {
  const teams = resolveValue(teamList)
  return Array.isArray(teams) ? teams : []
}

/**
 * 标准化空间请求参数。
 *
 * @param {Object} [params={}] - 原始参数。
 * @param {number|import('vue').Ref<number>|Function} [params.teamId] - 团队 ID。
 * @param {number|import('vue').Ref<number>|Function} [params.spaceType] - 空间类型。
 * @param {number|import('vue').Ref<number>|Function} [params.projectId] - 项目 ID。
 */
export function normalizeSpaceRequestParams(params = {}) {
  return {
    teamId: normalizePositiveId(resolveValue(params.teamId)),
    spaceType: normalizeSpaceType(resolveValue(params.spaceType)),
    projectId: normalizePositiveId(resolveValue(params.projectId)),
  }
}

/**
 * 从空间上下文或回退参数中解析请求参数。
 *
 * @param {Object} [spaceContext] - 空间上下文对象。
 * @param {Object} [fallbackParams={}] - 回退参数。
 * @param {Object} [overrides={}] - 覆盖参数。
 */
export function resolveSpaceRequestParams(spaceContext, fallbackParams = {}, overrides = {}) {
  if (spaceContext?.resolveRequestParams) {
    return spaceContext.resolveRequestParams(overrides)
  }

  return normalizeSpaceRequestParams({
    ...fallbackParams,
    ...overrides,
  })
}

/**
 * @typedef {Object} UseCurrentSpaceContextOptions
 * @property {Object} [route] - Vue Router 路由对象，默认通过 useRoute() 获取。
 * @property {Object} [currentIdStore] - currentId store 实例。
 * @property {Object} [currentUserStore] - currentUser store 实例。
 * @property {Object} [teamStore] - team store 实例。
 * @property {import('vue').Ref<number>|Function} [currentFolderId] - 当前文件夹 ID，可为 Ref 或返回值的函数。
 */

/**
 * 当前空间上下文，根据路由自动判断空间类型（个人/团队/项目）并提供权限信息。
 *
 * @param {UseCurrentSpaceContextOptions} [options={}] - 配置项。
 * @returns {{ spaceType: import('vue').ComputedRef<number>, teamId: import('vue').ComputedRef<number|null>, projectId: import('vue').ComputedRef<number|null>, isPersonalSpace: import('vue').ComputedRef<boolean>, isTeamSpace: import('vue').ComputedRef<boolean>, isProjectSpace: import('vue').ComputedRef<boolean>, requestParams: import('vue').ComputedRef<Object>, canWrite: import('vue').ComputedRef<boolean>, canWriteInExplorer: import('vue').ComputedRef<boolean>, canManageProjects: import('vue').ComputedRef<boolean>, resolveRequestParams: Function }} 空间上下文状态与权限信息。
 */
export function useCurrentSpaceContext(options = {}) {
  const route = options.route || useRoute()
  const currentIdStore = options.currentIdStore || useCurrentIdStore()
  const currentUserStore = options.currentUserStore || useCurrentUserStore()
  const teamStore = options.teamStore || useTeamStore()
  const { canWrite: personalCanWrite } = storeToRefs(currentUserStore)

  const isTeamSpace = computed(() => route.meta?.space === 'team')
  const isProjectSpace = computed(() => route.meta?.space === 'project')
  const isPersonalSpace = computed(() => !isTeamSpace.value && !isProjectSpace.value)
  const projectId = computed(() =>
    normalizePositiveId(route.params.projectId || route.query.projectId),
  )
  const spaceType = computed(() => {
    if (isProjectSpace.value) return SPACE_TYPE.PROJECT
    if (isTeamSpace.value) return SPACE_TYPE.TEAM
    return SPACE_TYPE.PERSONAL
  })
  const teamId = computed(() => {
    if (isPersonalSpace.value) {
      return null
    }

    const selectedTeamId = normalizePositiveId(resolveValue(teamStore.selectedTeamId))
    if (selectedTeamId) {
      return selectedTeamId
    }

    return normalizePositiveId(resolveTeamList(teamStore.teams)[0]?.id)
  })
  const currentFolderId = computed(() => {
    if (options.currentFolderId !== undefined) {
      return resolveValue(options.currentFolderId)
    }

    return currentIdStore.currentId
  })
  const requestParams = computed(() =>
    normalizeSpaceRequestParams({
      teamId,
      spaceType,
      projectId,
    }),
  )
  const canWrite = computed(() => {
    if (isPersonalSpace.value) {
      return Boolean(personalCanWrite.value)
    }
    if (isProjectSpace.value) {
      return Boolean(projectId.value)
    }
    return teamStore.hasTeamPermission(teamId.value, 'team:file:write')
  })
  const canWriteInExplorer = computed(() => {
    if (isTeamSpace.value && isProjectRootId(currentFolderId.value)) {
      // 项目组虚拟根目录不是真实文件目录，需要允许展示“新建/申请项目组”入口。
      return true
    }

    return canWrite.value
  })
  const canManageProjects = computed(() =>
    teamStore.hasTeamPermission(teamId.value, 'team:project:manage'),
  )

  function resolveRequestParams(overrides = {}) {
    return normalizeSpaceRequestParams({
      ...requestParams.value,
      ...overrides,
    })
  }

  return {
    spaceType,
    teamId,
    projectId,
    isPersonalSpace,
    isTeamSpace,
    isProjectSpace,
    requestParams,
    canWrite,
    canWriteInExplorer,
    canManageProjects,
    resolveRequestParams,
  }
}

/**
 * 通过 provide 注入当前空间上下文。
 *
 * @param {Object} [context] - 空间上下文对象，默认通过 useCurrentSpaceContext() 创建。
 */
export function provideCurrentSpaceContext(context = useCurrentSpaceContext()) {
  provide(currentSpaceContextKey, context)
  return context
}

/**
 * 获取已注入的空间上下文，若未注入则回退创建新的。
 *
 * @param {UseCurrentSpaceContextOptions} [fallbackOptions={}] - 回退创建时传递给 useCurrentSpaceContext 的配置项。
 * @returns {ReturnType<typeof useCurrentSpaceContext>} 空间上下文对象。
 */
export function useProvidedSpaceContext(fallbackOptions = {}) {
  return inject(currentSpaceContextKey, null) || useCurrentSpaceContext(fallbackOptions)
}
