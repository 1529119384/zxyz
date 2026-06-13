<template>
  <div class="settings-grid">
    <section class="settings-panel profile-panel">
      <div class="panel-title">
        <h2>基本信息</h2>
        <span>头像与昵称</span>
      </div>

      <div class="avatar-row">
        <el-avatar :size="72" :src="avatarSrc" />
        <div class="avatar-actions">
          <input
            ref="avatarInputRef"
            class="avatar-file-input"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            @change="handleAvatarFileChange"
          />
          <div class="avatar-action-row">
            <el-button
              :icon="Upload"
              :loading="uploadingAvatar"
              :disabled="savingProfile"
              @click="openAvatarPicker"
            >
              更换头像
            </el-button>
            <span v-if="uploadingAvatar" class="upload-progress"
              >上传中 {{ avatarUploadProgress }}%</span
            >
          </div>
          <p>{{ AVATAR_UPLOAD_TIP }}</p>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="昵称">
          <el-input
            v-model="profileForm.name"
            maxlength="64"
            show-word-limit
            placeholder="请输入昵称"
          />
        </el-form-item>
        <el-button type="primary" :loading="savingProfile" @click="saveProfile"
          >保存基本信息</el-button
        >
      </el-form>
    </section>

    <section class="settings-panel">
      <div class="panel-title">
        <h2>账户安全</h2>
        <span>修改登录密码</span>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="当前密码">
          <el-input
            v-model="passwordForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-button type="primary" :loading="savingPassword" @click="savePassword"
          >修改密码</el-button
        >
      </el-form>
    </section>

    <section class="settings-panel">
      <div class="panel-title">
        <h2>联系方式</h2>
        <span>邮箱验证码通过邮件发送</span>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="邮箱">
          <div class="inline-control">
            <el-input v-model="contactForm.email" placeholder="name@example.com" />
            <el-button :loading="savingEmail" @click="saveEmail">绑定邮箱</el-button>
          </div>
          <div class="verify-row">
            <el-tag
              :type="currentUserStore.profile?.emailVerified ? 'success' : 'info'"
              size="small"
            >
              {{ currentUserStore.profile?.emailVerified ? '已验证' : '未验证' }}
            </el-tag>
            <el-button
              size="small"
              :loading="emailCodeSending"
              :disabled="emailCodeButtonDisabled"
              @click="requestEmailCode"
            >
              {{ emailCodeButtonText }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="手机号">
          <div class="inline-control">
            <el-input v-model="contactForm.phone" placeholder="请输入手机号" />
            <el-button :loading="savingPhone" @click="savePhone">绑定手机</el-button>
          </div>
          <div class="verify-row">
            <el-tag
              :type="currentUserStore.profile?.phoneVerified ? 'success' : 'info'"
              size="small"
            >
              {{ currentUserStore.profile?.phoneVerified ? '已验证' : '未验证' }}
            </el-tag>
            <el-button size="small" @click="requestPhoneCode">获取验证码</el-button>
          </div>
        </el-form-item>
        <el-form-item label="验证码验证">
          <div class="inline-control">
            <el-select v-model="verifyForm.type">
              <el-option label="邮箱" value="email" />
              <el-option label="手机号" value="phone" />
            </el-select>
            <el-input v-model="verifyForm.code" placeholder="输入验证码" />
            <el-button type="primary" @click="verifyContactCode">验证</el-button>
          </div>
        </el-form-item>
      </el-form>
    </section>

    <section class="settings-panel">
      <div class="panel-title">
        <h2>团队偏好</h2>
        <span>登录后默认团队</span>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="默认团队">
          <el-select
            v-model="teamForm.defaultTeamId"
            clearable
            filterable
            placeholder="不设置默认团队"
            class="team-select"
          >
            <el-option
              v-for="team in teamStore.teams"
              :key="team.id"
              :label="team.name"
              :value="team.id"
            />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="savingDefaultTeam" @click="saveDefaultTeam"
          >保存团队偏好</el-button
        >
      </el-form>
    </section>
  </div>
</template>

<script setup>
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { setDefaultTeam } from '@/api/user'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'
import { useProfileForm } from '@/composables/useProfileForm'
import { usePasswordChange } from '@/composables/usePasswordChange'
import { useContactVerification } from '@/composables/useContactVerification'

const currentUserStore = useCurrentUserStore()
const teamStore = useTeamStore()
const savingDefaultTeam = ref(false)

// Shared reactive forms created at component level
const contactForm = reactive({ email: '', phone: '' })
const teamForm = reactive({ defaultTeamId: null })

const {
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
} = useProfileForm({ currentUserStore, teamStore, contactForm, teamForm })

const { savingPassword, passwordForm, savePassword } = usePasswordChange({ applyProfile })

const {
  savingEmail,
  savingPhone,
  emailCodeSending,
  emailCodeButtonDisabled,
  emailCodeButtonText,
  verifyForm,
  saveEmail,
  savePhone,
  requestEmailCode,
  requestPhoneCode,
  verifyContactCode,
} = useContactVerification({ currentUserStore, applyProfile, contactForm })

async function saveDefaultTeam() {
  const teamId = normalizePositiveId(teamForm.defaultTeamId)
  savingDefaultTeam.value = true
  try {
    const response = await setDefaultTeam({ teamId })
    applyProfile(response?.data || null)
    teamStore.setDefaultTeam(teamId)
    if (teamId) {
      teamStore.setSelectedTeam(teamId)
      await teamStore.loadTeamMembers(teamId)
    }
    ElMessage.success('团队偏好已保存')
  } catch (error) {
    handleBusinessError(error, '保存团队偏好失败')
  } finally {
    savingDefaultTeam.value = false
  }
}
</script>
