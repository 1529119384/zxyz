import { reactive, ref } from 'vue'

import { useCurrentUserStore } from '@/store/currentUser'
import { handleBusinessError } from '@/utils/error'

/**
 * 登录表单 composable，管理表单状态、校验规则和登录提交逻辑。
 *
 * @returns {{ loginForm: Object, loginFormRef: import('vue').Ref<Object|undefined>, loginRules: Object, loggingIn: import('vue').Ref<boolean>, submitLogin: Function, handleKeydown: Function }} 登录表单状态与操作方法。
 */
export function useLoginForm() {
  const currentUserStore = useCurrentUserStore()
  const loginFormRef = ref()
  const loggingIn = ref(false)
  const loginForm = reactive({
    username: '',
    password: '',
    rememberMe: false,
  })

  const loginRules = {
    username: [
      {
        required: true,
        message: '请输入用户名',
        trigger: 'blur',
      },
    ],
    password: [
      {
        required: true,
        message: '请输入密码',
        trigger: 'blur',
      },
    ],
  }

  async function submitLogin() {
    if (loggingIn.value) {
      return null
    }

    const isValid = await loginFormRef.value
      ?.validate()
      .then(() => true)
      .catch(() => false)

    if (!isValid) {
      return null
    }

    loggingIn.value = true

    try {
      const payload = {
        username: loginForm.username.trim(),
        password: loginForm.password,
        rememberMe: loginForm.rememberMe,
      }

      // 先在表单层完成校验，再进入 store 的登录 action，避免无效请求打到后端。
      return await currentUserStore.login(payload)
    } catch (error) {
      handleBusinessError(error, '登录失败，请稍后重试')
      return null
    } finally {
      loggingIn.value = false
    }
  }

  async function handleKeydown(event) {
    if (event.key !== 'Enter') {
      return null
    }

    // 回车提交属于表单交互规则，放在 composable 中能和校验、提交态一起维护，避免页面层重复拼装。
    return submitLogin()
  }

  return {
    loginForm,
    loginFormRef,
    loginRules,
    loggingIn,
    submitLogin,
    handleKeydown,
  }
}
