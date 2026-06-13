import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { fetchPublicShareInfo, verifySharePassword } from '@/api/share'
import { sanitizeSharePassword } from '@/models/share'
import { getErrorMessage } from '@/utils/error'

function getStatusMessage(error) {
  const statusText = error?.data?.statusText
  if (statusText) {
    return statusText
  }

  return getErrorMessage(error, '分享暂时不可访问')
}

/**
 * 公开分享页访问 composable，管理分享信息加载、提取码验证和页面状态。
 *
 * @returns {{ shareInfo: import('vue').Ref<Object|null>, pageLoading: import('vue').Ref<boolean>, verifying: import('vue').Ref<boolean>, submitPassword: import('vue').Ref<string>, pageError: import('vue').Ref<string>, canViewContent: import('vue').ComputedRef<boolean>, passByPassword: Function }} 分享页访问状态与操作方法。
 */
export function useShareVisit() {
  const route = useRoute()
  const shareInfo = ref(null)
  const pageLoading = ref(false)
  const verifying = ref(false)
  const submitPassword = ref('')
  const pageError = ref('')
  const autoVerifyKey = ref('')

  const shareKey = computed(() => String(route.params.shareKey || ''))
  const autoFillPassword = computed(() => sanitizeSharePassword(route.query.psw || ''))
  const canViewContent = computed(() => Boolean(shareInfo.value?.canViewContent))

  async function passByPassword(password, silent = false) {
    const normalizedPassword = sanitizeSharePassword(password)
    if (!normalizedPassword) {
      return false
    }

    verifying.value = true

    try {
      await verifySharePassword(shareKey.value, normalizedPassword)

      shareInfo.value = {
        ...(shareInfo.value || {}),
        passed: true,
        canViewContent: true,
      }
      submitPassword.value = normalizedPassword
      pageError.value = ''
      return true
    } catch (error) {
      if (!silent) {
        ElMessage.error(getErrorMessage(error, '提取码错误'))
      }
      return false
    } finally {
      verifying.value = false
    }
  }

  async function refreshPage() {
    if (!shareKey.value) {
      pageError.value = '分享不存在'
      shareInfo.value = null
      return
    }

    pageLoading.value = true
    pageError.value = ''

    try {
      const response = await fetchPublicShareInfo(shareKey.value)
      shareInfo.value = response?.data || null

      if (shareInfo.value?.canViewContent) {
        return
      }

      const verifyToken = `${shareKey.value}:${autoFillPassword.value}`
      if (
        shareInfo.value?.needPassword &&
        autoFillPassword.value &&
        autoVerifyKey.value !== verifyToken
      ) {
        autoVerifyKey.value = verifyToken
        await passByPassword(autoFillPassword.value, true)
      }
    } catch (error) {
      shareInfo.value = null
      pageError.value = getStatusMessage(error)
    } finally {
      pageLoading.value = false
    }
  }

  watch(
    () => [shareKey.value, autoFillPassword.value].join('|'),
    () => {
      refreshPage()
    },
    { immediate: true },
  )

  return {
    shareInfo,
    pageLoading,
    verifying,
    submitPassword,
    pageError,
    canViewContent,
    passByPassword,
  }
}
