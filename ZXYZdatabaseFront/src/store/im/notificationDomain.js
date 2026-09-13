// @ts-check
import {
  fetchSystemNotificationUnreadCount,
  fetchSystemNotifications,
  markSystemNotificationRead,
} from '@/api/im'

/**
 * 通知域的 state 切片（由 chat.js 组装后传入）。
 * @typedef {object} NotificationDomainState
 * @property {import('vue').Ref<any[]>} notifications 系统通知列表；本域只做整表替换，行结构由后端决定
 * @property {import('vue').Ref<number>} unreadCount
 */

/**
 * 通知域的依赖（由 chat.js 延迟绑定注入）。
 * @typedef {object} NotificationDomainDeps
 * @property {(teamId?: number|string|null) => Record<string, unknown>} resolveTeamScopedParams
 */

/**
 * @param {NotificationDomainState} state
 * @param {NotificationDomainDeps} deps
 */
export function createNotificationDomain(state, deps) {
  const { notifications, unreadCount } = state
  const { resolveTeamScopedParams } = deps

  /** @param {number|string|null} [teamId] 缺省时由 resolveTeamScopedParams 决定作用域 */
  async function loadUnreadCount(teamId) {
    const response = await fetchSystemNotificationUnreadCount(resolveTeamScopedParams(teamId))
    unreadCount.value = Number(response?.data?.unreadCount || 0)
    return unreadCount.value
  }

  /** @param {number|string|null} [teamId] 缺省时由 resolveTeamScopedParams 决定作用域 */
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

  /** @param {string|number} notificationId */
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
