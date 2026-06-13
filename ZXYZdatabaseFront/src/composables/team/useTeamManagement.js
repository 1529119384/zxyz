import { computed, unref } from 'vue'
import { ElMessage } from 'element-plus'

import { createTeamMember } from '@/api/team'
import {
  TEAM_MANAGEMENT_PERMISSION_CODES,
  TEAM_PERMISSION_CENTER_CODES,
  TEAM_PERMISSION_CODES,
} from '@/constants/teamPermissions'
import { useImWorkspace } from '@/composables/useImWorkspace'
import { useChatStore } from '@/store/chat'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

function resolveMaybeRef(value) {
  return typeof value === 'function' ? value() : unref(value)
}

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
  const canUpdateTeam = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.updateTeam))
  const canCreateMember = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.createMember))
  const canInviteMember = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.inviteMember))
  const canAssignRole = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.assignRole))
  const canRemoveMember = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.removeMember))
  const canPublishAnnouncement = computed(() =>
    hasTeamPermission(TEAM_PERMISSION_CODES.publishAnnouncement),
  )
  const canManageMute = computed(() => hasTeamPermission(TEAM_PERMISSION_CODES.manageMute))
  const canManageInviteLink = computed(() =>
    hasTeamPermission(TEAM_PERMISSION_CODES.manageInviteLink),
  )
  const canReviewJoinRequests = computed(() =>
    hasTeamPermission(TEAM_PERMISSION_CODES.reviewJoinRequest),
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

  function hasTeamPermission(code, teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId || !code) {
      return false
    }
    return teamManagement.hasTeamPermission(normalizedTeamId, code)
  }

  function hasAnyTeamPermission(codes, teamId = selectedTeamId.value) {
    return Array.isArray(codes) && codes.some((code) => hasTeamPermission(code, teamId))
  }

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

  async function loadTeamMembersSafe(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    return teamManagement.loadTeamMembersSafe(normalizedTeamId)
  }

  async function loadTeamManagementSafe(
    teamId = selectedTeamId.value,
    errorMessage = '加载团队设置失败',
  ) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      teamManagement.clearTeamManagement()
      return
    }
    await teamManagement.loadTeamManagement(normalizedTeamId).catch((error) => {
      handleBusinessError(error, errorMessage)
    })
  }

  function openPermissionCenter() {
    if (!router || !canAccessPermissionCenter.value) {
      return
    }
    if (typeof close === 'function') {
      close()
    }
    router.push({
      name: 'permissionCenter',
      query: selectedTeamId.value ? { scope: 'team', teamId: String(selectedTeamId.value) } : {},
    })
  }

  async function saveTeamProfile(profileForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.updateSelectedTeam(profileForm)
      await chatStore.loadConversations(teamId)
      ElMessage.success('团队资料已保存')
      return true
    } catch (error) {
      handleBusinessError(error, '保存团队资料失败')
      return false
    }
  }

  async function publishAnnouncement(announcementForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.publishAnnouncement(teamId, announcementForm)
      await Promise.all([chatStore.loadConversations(teamId), chatStore.loadNotifications(teamId)])
      announcementForm.title = ''
      announcementForm.content = ''
      ElMessage.success('公告已发布')
      return true
    } catch (error) {
      handleBusinessError(error, '发布公告失败')
      return false
    }
  }

  async function muteMember(muteForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.muteMember(teamId, muteForm)
      muteForm.userId = null
      muteForm.reason = ''
      ElMessage.success('成员已禁言')
      return true
    } catch (error) {
      handleBusinessError(error, '禁言成员失败')
      return false
    }
  }

  async function unmuteMember(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.unmuteMember(teamId, userId)
      ElMessage.success('已解除禁言')
      return true
    } catch (error) {
      handleBusinessError(error, '解除禁言失败')
      return false
    }
  }

  async function createInviteLink(inviteLinkForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.createInviteLink(teamId, inviteLinkForm)
      ElMessage.success('邀请链接已生成')
      return true
    } catch (error) {
      handleBusinessError(error, '生成邀请链接失败')
      return false
    }
  }

  async function approveJoinRequest(requestId) {
    try {
      await teamManagement.approveJoinRequest(requestId)
      await chatStore.loadConversations()
      ElMessage.success('已通过申请')
      return true
    } catch (error) {
      handleBusinessError(error, '审核申请失败')
      return false
    }
  }

  async function rejectJoinRequest(requestId) {
    try {
      await teamManagement.rejectJoinRequest(requestId)
      ElMessage.success('已拒绝申请')
      return true
    } catch (error) {
      handleBusinessError(error, '审核申请失败')
      return false
    }
  }

  async function leaveTeam() {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.leaveSelectedTeam(teamId)
      chatStore.clearActiveConversation()
      await chatStore.loadConversations()
      if (typeof close === 'function') {
        close()
      }
      ElMessage.success('已退出团队')
      return true
    } catch (error) {
      handleBusinessError(error, '退出团队失败')
      return false
    }
  }

  async function removeMember(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.removeMember(teamId, userId)
      await chatStore.loadConversations(teamId)
      ElMessage.success('已移除成员')
      return true
    } catch (error) {
      handleBusinessError(error, '移除成员失败')
      return false
    }
  }

  async function createMemberAccount(memberForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    const username = memberForm.username.trim()
    const password = memberForm.password.trim()
    if (!username || !password) {
      ElMessage.warning('请填写成员用户名和初始密码')
      return false
    }
    if (password.length < 6) {
      ElMessage.warning('初始密码不能少于 6 位')
      return false
    }
    try {
      await createTeamMember(teamId, {
        username,
        password,
        name: memberForm.name.trim() || null,
        roleCode: memberForm.roleCode,
      })
      memberForm.username = ''
      memberForm.password = ''
      memberForm.name = ''
      memberForm.roleCode = 'team_member'
      // 成员创建会影响成员列表、团队会话和左侧团队摘要，需要统一刷新。
      await workspace.refreshTeamMemberContext(teamId)
      ElMessage.success('成员账号已创建')
      return true
    } catch (error) {
      handleBusinessError(error, '创建成员账号失败')
      return false
    }
  }

  async function searchUsers(keyword) {
    try {
      await teamManagement.searchUsers(keyword)
      return true
    } catch (error) {
      handleBusinessError(error, '搜索用户失败')
      return false
    }
  }

  async function inviteUser(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.inviteUser(teamId, userId)
      ElMessage.success('邀请已发送')
      return true
    } catch (error) {
      handleBusinessError(error, '发送邀请失败')
      return false
    }
  }

  async function startDirectChat(targetUserId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await chatStore.createDirectConversationAndOpen(teamId, targetUserId)
      if (typeof close === 'function') {
        close()
      }
      if (router) {
        router.push({ name: 'chatHome' })
      }
      return true
    } catch (error) {
      handleBusinessError(error, '创建私聊失败')
      return false
    }
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
    loadTeamMembersSafe,
    loadTeamManagementSafe,
    openPermissionCenter,
    saveTeamProfile,
    publishAnnouncement,
    muteMember,
    unmuteMember,
    createInviteLink,
    approveJoinRequest,
    rejectJoinRequest,
    leaveTeam,
    removeMember,
    createMemberAccount,
    searchUsers,
    inviteUser,
    startDirectChat,
  }
}
