import { computed } from 'vue'

import { createAdminTeam } from '@/api/adminTeam'
import { fetchMyTeams, fetchTeamMembers, leaveTeam, removeTeamMember, updateTeam } from '@/api/team'
import {
  acceptTeamInvitation,
  approveTeamJoinRequest,
  createTeamInviteLink,
  fetchTeamJoinRequests,
  fetchTeamMutes,
  inviteTeamUser,
  muteTeamMember,
  publishTeamAnnouncement,
  rejectTeamInvitation,
  rejectTeamJoinRequest,
  searchTeamInviteCandidates,
  submitTeamJoinRequest,
  unmuteTeamMember,
} from '@/api/teamIm'
import { useCurrentUserStore } from '@/store/currentUser'
import { normalizePositiveId } from '@/utils/id'

import { normalizeTeam, normalizeTeamMember, requireTeamId } from './normalizers'

export function createTeamDomain(state, deps = {}) {
  const {
    teams,
    selectedTeamId,
    defaultTeamId,
    teamMembers,
    teamMutes,
    joinRequests,
    inviteLink,
    userSearchResults,
  } = state

  // J-8 收口：本域不再持有 clearActiveConversation / loadConversations / loadNotifications
  // 依赖 —— 此前 team.js 组装时只传 { emitter }，这三个依赖永远落在 no-op 默认值上，
  // 「leaveSelectedTeam 等会刷新聊天数据」是假象。真实调用路径（useTeamSettingsActions /
  // useTeamMemberActions / useImWorkspace）都在 composable 层自行编排 chatStore 刷新，
  // 且向 team.js 注入 chat 依赖会与 chatBridge 的运行时桥接形成循环初始化风险。
  const { emitter } = deps

  const selectedTeam = computed(
    () => teams.value.find((team) => normalizePositiveId(team.id) === selectedTeamId.value) || null,
  )
  const currentTeamPermissions = computed(() =>
    Array.isArray(selectedTeam.value?.myPermissions) ? selectedTeam.value.myPermissions : [],
  )
  const hasTeams = computed(() => teams.value.length > 0)
  const needsTeamSwitcher = computed(() => teams.value.length >= 2)

  function setSelectedTeam(teamId) {
    selectedTeamId.value = normalizePositiveId(teamId)
  }

  function setDefaultTeam(teamId) {
    defaultTeamId.value = normalizePositiveId(teamId)
  }

  function syncDefaultTeamFromProfile() {
    const profileDefaultTeamId = normalizePositiveId(useCurrentUserStore().profile?.defaultTeamId)
    if (profileDefaultTeamId || defaultTeamId.value) {
      setDefaultTeam(profileDefaultTeamId)
    }
    return defaultTeamId.value
  }

  function resolveTeamScopedParams(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    return normalizedTeamId ? { teamId: normalizedTeamId } : {}
  }

  function clearTeamMembers() {
    teamMembers.value = []
  }

  function clearTeamManagement() {
    teamMutes.value = []
    joinRequests.value = []
  }

  function handleTeamAccessRevoked(teamId) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      return
    }
    teams.value = teams.value.filter((item) => Number(item.id) !== Number(normalizedTeamId))
    if (Number(selectedTeamId.value) === Number(normalizedTeamId)) {
      selectedTeamId.value = null
      clearTeamMembers()
      clearTeamManagement()
    }
  }

  async function loadTeams() {
    syncDefaultTeamFromProfile()
    const response = await fetchMyTeams()
    teams.value = Array.isArray(response?.data)
      ? response.data
          .filter((team) => normalizePositiveId(team?.id))
          .map((team) => normalizeTeam(team))
      : []
    const teamIds = teams.value.map((team) => normalizePositiveId(team.id)).filter(Boolean)
    const currentTeamExists = teamIds.includes(selectedTeamId.value)
    if (!currentTeamExists) {
      if (teamIds.length === 1) {
        selectedTeamId.value = teamIds[0]
      } else if (defaultTeamId.value && teamIds.includes(defaultTeamId.value)) {
        selectedTeamId.value = defaultTeamId.value
      } else {
        selectedTeamId.value = null
        if (defaultTeamId.value && !teamIds.includes(defaultTeamId.value)) {
          setDefaultTeam(null)
        }
      }
    }
    if (selectedTeamId.value) {
      await loadTeamMembersSafe(selectedTeamId.value)
    } else {
      clearTeamMembers()
      clearTeamManagement()
    }
    return teams.value
  }

  // 过期响应令牌（J-4，与 usePagedList 同法）：快速切换团队时两个 loadTeamMembers 同时在飞，
  // 先发起的慢响应不得覆盖后发起团队的成员列表，也不得把 selectedTeamId 拽回旧团队。
  let latestTeamMembersToken = 0

  async function loadTeamMembers(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      clearTeamMembers()
      return []
    }
    const requestToken = ++latestTeamMembersToken
    selectedTeamId.value = normalizedTeamId
    const response = await fetchTeamMembers(normalizedTeamId)
    if (requestToken !== latestTeamMembersToken) {
      return teamMembers.value
    }
    teamMembers.value = Array.isArray(response?.data)
      ? response.data.map((item) => normalizeTeamMember(item))
      : []
    return teamMembers.value
  }

  async function loadTeamMembersSafe(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      clearTeamMembers()
      return []
    }
    try {
      return await loadTeamMembers(normalizedTeamId)
    } catch {
      // 成员加载失败时也必须由 store 统一清空，避免 UI 层绕过 action 写内部状态。
      clearTeamMembers()
      return []
    }
  }

  async function loadTeamManagement(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      clearTeamManagement()
      return
    }
    const [mutesResponse, requestsResponse] = await Promise.all([
      fetchTeamMutes(normalizedTeamId),
      fetchTeamJoinRequests(normalizedTeamId),
    ])
    teamMutes.value = Array.isArray(mutesResponse?.data) ? mutesResponse.data : []
    joinRequests.value = Array.isArray(requestsResponse?.data) ? requestsResponse.data : []
  }

  async function createNewTeam(payload) {
    const response = await createAdminTeam(payload)
    // 团队列表刷新在域内完成；会话列表刷新由调用方（composable 层）编排（J-8）。
    await loadTeams()
    const createdTeamId = normalizePositiveId(response?.data?.id)
    if (createdTeamId) {
      selectedTeamId.value = createdTeamId
      await loadTeamMembers(createdTeamId)
    } else if (selectedTeamId.value) {
      await loadTeamMembers(selectedTeamId.value)
    }
    return response?.data
  }

  async function updateSelectedTeam(payload) {
    const teamId = requireTeamId(selectedTeamId.value)
    const response = await updateTeam(teamId, payload)
    const updatedTeam = response?.data || null
    if (updatedTeam?.id) {
      const normalizedTeam = normalizeTeam(updatedTeam)
      const index = teams.value.findIndex((team) => Number(team.id) === Number(normalizedTeam.id))
      if (index >= 0) {
        teams.value.splice(index, 1, { ...teams.value[index], ...normalizedTeam })
      }
    }
    return updatedTeam
  }

  function hasTeamPermission({ teamId, code }) {
    const normalizedTeamId = normalizePositiveId(teamId) || selectedTeamId.value
    if (!normalizedTeamId || !code) {
      return false
    }
    const team = teams.value.find((item) => normalizePositiveId(item.id) === normalizedTeamId)
    return Array.isArray(team?.myPermissions) && team.myPermissions.includes(code)
  }

  async function searchUsers(keyword) {
    const response = await searchTeamInviteCandidates(keyword)
    userSearchResults.value = Array.isArray(response?.data) ? response.data : []
    return userSearchResults.value
  }

  async function inviteUser(teamId, userId) {
    const response = await inviteTeamUser(requireTeamId(teamId), userId)
    return response?.data
  }

  async function leaveSelectedTeam(teamId = selectedTeamId.value) {
    await leaveTeam(requireTeamId(teamId))
    // 激活会话清空与会话列表刷新由调用方（useTeamSettingsActions.leaveTeam）编排（J-8）。
    selectedTeamId.value = null
    await loadTeams()
  }

  async function removeMember(teamId, userId) {
    const normalizedTeamId = requireTeamId(teamId)
    await removeTeamMember(normalizedTeamId, userId)
    // 会话列表刷新由调用方（useTeamMemberActions.removeMember）编排（J-8）。
    await Promise.all([loadTeamMembers(normalizedTeamId), loadTeamManagement(normalizedTeamId)])
  }

  async function acceptInvitation(invitationId) {
    const response = await acceptTeamInvitation(invitationId)
    // 通知与会话刷新由调用方编排（J-8）；本域只负责团队列表与成员。
    await loadTeams()
    return response?.data
  }

  async function rejectInvitation(invitationId) {
    const response = await rejectTeamInvitation(invitationId)
    return response?.data
  }

  async function publishAnnouncement(teamId, payload) {
    const response = await publishTeamAnnouncement(requireTeamId(teamId), payload)
    // 会话/通知刷新由调用方（useTeamSettingsActions.publishAnnouncement）编排（J-8）。
    return response?.data
  }

  async function muteMember(teamId, payload) {
    const normalizedTeamId = requireTeamId(teamId)
    const response = await muteTeamMember(normalizedTeamId, payload)
    await loadTeamManagement(normalizedTeamId)
    return response?.data
  }

  async function unmuteMember(teamId, userId) {
    const normalizedTeamId = requireTeamId(teamId)
    await unmuteTeamMember(normalizedTeamId, userId)
    await loadTeamManagement(normalizedTeamId)
  }

  async function createInviteLink(teamId, payload = {}) {
    const response = await createTeamInviteLink(requireTeamId(teamId), payload)
    inviteLink.value = response?.data || null
    return inviteLink.value
  }

  async function submitJoinRequest(token) {
    const response = await submitTeamJoinRequest(token)
    return response?.data
  }

  async function approveJoinRequest(requestId) {
    const response = await approveTeamJoinRequest(requestId)
    // 会话列表刷新由调用方（useTeamMemberActions.approveJoinRequest）编排（J-8）。
    const tasks = [loadTeams()]
    if (selectedTeamId.value) {
      tasks.push(loadTeamManagement(selectedTeamId.value))
    }
    await Promise.all(tasks)
    return response?.data
  }

  async function rejectJoinRequest(requestId) {
    const response = await rejectTeamJoinRequest(requestId)
    if (selectedTeamId.value) {
      await loadTeamManagement(selectedTeamId.value)
    }
    return response?.data
  }

  async function refreshTeamPermissionCenter(teamId = selectedTeamId.value) {
    if (emitter) {
      emitter.emit('permissionCenterNeedsReload', teamId)
    }
  }

  return {
    selectedTeam,
    currentTeamPermissions,
    hasTeams,
    needsTeamSwitcher,
    setSelectedTeam,
    setDefaultTeam,
    syncDefaultTeamFromProfile,
    resolveTeamScopedParams,
    loadTeams,
    loadTeamMembers,
    loadTeamMembersSafe,
    clearTeamMembers,
    loadTeamManagement,
    clearTeamManagement,
    createNewTeam,
    updateSelectedTeam,
    hasTeamPermission,
    searchUsers,
    inviteUser,
    leaveSelectedTeam,
    removeMember,
    acceptInvitation,
    rejectInvitation,
    publishAnnouncement,
    muteMember,
    unmuteMember,
    createInviteLink,
    submitJoinRequest,
    approveJoinRequest,
    rejectJoinRequest,
    refreshTeamPermissionCenter,
    handleTeamAccessRevoked,
  }
}
