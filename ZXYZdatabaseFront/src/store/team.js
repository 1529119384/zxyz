import { readonly, ref } from 'vue'
import { defineStore } from 'pinia'
import { useLocalStorage } from '@vueuse/core'

import { createEventEmitter } from '@/utils/eventEmitter'
import { normalizePositiveId } from '@/utils/id'

import { DEFAULT_TEAM_ID_KEY } from './im/normalizers'
import { createPermissionDomain } from './im/permissionDomain'
import { createTeamDomain } from './im/teamDomain'

export const useTeamStore = defineStore('team', () => {
  const teams = ref([])
  // selectedTeamId 是团队上下文的唯一真源；聊天 Store 只能读取，不直接拥有团队选择状态。
  const selectedTeamId = ref(null)
  const defaultTeamId = useLocalStorage(DEFAULT_TEAM_ID_KEY, null, {
    serializer: {
      read: (raw) => {
        if (raw == null) return null
        try {
          return normalizePositiveId(JSON.parse(raw))
        } catch {
          return null
        }
      },
      write: (value) => JSON.stringify(value),
    },
  })
  const teamMembers = ref([])
  const teamMutes = ref([])
  const joinRequests = ref([])
  const inviteLink = ref(null)
  const userSearchResults = ref([])
  const teamPermissions = ref([])
  const teamRoles = ref([])
  const teamPermissionAudit = ref([])

  const state = {
    teams,
    selectedTeamId,
    defaultTeamId,
    teamMembers,
    teamMutes,
    joinRequests,
    inviteLink,
    userSearchResults,
    teamPermissions,
    teamRoles,
    teamPermissionAudit,
  }

  // 事件总线用于 teamDomain ↔ permissionDomain 解耦：
  // 两个 domain 不再直接调用对方方法，而是通过事件间接通信。
  const emitter = createEventEmitter()

  const permissionDomain = createPermissionDomain(state, { emitter })
  const teamDomain = createTeamDomain(state, { emitter })

  // permissionDomain 发出的事件 → teamDomain 执行刷新
  emitter.on('teamMembersNeedReload', (teamId) => {
    teamDomain.loadTeamMembersSafe(teamId)
  })
  emitter.on('teamsNeedReload', () => {
    teamDomain.loadTeams()
  })

  // teamDomain 发出的事件 → permissionDomain 执行刷新
  emitter.on('permissionCenterNeedsReload', (teamId) => {
    permissionDomain.loadTeamPermissionCenter(teamId)
  })

  return {
    teams: readonly(teams),
    selectedTeamId: readonly(selectedTeamId),
    defaultTeamId: readonly(defaultTeamId),
    teamMembers: readonly(teamMembers),
    teamMutes: readonly(teamMutes),
    joinRequests: readonly(joinRequests),
    inviteLink: readonly(inviteLink),
    userSearchResults: readonly(userSearchResults),
    teamPermissions: readonly(teamPermissions),
    teamRoles: readonly(teamRoles),
    teamPermissionAudit: readonly(teamPermissionAudit),
    selectedTeam: teamDomain.selectedTeam,
    currentTeamPermissions: teamDomain.currentTeamPermissions,
    hasTeams: teamDomain.hasTeams,
    needsTeamSwitcher: teamDomain.needsTeamSwitcher,
    setSelectedTeam: teamDomain.setSelectedTeam,
    setDefaultTeam: teamDomain.setDefaultTeam,
    syncDefaultTeamFromProfile: teamDomain.syncDefaultTeamFromProfile,
    resolveTeamScopedParams: teamDomain.resolveTeamScopedParams,
    loadTeams: teamDomain.loadTeams,
    loadTeamMembers: teamDomain.loadTeamMembers,
    loadTeamMembersSafe: teamDomain.loadTeamMembersSafe,
    clearTeamMembers: teamDomain.clearTeamMembers,
    loadTeamManagement: teamDomain.loadTeamManagement,
    clearTeamManagement: teamDomain.clearTeamManagement,
    createNewTeam: teamDomain.createNewTeam,
    updateSelectedTeam: teamDomain.updateSelectedTeam,
    hasTeamPermission: teamDomain.hasTeamPermission,
    searchUsers: teamDomain.searchUsers,
    inviteUser: teamDomain.inviteUser,
    leaveSelectedTeam: teamDomain.leaveSelectedTeam,
    removeMember: teamDomain.removeMember,
    acceptInvitation: teamDomain.acceptInvitation,
    rejectInvitation: teamDomain.rejectInvitation,
    publishAnnouncement: teamDomain.publishAnnouncement,
    muteMember: teamDomain.muteMember,
    unmuteMember: teamDomain.unmuteMember,
    createInviteLink: teamDomain.createInviteLink,
    submitJoinRequest: teamDomain.submitJoinRequest,
    approveJoinRequest: teamDomain.approveJoinRequest,
    rejectJoinRequest: teamDomain.rejectJoinRequest,
    refreshTeamPermissionCenter: teamDomain.refreshTeamPermissionCenter,
    handleTeamAccessRevoked: teamDomain.handleTeamAccessRevoked,
    clearTeamPermissionCenter: permissionDomain.clearTeamPermissionCenter,
    loadTeamPermissionCenter: permissionDomain.loadTeamPermissionCenter,
    saveTeamRole: permissionDomain.saveTeamRole,
    removeTeamRole: permissionDomain.removeTeamRole,
    updateTeamRolePermissions: permissionDomain.updateTeamRolePermissions,
    updateTeamMemberRole: permissionDomain.updateTeamMemberRole,
  }
})
