import { computed, unref } from 'vue'
import { ElMessage } from 'element-plus'

import {
  TEAM_MANAGEMENT_PERMISSION_CODES,
  TEAM_PERMISSION_CENTER_CODES,
  TEAM_PERMISSION_CODES,
} from '@/constants/teamPermissions'
import { useTeamStore } from '@/store/team'
import { normalizePositiveId } from '@/utils/id'

/** 解析 teamId 入参：支持 Ref、函数（延迟求值）与原始值。 */
function resolveMaybeRef(value) {
  return typeof value === 'function' ? value() : unref(value)
}

/**
 * @typedef {Object} UseTeamPermissionStateOptions
 * @property {Object} [teamStore] - 团队 Store 实例。
 * @property {Object} [currentUserStore] - 当前用户 Store 实例。
 * @property {import('vue').Ref<number>|Function|number} [teamId] - 团队 ID，支持 Ref、函数或原始值。
 */

/**
 * 团队「身份 + 权限判定 + 展示」只读视图（`useTeamManagement` 的第 1/3 片）。
 *
 * **为什么单独拆出来**：这一片全部是**无副作用的读**（computed 与纯函数），
 * 而 `useTeamMemberActions` / `useTeamSettingsActions` 两片全是**写操作**。
 * 拆开之后「谁能改状态」在文件层面就一目了然；两片写操作都以本片为唯一依赖，
 * 于是 `selectedTeamId` / `hasTeamPermission` 的推导只有一处，不会漂移。
 *
 * @param {UseTeamPermissionStateOptions} [options={}] - 配置选项。
 * @returns {{ selectedTeamId: import('vue').ComputedRef<number|null>, currentUserId: import('vue').ComputedRef<number|null>, canAccessPermissionCenter: import('vue').ComputedRef<boolean>, canManageTeamSettings: import('vue').ComputedRef<boolean>, canUpdateTeam: import('vue').ComputedRef<boolean>, canCreateMember: import('vue').ComputedRef<boolean>, canInviteMember: import('vue').ComputedRef<boolean>, canAssignRole: import('vue').ComputedRef<boolean>, canRemoveMember: import('vue').ComputedRef<boolean>, canPublishAnnouncement: import('vue').ComputedRef<boolean>, canManageMute: import('vue').ComputedRef<boolean>, canManageInviteLink: import('vue').ComputedRef<boolean>, canReviewJoinRequests: import('vue').ComputedRef<boolean>, joinLinkText: import('vue').ComputedRef<string>, roleText: Function, displayName: Function, hasTeamPermission: Function, hasAnyTeamPermission: Function, currentTeamId: Function }} 团队身份、权限判定与展示信息。
 */
export function useTeamPermissionState({
  teamStore = useTeamStore(),
  currentUserStore = null,
  teamId = null,
} = {}) {
  const teamManagement = teamStore
  const selectedTeamId = computed(() => {
    const explicitTeamId = resolveMaybeRef(teamId)
    return normalizePositiveId(explicitTeamId) || normalizePositiveId(teamManagement.selectedTeamId)
  })
  const currentUserId = computed(() => currentUserStore?.profile?.id ?? null)
  const canAccessPermissionCenter = computed(() =>
    hasAnyTeamPermission(TEAM_PERMISSION_CENTER_CODES),
  )
  const canManageTeamSettings = computed(() =>
    hasAnyTeamPermission(TEAM_MANAGEMENT_PERMISSION_CODES),
  )
  // 注意：hasTeamPermission 的签名是 ({ teamId, code })，必须传对象。
  // 这里曾直接传 code 字符串，解构后 code 为 undefined → 恒返回 false，
  // 导致团队设置抽屉里 9 个权限位控（保存资料/建成员/邀请/分配角色/移除/
  // 发公告/禁言/邀请链接/审核入队）对所有人（含团队所有者）永久置灰。
  // 团队 ID 有意省略 → 由 hasTeamPermission 内部回退到 selectedTeamId。
  const canUpdateTeam = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.updateTeam }),
  )
  const canCreateMember = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.createMember }),
  )
  const canInviteMember = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.inviteMember }),
  )
  const canAssignRole = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.assignRole }),
  )
  const canRemoveMember = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.removeMember }),
  )
  const canPublishAnnouncement = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.publishAnnouncement }),
  )
  const canManageMute = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.manageMute }),
  )
  const canManageInviteLink = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.manageInviteLink }),
  )
  const canReviewJoinRequests = computed(() =>
    hasTeamPermission({ code: TEAM_PERMISSION_CODES.reviewJoinRequest }),
  )
  const joinLinkText = computed(() => {
    const joinUrl = teamManagement.inviteLink?.joinUrl
    if (!joinUrl || typeof window === 'undefined') {
      return ''
    }
    return `${window.location.origin}${joinUrl}`
  })

  function roleText(role) {
    return (
      { team_owner: '团队所有者', team_admin: '团队管理员', team_member: '团队成员' }[role] ||
      role ||
      '成员'
    )
  }

  function displayName(row = {}) {
    const userId = row.userId ?? row.id
    return row.name || row.username || (userId ? `用户 ${userId}` : '未知用户')
  }

  function hasTeamPermission({ teamId, code }) {
    const normalizedTeamId = normalizePositiveId(teamId) || selectedTeamId.value
    if (!normalizedTeamId || !code) {
      return false
    }
    return teamManagement.hasTeamPermission({ teamId: normalizedTeamId, code })
  }

  function hasAnyTeamPermission(codes, teamId = selectedTeamId.value) {
    return Array.isArray(codes) && codes.some((code) => hasTeamPermission({ teamId, code }))
  }

  /** 取当前团队 ID；缺失时按需提示，供各写操作统一做前置守卫。 */
  function currentTeamId({ warn = true } = {}) {
    const normalizedTeamId = selectedTeamId.value
    if (!normalizedTeamId) {
      if (warn) {
        ElMessage.warning('请先选择团队')
      }
      return null
    }
    return normalizedTeamId
  }

  return {
    selectedTeamId,
    currentUserId,
    canAccessPermissionCenter,
    canManageTeamSettings,
    canUpdateTeam,
    canCreateMember,
    canInviteMember,
    canAssignRole,
    canRemoveMember,
    canPublishAnnouncement,
    canManageMute,
    canManageInviteLink,
    canReviewJoinRequests,
    joinLinkText,
    roleText,
    displayName,
    hasTeamPermission,
    hasAnyTeamPermission,
    currentTeamId,
  }
}
