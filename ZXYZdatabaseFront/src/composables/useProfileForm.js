import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'

import { updateUserSettings } from '@/api/user'
import { AVATAR_UPLOAD_TIP, uploadUserAvatar } from '@/services/avatarUpload'
import { defaultAvatarUrl } from '@/config/defaultAssets'
import { handleBusinessError } from '@/utils/error'
import { applyProfileToStores } from '@/utils/applyProfileHelper'

export function useProfileForm({ currentUserStore, teamStore, contactForm, teamForm }) {
  const savingProfile = ref(false)
  const uploadingAvatar = ref(false)
  const avatarUploadProgress = ref(0)
  const avatarInputRef = ref(null)
  const profileForm = reactive({ name: '' })

  const avatarSrc = computed(() => currentUserStore.profile?.avatar || defaultAvatarUrl)

  function applyProfile(profile) {
    applyProfileToStores(profile, {
      currentUserStore,
      teamStore,
      forms: { profileForm, contactForm, teamForm },
    })
  }

  function openAvatarPicker() {
    if (uploadingAvatar.value) return
    avatarInputRef.value?.click()
  }

  async function handleAvatarFileChange(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    uploadingAvatar.value = true
    avatarUploadProgress.value = 0
    try {
      const avatar = await uploadUserAvatar(file, (progress) => {
        const total = progress.total || file.size || 1
        avatarUploadProgress.value = Math.min(99, Math.round((progress.loaded / total) * 100))
      })
      avatarUploadProgress.value = 100
      const response = await updateUserSettings({
        name: profileForm.name,
        avatar,
      })
      applyProfile(response?.data || null)
      ElMessage.success('头像已更新')
    } catch (error) {
      handleBusinessError(error, '头像上传失败')
    } finally {
      uploadingAvatar.value = false
      avatarUploadProgress.value = 0
    }
  }

  async function saveProfile() {
    savingProfile.value = true
    try {
      const response = await updateUserSettings({ name: profileForm.name })
      applyProfile(response?.data || null)
      ElMessage.success('基本信息已保存')
    } catch (error) {
      handleBusinessError(error, '保存基本信息失败')
    } finally {
      savingProfile.value = false
    }
  }

  return {
    AVATAR_UPLOAD_TIP,
    savingProfile,
    uploadingAvatar,
    avatarUploadProgress,
    avatarInputRef,
    profileForm,
    avatarSrc,
    applyProfile,
    openAvatarPicker,
    handleAvatarFileChange,
    saveProfile,
  }
}
