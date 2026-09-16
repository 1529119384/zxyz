import { useImWorkspace } from '@/composables/useImWorkspace'
import { useChatStore } from '@/store/chat'
import { useTeamStore } from '@/store/team'

import { useTeamMemberActions } from './useTeamMemberActions'
import { useTeamPermissionState } from './useTeamPermissionState'
import { useTeamSettingsActions } from './useTeamSettingsActions'

/**
 * @typedef {Object} UseTeamManagementOptions
 * @property {Object} [teamStore] - 团队 Store 实例。
 * @property {Object} [chatStore] - 聊天 Store 实例。
 * @property {Object} [workspace] - IM 工作区实例。
 * @property {Object} [router] - Vue Router 实例。
 * @property {Object} [currentUserStore] - 当前用户 Store 实例。
 * @property {Function} [close] - 关闭当前面板/对话框的函数。
 * @property {import('vue').Ref<number>|Function|number} [teamId] - 团队 ID，支持 Ref、函数或原始值。
 */

/**
 * 团队管理组合函数，提供团队设置、成员管理、权限校验及公告等操作。
 *
 * **结构**：本文件只做**组合根**（依赖装配 + 对外 API 汇总），实现分三片：
 * - `useTeamPermissionState` —— 身份、权限判定、展示（纯读，是所有推导的唯一来源）
 * - `useTeamMemberActions`   —— 成员/邀请/审核（写）
 * - `useTeamSettingsActions` —— 资料/公告/邀请链接/退出/跳转（写）
 *
 * 拆分的收益不只是行数：**「谁能改状态」在文件层面就能看出来**，
 * 且 `selectedTeamId` / `hasTeamPermission` 的推导只有一处 —— 此前 403 行的
 * 单文件里，读与写交织，任何一处回退逻辑改动都要通读全文。
 * **对外 API 逐字未变**（`useTeamManagement.spec.js` 的「返回面」用例钉住了键集合）。
 *
 * @param {UseTeamManagementOptions} [options={}] - 配置选项。
 * @returns {{ selectedTeamId: import('vue').ComputedRef<number|null>, currentUserId: import('vue').ComputedRef<number|null>, canAccessPermissionCenter: import('vue').ComputedRef<boolean>, canManageTeamSettings: import('vue').ComputedRef<boolean>, canUpdateTeam: import('vue').ComputedRef<boolean>, canCreateMember: import('vue').ComputedRef<boolean>, canInviteMember: import('vue').ComputedRef<boolean>, canAssignRole: import('vue').ComputedRef<boolean>, canRemoveMember: import('vue').ComputedRef<boolean>, canPublishAnnouncement: import('vue').ComputedRef<boolean>, canManageMute: import('vue').ComputedRef<boolean>, canManageInviteLink: import('vue').ComputedRef<boolean>, canReviewJoinRequests: import('vue').ComputedRef<boolean>, joinLinkText: import('vue').ComputedRef<string>, roleText: Function, displayName: Function, hasTeamPermission: Function, hasAnyTeamPermission: Function, currentTeamId: Function, loadTeamMembersSafe: Function, loadTeamManagementSafe: Function, openPermissionCenter: Function, saveTeamProfile: Function, publishAnnouncement: Function, muteMember: Function, unmuteMember: Function, createInviteLink: Function, approveJoinRequest: Function, rejectJoinRequest: Function, leaveTeam: Function, removeMember: Function, createMemberAccount: Function, searchUsers: Function, inviteUser: Function, startDirectChat: Function }} 团队管理状态与操作方法。
 */
export function useTeamManagement({
  teamStore = useTeamStore(),
  chatStore = useChatStore(),
  workspace = useImWorkspace({ teamStore, chatStore }),
  router = null,
  currentUserStore = null,
  close = null,
  teamId = null,
} = {}) {
  const permissionState = useTeamPermissionState({ teamStore, currentUserStore, teamId })
  const memberActions = useTeamMemberActions({
    permissionState,
    teamStore,
    chatStore,
    workspace,
    router,
    close,
  })
  const settingsActions = useTeamSettingsActions({
    permissionState,
    teamStore,
    chatStore,
    router,
    close,
  })

  return {
    ...permissionState,
    ...memberActions,
    ...settingsActions,
  }
}
