import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { setDefaultTeam } from '@/api/user'
import { useCurrentUserStore } from '@/store/currentUser'
import { useSessionStore } from '@/store/session'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'
import { sanitizeRedirectPath } from '@/utils/sanitizeRedirect'

/**
 * 登录后引导 composable，管理团队选择对话框和登录后路由跳转逻辑。
 *
 * @returns {{ teams: Array, teamDialogVisible: import('vue').Ref<boolean>, pendingTeamId: import('vue').Ref<number|null>, setAsDefault: import('vue').Ref<boolean>, submitting: import('vue').Ref<boolean>, handlePostLoginSuccess: Function, confirmTeamSelect: Function, skipTeamSelect: Function }} 登录后引导状态与操作方法。
 */
export function usePostLoginGuide() {
  const router = useRouter()
  const route = useRoute()
  const currentUserStore = useCurrentUserStore()
  const teamStore = useTeamStore()
  const sessionStore = useSessionStore()
  const teamDialogVisible = ref(false)
  const pendingTeamId = ref(null)
  const setAsDefault = ref(true)
  const submitting = ref(false)

  async function handlePostLoginSuccess() {
    try {
      const session = await sessionStore.ensureSessionReady({ force: true })
      if (teamStore.teams.length >= 2 && !teamStore.defaultTeamId) {
        pendingTeamId.value = teamStore.teams[0]?.id || null
        setAsDefault.value = true
        teamDialogVisible.value = true
        return
      }
      if (session.shouldEnterNoTeam) {
        await router.replace({ name: 'noTeam' })
        return
      }
      await finishLoginRedirect()
    } catch (error) {
      handleBusinessError(error, '加载登录状态失败，请稍后重试')
    }
  }

  async function finishLoginRedirect() {
    const redirect = sanitizeRedirectPath(route.query.redirect)
    await router.replace(redirect)
  }

  async function confirmTeamSelect() {
    if (submitting.value) {
      return
    }

    const teamId = normalizePositiveId(pendingTeamId.value)
    if (!teamId) {
      return
    }

    submitting.value = true
    try {
      teamStore.setSelectedTeam(teamId)
      if (setAsDefault.value) {
        await persistDefaultTeam(teamId)
      }
      teamDialogVisible.value = false
    } catch (error) {
      handleBusinessError(error, '保存默认团队失败，请稍后重试')
      return
    } finally {
      submitting.value = false
    }

    await finishLoginRedirect()
  }

  async function skipTeamSelect() {
    if (submitting.value) {
      return
    }

    teamStore.setSelectedTeam(null)
    teamDialogVisible.value = false
    await finishLoginRedirect()
  }

  async function persistDefaultTeam(teamId) {
    const response = await setDefaultTeam({ teamId })
    const profile = response?.data || null
    // 后端 profile 是默认团队真源，保存成功后再同步本地缓存，避免下次初始化被旧 profile 覆盖。
    if (profile) {
      currentUserStore.setProfile(profile)
    }
    teamStore.setDefaultTeam(teamId)
  }

  return {
    teams: teamStore.teams,
    teamDialogVisible,
    pendingTeamId,
    setAsDefault,
    submitting,
    handlePostLoginSuccess,
    confirmTeamSelect,
    skipTeamSelect,
  }
}
