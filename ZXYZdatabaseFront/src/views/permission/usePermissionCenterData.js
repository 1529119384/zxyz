import { computed, onMounted, reactive, ref, unref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  fetchSystemPermissionAudit,
  fetchSystemPermissions,
  fetchSystemRoles,
} from '@/api/permission'
import { searchUsers } from '@/api/user'
import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { useSystemPermissionActions } from '@/composables/useSystemPermissionActions'
import { useTeamPermissionActions } from '@/composables/useTeamPermissionActions'
import { TEAM_PERMISSION_WORKBENCH_CODES } from '@/constants/teamPermissions'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

/** 内置系统角色编码：不可删除（与后端约定）。 */
const BUILTIN_SYSTEM_ROLE_CODES = new Set(['system_admin', 'system_user'])

/**
 * 权限管理页的数据与装配逻辑（从 `permission/index.vue` 的 `<script setup>` 整段提取）。
 *
 * **为什么提取**：提取之后 `index.vue` 只剩「模板 + 装配」，
 * 而这一层是**纯逻辑、不依赖挂载**的——可以像 composable 单测那样直接调用来验证
 * 「无权限时不发请求」「路由 teamId 会写回 store」等关键分支，
 * 不必 mount 一个 671 行的页面组件。
 *
 * 必须在组件 `setup()` 内调用（内部用了 `useRoute` / `onMounted` / `watch`）。
 *
 * @returns {Object} 权限页所需的全部状态、派生值与操作方法。
 */
