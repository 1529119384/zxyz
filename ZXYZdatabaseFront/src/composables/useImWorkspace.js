import { useChatStore } from '@/store/chat'
import { useTeamStore } from '@/store/team'
import { normalizePositiveId } from '@/utils/id'

/**
 * @typedef {Object} UseImWorkspaceOptions
 * @property {Object} [teamStore] - 团队 Store 实例。
 * @property {Object} [chatStore] - 聊天 Store 实例。
 */

/**
 * IM 工作区组合函数，管理团队切换、会话导航及成员上下文刷新。
 *
 * @param {UseImWorkspaceOptions} [options={}] - 配置选项。
 * @returns {{ switchTeam: Function, loadChatNavigation: Function, loadSelectedTeamChat: Function, refreshTeamMemberContext: Function }} IM 工作区操作方法。
 */
export function useImWorkspace({ teamStore = useTeamStore(), chatStore = useChatStore() } = {}) {
  async function switchTeam(teamId) {
    const normalizedTeamId = normalizePositiveId(teamId)
    teamStore.setSelectedTeam(normalizedTeamId)
    await Promise.all([
      chatStore.loadConversations(normalizedTeamId),
      chatStore.loadNotifications(normalizedTeamId),
      chatStore.loadUnreadCount(normalizedTeamId),
    ])
    if (normalizedTeamId) {
      await teamStore.loadTeamMembersSafe(normalizedTeamId)
    }
  }

  async function loadChatNavigation(teamId = teamStore.selectedTeamId) {
    await Promise.all([chatStore.loadUnreadCount(teamId), chatStore.loadConversations(teamId)])
  }

  async function loadSelectedTeamChat(teamId = teamStore.selectedTeamId) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      return
    }
    await Promise.all([
      chatStore.loadConversations(normalizedTeamId),
      chatStore.loadNotifications(normalizedTeamId),
    ])
    await teamStore.loadTeamMembersSafe(normalizedTeamId)
  }

  async function refreshTeamMemberContext(teamId = teamStore.selectedTeamId) {
    const normalizedTeamId = normalizePositiveId(teamId)
    await Promise.all([
      teamStore.loadTeamMembersSafe(normalizedTeamId),
      teamStore.loadTeams(),
      chatStore.loadConversations(normalizedTeamId),
    ])
  }

  return {
    switchTeam,
    loadChatNavigation,
    loadSelectedTeamChat,
    refreshTeamMemberContext,
  }
}
