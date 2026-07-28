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

  const {
    clearActiveConversation = () => {},
    loadConversations = async () => [],
    loadNotifications = async () => [],
    emitter,
  } = deps

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

  async function loadTeamMembers(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    if (!normalizedTeamId) {
      clearTeamMembers()
      return []
    }
    selectedTeamId.value = normalizedTeamId
    const response = await fetchTeamMembers(normalizedTeamId)
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
    await Promise.all([loadTeams(), loadConversations()])
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
    selectedTeamId.value = null
    clearActiveConversation()
    await Promise.all([loadTeams(), loadConversations()])
  }

  async function removeMember(teamId, userId) {
    const normalizedTeamId = requireTeamId(teamId)
    await removeTeamMember(normalizedTeamId, userId)
    await Promise.all([
      loadTeamMembers(normalizedTeamId),
      loadTeamManagement(normalizedTeamId),
      loadConversations(),
    ])
  }

  async function acceptInvitation(invitationId) {
    const response = await acceptTeamInvitation(invitationId)
    await Promise.all([loadNotifications(), loadTeams(), loadConversations()])
    return response?.data
  }

  async function rejectInvitation(invitationId) {
    const response = await rejectTeamInvitation(invitationId)
    await loadNotifications()
    return response?.data
  }

  async function publishAnnouncement(teamId, payload) {
    const response = await publishTeamAnnouncement(requireTeamId(teamId), payload)
    await Promise.all([loadConversations(), loadNotifications()])
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
    const tasks = [loadTeams(), loadConversations()]
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
