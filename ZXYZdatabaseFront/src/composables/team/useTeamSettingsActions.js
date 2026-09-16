import { ElMessage } from 'element-plus'

import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

/**
 * @typedef {Object} UseTeamSettingsActionsOptions
 * @property {Object} permissionState - `useTeamPermissionState` 的返回值（唯一来源，勿重复推导）。
 * @property {Object} teamStore - 团队 Store 实例。
 * @property {Object} chatStore - 聊天 Store 实例。
 * @property {Object} [router] - Vue Router 实例。
 * @property {Function} [close] - 关闭当前面板/对话框的函数。
 */

/**
 * 团队设置/公告/邀请链接/退出类**写操作**（`useTeamManagement` 的第 3/3 片）。
 *
 * 与 `useTeamMemberActions` 同一约定：返回 `true / false`，失败交给 `handleBusinessError`。
 *
 * @param {UseTeamSettingsActionsOptions} options - 配置选项。
 * @returns {{ loadTeamManagementSafe: Function, openPermissionCenter: Function, saveTeamProfile: Function, publishAnnouncement: Function, createInviteLink: Function, leaveTeam: Function }} 团队设置相关操作。
 */
export function useTeamSettingsActions({
  permissionState,
  teamStore,
  chatStore,
  router = null,
  close = null,
}) {
  const teamManagement = teamStore
  const { selectedTeamId, canAccessPermissionCenter, currentTeamId } = permissionState

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

  return {
    loadTeamManagementSafe,
    openPermissionCenter,
    saveTeamProfile,
    publishAnnouncement,
    createInviteLink,
    leaveTeam,
  }
}