export function usePermissionCenterData() {
  const route = useRoute()
  const currentUserStore = useCurrentUserStore()
  const permissionCenter = useTeamStore()
  const activeTab = ref(route.query.scope === 'team' ? 'team' : 'system')
  const systemPermissions = ref([])
  const systemRoles = ref([])
  const systemAudit = ref([])
  const systemUserOptions = ref([])
  const systemUserSearching = ref(false)
  const userRoleForm = reactive({ userId: '', roleCode: '' })
  const memberRoleForm = reactive({ userId: null, roleCode: '' })
  const safeSystemPermissions = computed(() => readArray(systemPermissions))
  const safeSystemRoles = computed(() => readArray(systemRoles))
  const safeSystemAudit = computed(() => readArray(systemAudit))
  const safeTeamPermissions = computed(() => readArray(permissionCenter.teamPermissions))
  const safeTeamRoles = computed(() => readArray(permissionCenter.teamRoles))
  const safeTeamPermissionAudit = computed(() => readArray(permissionCenter.teamPermissionAudit))
  const safeTeamMembers = computed(() => readArray(permissionCenter.teamMembers))

  const routeTeamId = computed(() => {
    const rawValue = Number(route.query.teamId)
    return Number.isSafeInteger(rawValue) && rawValue > 0 ? rawValue : null
  })
  const selectedTeamId = computed(
    () =>
      routeTeamId.value || permissionCenter.selectedTeamId || permissionCenter.teams[0]?.id || null,
  )
  const {
    currentUserId,
    displayName: displayTeamMemberName,
    hasTeamPermission,
    hasAnyTeamPermission,
    loadTeamMembersSafe,
  } = useTeamManagement({ teamStore: permissionCenter, currentUserStore, teamId: selectedTeamId })
  const canReadSystemPermissionCenter = computed(() =>
    currentUserStore.hasAnySystemPermission([
      'system:role:manage',
      'system:permission:read',
      'system:audit:read',
    ]),
  )
  const canManageSystemRoles = computed(() =>
    currentUserStore.hasSystemPermission('system:role:manage'),
  )
  const canReadSystemAudit = computed(() =>
    currentUserStore.hasAnySystemPermission(['system:role:manage', 'system:audit:read']),
  )
  const filteredSystemUserOptions = computed(() =>
    readArray(systemUserOptions).filter(
      (item) => Number(resolveUserId(item)) !== Number(currentUserId.value),
    ),
  )
  const assignableTeamMembers = computed(() =>
    safeTeamMembers.value.filter((item) => Number(item.userId) !== Number(currentUserId.value)),
  )
  const canManageTeamRoles = computed(() => hasTeamPermission({ code: 'team:role:manage' }))
  const canAssignTeamMemberRole = computed(() =>
    hasTeamPermission({ code: 'team:member:assign-role' }),
  )
  const canReadTeamPermissionCenter = computed(() =>
    hasAnyTeamPermission(TEAM_PERMISSION_WORKBENCH_CODES),
  )
  const canReadTeamAudit = computed(
    () => hasTeamPermission({ code: 'team:audit:read' }) || canManageTeamRoles.value,
  )
  const showSystemPermissionTab = computed(
    () => canReadSystemPermissionCenter.value || canReadSystemAudit.value,
  )
  const showTeamPermissionTab = computed(
    () => canReadTeamPermissionCenter.value || canReadTeamAudit.value,
  )
  const readonlyModeText = computed(() => {
    if (activeTab.value === 'system') {
      if (!canReadSystemPermissionCenter.value && !canReadSystemAudit.value) {
        return '当前账号没有系统权限中心访问权限。'
      }
      if (!canManageSystemRoles.value) {
        return '当前为系统只读模式，可以查看权限信息，但不能执行角色任命或编辑。'
      }
      return ''
    }
    if (!selectedTeamId.value) {
      return '请先选择团队后再查看团队权限。'
    }
    if (!canReadTeamPermissionCenter.value && !canReadTeamAudit.value) {
      return '当前团队下没有权限中心访问权限。'
    }
    if (!canManageTeamRoles.value && !canAssignTeamMemberRole.value) {
      return '当前为团队只读模式，可以查看权限信息，但不能执行角色任命或编辑。'
    }
    return ''
  })

  watch(
    routeTeamId,
    (teamId) => {
      if (teamId) {
        permissionCenter.setSelectedTeam(teamId)
      }
    },
    { immediate: true },
  )

  watch(
    () => route.query.scope,
    (scope) => {
      activeTab.value = scope === 'team' ? 'team' : 'system'
    },
    { immediate: true },
  )

  watch(activeTab, (tab) => {
    if (tab !== 'system' && tab !== 'team') {
      activeTab.value = 'system'
    }
  })

  watch(
    selectedTeamId,
    async (teamId) => {
      if (!teamId) {
        return
      }
      try {
        await loadTeamPermissionData(teamId)
      } catch (error) {
        handleBusinessError(error, '加载团队权限数据失败')
      }
    },
    { immediate: true },
  )

  async function refreshAll() {
    try {
      await Promise.all([
        showSystemPermissionTab.value ? loadSystemPermissionData() : Promise.resolve(),
        selectedTeamId.value ? loadTeamPermissionData(selectedTeamId.value) : Promise.resolve(),
      ])
    } catch (error) {
      handleBusinessError(error, '加载权限管理页失败')
    }
  }

  async function loadSystemPermissionData() {
    if (!canReadSystemPermissionCenter.value && !canReadSystemAudit.value) {
      systemPermissions.value = []
      systemRoles.value = []
      systemAudit.value = []
      return
    }
    const [permissionsResult, rolesResult, auditResult] = await Promise.allSettled([
      canReadSystemPermissionCenter.value
        ? fetchSystemPermissions()
        : Promise.resolve({ data: [] }),
      canReadSystemPermissionCenter.value ? fetchSystemRoles() : Promise.resolve({ data: [] }),
      canReadSystemAudit.value ? fetchSystemPermissionAudit() : Promise.resolve({ data: [] }),
    ])
    systemPermissions.value =
      permissionsResult.status === 'fulfilled' && Array.isArray(permissionsResult.value?.data)
        ? permissionsResult.value.data
        : []
    systemRoles.value =
      rolesResult.status === 'fulfilled' && Array.isArray(rolesResult.value?.data)
        ? rolesResult.value.data
        : []
    systemAudit.value =
      auditResult.status === 'fulfilled' && Array.isArray(auditResult.value?.data)
        ? auditResult.value.data
        : []
  }

  async function loadTeamPermissionData(teamId) {
    if (!teamId) {
      permissionCenter.clearTeamPermissionCenter()
      return
    }
    await loadTeamMembersSafe(teamId)
    await permissionCenter.loadTeamPermissionCenter(teamId, {
      includePermissionCenter: canReadTeamPermissionCenter.value,
      includeAudit: canReadTeamAudit.value,
      throwOnFailure: false,
    })
  }

  function formatPermissionLabel(permission) {
    if (!permission) {
      return ''
    }
    const name = permission.permissionName || permission.name || ''
    const code = permission.permissionCode || permission.code || ''
    return name && code ? `${name} (${code})` : name || code
  }

  function isBuiltinSystemRole(row) {
    return BUILTIN_SYSTEM_ROLE_CODES.has(row?.roleCode)
  }

  function isBuiltinTeamRole(row) {
    return Boolean(row?.builtin)
  }

  function formatUserOption(user) {
    const userId = resolveUserId(user)
    const displayName = displayTeamMemberName({ ...user, userId })
    const email = user.email ? ` / ${user.email}` : ''
    return `${displayName} (${userId})${email}`
  }

  function resolveUserId(user) {
    return user?.userId ?? user?.id ?? null
  }

  async function searchSystemUsers(keyword) {
    const normalizedKeyword = typeof keyword === 'string' ? keyword.trim() : ''
    if (!normalizedKeyword) {
      systemUserOptions.value = []
      return
    }
    systemUserSearching.value = true
    try {
      const response = await searchUsers(normalizedKeyword)
      systemUserOptions.value = Array.isArray(response?.data)
        ? response.data.filter(
            (item) => Number(resolveUserId(item)) !== Number(currentUserId.value),
          )
        : []
    } catch (error) {
      handleBusinessError(error, '搜索用户失败')
    } finally {
      systemUserSearching.value = false
    }
  }

  const systemActions = useSystemPermissionActions({
    canManage: canManageSystemRoles,
    refreshAll,
    createAutoRoleCode,
    isBuiltinRole: isBuiltinSystemRole,
  })

  const teamActions = useTeamPermissionActions({
    canManage: canManageTeamRoles,
    refreshAll,
    createAutoRoleCode,
    teamId: selectedTeamId,
    permissionCenter,
    isBuiltinRole: isBuiltinTeamRole,
    currentUserId,
  })

  onMounted(() => {
    refreshAll()
  })

  function readArray(value) {
    // Element Plus 表格只接受数组，权限数据在切换团队/权限时统一兜底。
    const resolved = unref(value)
    return Array.isArray(resolved) ? resolved : []
  }

  function createAutoRoleCode(prefix) {
    const random = Math.random().toString(36).slice(2, 8)
    return `${prefix}_custom_${Date.now()}_${random}`
  }

  return {
    // 页头与页签
    activeTab,
    readonlyModeText,
    refreshAll,
    // 系统区
    showSystemPermissionTab,
    safeSystemPermissions,
    safeSystemRoles,
    safeSystemAudit,
    canReadSystemPermissionCenter,
    canManageSystemRoles,
    canReadSystemAudit,
    isBuiltinSystemRole,
    formatPermissionLabel,
    systemActions,
    userRoleForm,
    systemUserSearching,
    filteredSystemUserOptions,
    searchSystemUsers,
    formatUserOption,
    resolveUserId,
    // 团队区
    selectedTeamId,
    showTeamPermissionTab,
    safeTeamPermissions,
    safeTeamRoles,
    safeTeamPermissionAudit,
    canReadTeamPermissionCenter,
    canManageTeamRoles,
    canAssignTeamMemberRole,
    canReadTeamAudit,
    isBuiltinTeamRole,
    teamActions,
    assignableTeamMembers,
    memberRoleForm,
    displayTeamMemberName,
  }
}
