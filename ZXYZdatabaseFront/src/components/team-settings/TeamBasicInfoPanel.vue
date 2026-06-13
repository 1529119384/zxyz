<template>
  <section class="team-setting">
    <div class="team-profile">
      <div class="team-avatar-control">
        <el-avatar :size="56" :src="profileForm.avatar || selectedTeam?.avatar">
          {{ selectedTeam?.name?.slice(0, 1) || '团' }}
        </el-avatar>
        <el-button
          size="small"
          :icon="Upload"
          :loading="uploadingTeamAvatar"
          :disabled="!canUpdateTeam || savingProfile"
          @click="$emit('open-team-avatar-picker')"
        >
          更换
        </el-button>
      </div>
      <div class="team-profile__main">
        <el-input
          v-model="profileName"
          maxlength="50"
          placeholder="团队名称"
          :disabled="!canUpdateTeam"
        />
        <el-input
          v-model="profileDescription"
          type="textarea"
          :rows="3"
          maxlength="500"
          placeholder="团队描述"
          :disabled="!canUpdateTeam"
        />
        <p class="avatar-tip">{{ AVATAR_UPLOAD_TIP }}</p>
        <span v-if="uploadingTeamAvatar" class="upload-progress"
          >头像上传中 {{ teamAvatarUploadProgress }}%</span
        >
        <p v-if="!canUpdateTeam" class="permission-tip">
          缺少 `team:update` 权限，当前只能查看团队资料。
        </p>
        <div class="inline-form">
          <el-button
            type="primary"
            :loading="savingProfile"
            :disabled="!canUpdateTeam"
            @click="$emit('save-profile')"
            >保存资料</el-button
          >
          <el-button
            plain
            :disabled="!canAccessPermissionCenter"
            @click="$emit('open-permission-center')"
            >权限管理</el-button
          >
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { Upload } from '@element-plus/icons-vue'

import { AVATAR_UPLOAD_TIP } from '@/services/avatarUpload'

const props = defineProps({
  selectedTeam: { type: Object, default: null },
  profileForm: { type: Object, required: true },
  canUpdateTeam: { type: Boolean, default: false },
  canAccessPermissionCenter: { type: Boolean, default: false },
  savingProfile: { type: Boolean, default: false },
  uploadingTeamAvatar: { type: Boolean, default: false },
  teamAvatarUploadProgress: { type: Number, default: 0 },
})

const emit = defineEmits([
  'save-profile',
  'open-permission-center',
  'open-team-avatar-picker',
  'update:profileForm',
])

const profileName = computed({
  get: () => props.profileForm.name,
  set: (val) => emit('update:profileForm', { ...props.profileForm, name: val }),
})

const profileDescription = computed({
  get: () => props.profileForm.description,
  set: (val) => emit('update:profileForm', { ...props.profileForm, description: val }),
})
</script>

<style scoped>
.team-setting,
.team-profile,
.team-profile__main {
  display: grid;
  gap: 12px;
}

.team-profile {
  grid-template-columns: 80px minmax(0, 1fr);
  align-items: start;
  margin-bottom: 12px;
}

.team-avatar-control {
  display: grid;
  justify-items: center;
  gap: 8px;
}

.avatar-tip,
.upload-progress {
  margin: 0;
  color: #909399;
  font-size: 12px;
}

.upload-progress {
  color: #409eff;
}

.inline-form {
  display: flex;
  align-items: center;
  gap: 10px;
}

.permission-tip {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
</style>
