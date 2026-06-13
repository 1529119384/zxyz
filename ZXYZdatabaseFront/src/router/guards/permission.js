import { TEAM_PERMISSION_CENTER_CODES } from '@/constants/teamPermissions'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { normalizePositiveId } from '@/utils/id'

function resolvePermissionCenterTeamId(to, navigation) {
  return (
    normalizePositiveId(to?.query?.teamId) ||
    normalizePositiveId(navigation.selectedTeamId) ||
    normalizePositiveId(navigation.teams[0]?.id)
  )
}

function canAccessTeamPermissionCenter(to) {
  const teamStore = useTeamStore()
  const teamId = resolvePermissionCenterTeamId(to, teamStore)
  return (
    Boolean(teamId) &&
    TEAM_PERMISSION_CENTER_CODES.some((code) => teamStore.hasTeamPermission(teamId, code))
  )
}

export function requireSystemAdminRole() {
  return () => {
    const currentUserStore = useCurrentUserStore()
    return currentUserStore.isAdmin ? true : { name: 'accountSettings' }
  }
}

export function requirePermissionCenter(to) {
  const currentUserStore = useCurrentUserStore()
  // 权限中心允许系统权限或当前团队权限进入，路由层只消费这个统一结果。
  return currentUserStore.canReadSystemPermissionCenter || canAccessTeamPermissionCenter(to)
    ? true
    : { name: 'accountSettings' }
}
