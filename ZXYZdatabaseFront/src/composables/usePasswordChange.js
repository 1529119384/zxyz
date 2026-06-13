import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { changePassword } from '@/api/user'
import { handleBusinessError } from '@/utils/error'

export function usePasswordChange({ applyProfile }) {
  const savingPassword = ref(false)
  const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

  async function savePassword() {
    const oldPassword = passwordForm.oldPassword.trim()
    const newPassword = passwordForm.newPassword.trim()
    const confirmPassword = passwordForm.confirmPassword.trim()
    if (!oldPassword || !newPassword) return ElMessage.warning('请填写当前密码和新密码')
    if (newPassword.length < 6) return ElMessage.warning('新密码不能少于 6 位')
    if (newPassword !== confirmPassword) return ElMessage.warning('两次输入的新密码不一致')
    savingPassword.value = true
    try {
      const response = await changePassword({ oldPassword, newPassword })
      applyProfile(response?.data || null)
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
      ElMessage.success('密码已修改')
    } catch (error) {
      handleBusinessError(error, '修改密码失败')
    } finally {
      savingPassword.value = false
    }
  }

  return {
    savingPassword,
    passwordForm,
    savePassword,
  }
}
