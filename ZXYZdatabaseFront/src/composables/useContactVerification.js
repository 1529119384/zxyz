import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'

import {
  createEmailVerificationCode,
  createPhoneVerificationCode,
  verifyContact,
} from '@/api/account'
import { bindEmail, bindPhone, fetchUserSettings } from '@/api/user'
import { handleBusinessError } from '@/utils/error'

const EMAIL_CODE_COOLDOWN_SECONDS = 60
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const MAINLAND_PHONE_PATTERN = /^1[3-9]\d{9}$/

function normalizeEmail(email) {
  return typeof email === 'string' ? email.trim() : ''
}

export function useContactVerification({
  currentUserStore,
  applyProfile,
  contactForm: externalContactForm,
}) {
  const savingEmail = ref(false)
  const savingPhone = ref(false)
  const emailCodeSending = ref(false)
  const emailCodeCountdown = ref(0)
  let emailCodeCountdownTimer = null

  const contactForm = externalContactForm || reactive({ email: '', phone: '' })
  const verifyForm = reactive({ type: 'email', code: '' })

  const emailCodeButtonText = computed(() =>
    emailCodeCountdown.value > 0 ? `${emailCodeCountdown.value}s 后重试` : '获取验证码',
  )
  const emailCodeButtonDisabled = computed(
    () =>
      emailCodeSending.value ||
      savingEmail.value ||
      emailCodeCountdown.value > 0 ||
      Boolean(currentUserStore.profile?.emailVerified),
  )

  onMounted(() => {
    loadSettings().catch((error) => handleBusinessError(error, '加载个人设置失败'))
  })

  onUnmounted(() => {
    clearEmailCodeCountdownTimer()
  })

  async function loadSettings() {
    const response = await fetchUserSettings()
    applyProfile(response?.data || null)
  }

  async function saveEmail() {
    const email = contactForm.email.trim()
    if (!EMAIL_PATTERN.test(email)) return ElMessage.warning('请输入正确的邮箱格式')
    const previousEmail = normalizeEmail(currentUserStore.profile?.email)
    savingEmail.value = true
    try {
      const response = await bindEmail({ email })
      applyProfile(response?.data || null)
      if (normalizeEmail(response?.data?.email) !== previousEmail) {
        resetEmailCodeCountdown()
      }
      ElMessage.success('邮箱已绑定，请获取验证码完成验证')
    } catch (error) {
      handleBusinessError(error, '绑定邮箱失败')
    } finally {
      savingEmail.value = false
    }
  }

  async function savePhone() {
    const phone = contactForm.phone.trim()
    if (!MAINLAND_PHONE_PATTERN.test(phone)) return ElMessage.warning('请输入正确的手机号')
    savingPhone.value = true
    try {
      const response = await bindPhone({ phone })
      applyProfile(response?.data || null)
      ElMessage.success('手机号已绑定，请获取开发验证码完成验证')
    } catch (error) {
      handleBusinessError(error, '绑定手机号失败')
    } finally {
      savingPhone.value = false
    }
  }

  async function requestEmailCode() {
    if (!validateEmailCodeRequest() || emailCodeSending.value || emailCodeCountdown.value > 0) {
      return
    }
    emailCodeSending.value = true
    try {
      await createEmailVerificationCode()
      verifyForm.type = 'email'
      verifyForm.code = ''
      startEmailCodeCountdown()
      ElMessage.success('验证码已发送至绑定邮箱')
    } catch (error) {
      handleBusinessError(error, '获取邮箱验证码失败')
    } finally {
      emailCodeSending.value = false
    }
  }

  async function requestPhoneCode() {
    try {
      const response = await createPhoneVerificationCode()
      verifyForm.type = 'phone'
      verifyForm.code = response?.data?.code || ''
      ElMessage.success(`开发验证码：${verifyForm.code}`)
    } catch (error) {
      handleBusinessError(error, '获取手机验证码失败')
    }
  }

  async function verifyContactCode() {
    try {
      const response = await verifyContact({ type: verifyForm.type, code: verifyForm.code.trim() })
      applyProfile(response?.data || null)
      if (verifyForm.type === 'email' && response?.data?.emailVerified) {
        resetEmailCodeCountdown()
      }
      verifyForm.code = ''
      ElMessage.success('联系方式已验证')
    } catch (error) {
      handleBusinessError(error, '验证联系方式失败')
    }
  }

  function validateEmailCodeRequest() {
    const formEmail = normalizeEmail(contactForm.email)
    const boundEmail = normalizeEmail(currentUserStore.profile?.email)
    if (!formEmail) {
      ElMessage.warning('请先填写邮箱')
      return false
    }
    if (!EMAIL_PATTERN.test(formEmail)) {
      ElMessage.warning('请输入正确的邮箱格式')
      return false
    }
    if (!boundEmail || formEmail !== boundEmail) {
      ElMessage.warning('请先保存邮箱后再获取验证码')
      return false
    }
    if (currentUserStore.profile?.emailVerified) {
      ElMessage.warning('邮箱已验证，无需重复获取验证码')
      return false
    }
    return true
  }

  function startEmailCodeCountdown() {
    clearEmailCodeCountdownTimer()
    emailCodeCountdown.value = EMAIL_CODE_COOLDOWN_SECONDS
    emailCodeCountdownTimer = window.setInterval(() => {
      if (emailCodeCountdown.value <= 1) {
        resetEmailCodeCountdown()
        return
      }
      emailCodeCountdown.value -= 1
    }, 1000)
  }

  function resetEmailCodeCountdown() {
    clearEmailCodeCountdownTimer()
    emailCodeCountdown.value = 0
  }

  function clearEmailCodeCountdownTimer() {
    if (!emailCodeCountdownTimer) return
    window.clearInterval(emailCodeCountdownTimer)
    emailCodeCountdownTimer = null
  }

  return {
    savingEmail,
    savingPhone,
    emailCodeSending,
    emailCodeButtonDisabled,
    emailCodeButtonText,
    contactForm,
    verifyForm,
    saveEmail,
    savePhone,
    requestEmailCode,
    requestPhoneCode,
    verifyContactCode,
  }
}
