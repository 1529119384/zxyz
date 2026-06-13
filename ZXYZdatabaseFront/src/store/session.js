import { defineStore } from 'pinia'
import { ref } from 'vue'

import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'

let pendingSessionReady = null

export const useSessionStore = defineStore('session', () => {
  const bootstrapped = ref(false)
  let sessionVersion = 0

  async function ensureSessionReady(options = {}) {
    const { force = false } = options

    if (force) {
      resetSessionBootstrap()
    }

    if (bootstrapped.value) {
      return createSessionSnapshot()
    }

    if (pendingSessionReady) {
      return pendingSessionReady
    }

    const currentVersion = sessionVersion
    const request = loadSession(options)
      .then((session) => {
        // 只有最新一轮初始化可以标记完成，避免账号切换时旧请求回写状态。
        if (sessionVersion === currentVersion) {
          bootstrapped.value = true
        }
        return session
      })
      .finally(() => {
        if (pendingSessionReady === request) {
          pendingSessionReady = null
        }
      })
    pendingSessionReady = request
    return pendingSessionReady
  }

  async function loadSession(options = {}) {
    const currentUserStore = useCurrentUserStore()
    const teamStore = useTeamStore()

    // 会话初始化时始终从 API 获取完整 profile，因为 localStorage 中仅缓存显示层数据
    const profile = await currentUserStore.loadProfile()
    const teams = await teamStore.loadTeams()

    return createSessionSnapshot({ profile, teams })
  }

  function createSessionSnapshot(overrides = {}) {
    const currentUserStore = useCurrentUserStore()
    const teamStore = useTeamStore()
    const profile = Object.prototype.hasOwnProperty.call(overrides, 'profile')
      ? overrides.profile
      : currentUserStore.profile
    const teams = Object.prototype.hasOwnProperty.call(overrides, 'teams')
      ? overrides.teams
      : teamStore.teams
    const normalizedTeams = Array.isArray(teams) ? teams : []
    const canCreateTeam = currentUserStore.isAdmin

    return {
      profile,
      teams: normalizedTeams,
      canCreateTeam,
      hasTeams: normalizedTeams.length > 0,
      shouldEnterNoTeam: !normalizedTeams.length && !canCreateTeam,
    }
  }

  function resetSessionBootstrap() {
    pendingSessionReady = null
    bootstrapped.value = false
    sessionVersion += 1
  }

  return {
    ensureSessionReady,
    resetSessionBootstrap,
  }
})
