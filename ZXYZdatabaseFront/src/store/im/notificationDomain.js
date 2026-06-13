import {
  fetchSystemNotificationUnreadCount,
  fetchSystemNotifications,
  markSystemNotificationRead,
} from '@/api/im'

export function createNotificationDomain(state, deps) {
  const { notifications, unreadCount } = state
  const { resolveTeamScopedParams } = deps

  async function loadUnreadCount(teamId) {
    const response = await fetchSystemNotificationUnreadCount(resolveTeamScopedParams(teamId))
    unreadCount.value = Number(response?.data?.unreadCount || 0)
    return unreadCount.value
  }

  async function loadNotifications(teamId) {
    const response = await fetchSystemNotifications({
      page: 1,
      pageSize: 50,
      ...resolveTeamScopedParams(teamId),
    })
    notifications.value = Array.isArray(response?.data) ? response.data : []
    await loadUnreadCount()
    return notifications.value
  }

  async function markRead(notificationId) {
    await markSystemNotificationRead(notificationId)
    await loadNotifications()
  }

  return {
    loadUnreadCount,
    loadNotifications,
    markRead,
  }
}
