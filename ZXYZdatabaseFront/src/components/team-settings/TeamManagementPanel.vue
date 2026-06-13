<template>
  <div>
    <div class="management-section">
      <h3>发布公告</h3>
      <p v-if="!canPublishAnnouncement" class="permission-tip">
        缺少 `team:announcement:publish` 权限，当前分区只读。
      </p>
      <el-input
        v-model="announcementTitle"
        maxlength="120"
        placeholder="公告标题"
        :disabled="!canPublishAnnouncement"
      />
      <el-input
        v-model="announcementContent"
        type="textarea"
        :rows="4"
        maxlength="5000"
        placeholder="公告内容"
        :disabled="!canPublishAnnouncement"
      />
      <el-button
        type="primary"
        :disabled="!canPublishAnnouncement"
        @click="$emit('publish-announcement')"
        >发布公告</el-button
      >
    </div>

    <div class="management-section">
      <h3>禁言</h3>
      <p v-if="!canManageMute" class="permission-tip">
        缺少 `team:mute:manage` 权限，当前分区只读。
      </p>
      <el-select v-model="muteUserId" placeholder="选择成员" :disabled="!canManageMute">
        <el-option
          v-for="member in teamMembers"
          :key="member.userId"
          :label="displayName(member)"
          :value="member.userId"
          :disabled="member.userId === currentUserId"
        />
      </el-select>
      <el-input v-model="muteReason" placeholder="禁言原因" :disabled="!canManageMute" />
      <el-button type="warning" :disabled="!canManageMute" @click="$emit('mute-member')"
        >禁言</el-button
      >
      <el-table :data="teamMutes" height="180">
        <el-table-column label="成员" min-width="120">
          <template #default="{ row }">{{ row.name || row.username || row.userId }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="120" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button
              size="small"
              :disabled="!canManageMute"
              @click="$emit('unmute-member', row.userId)"
              >解除</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  teamMembers: { type: Array, default: () => [] },
  teamMutes: { type: Array, default: () => [] },
  announcementForm: { type: Object, required: true },
  muteForm: { type: Object, required: true },
  currentUserId: { type: [Number, null], default: null },
  canPublishAnnouncement: { type: Boolean, default: false },
  canManageMute: { type: Boolean, default: false },
  displayName: { type: Function, required: true },
})

const emit = defineEmits([
  'publish-announcement',
  'mute-member',
  'unmute-member',
  'update:announcementForm',
  'update:muteForm',
])

const announcementTitle = computed({
  get: () => props.announcementForm.title,
  set: (val) => emit('update:announcementForm', { ...props.announcementForm, title: val }),
})

const announcementContent = computed({
  get: () => props.announcementForm.content,
  set: (val) => emit('update:announcementForm', { ...props.announcementForm, content: val }),
})

const muteUserId = computed({
  get: () => props.muteForm.userId,
  set: (val) => emit('update:muteForm', { ...props.muteForm, userId: val }),
})

const muteReason = computed({
  get: () => props.muteForm.reason,
  set: (val) => emit('update:muteForm', { ...props.muteForm, reason: val }),
})
</script>

<style scoped>
.management-section {
  display: grid;
  gap: 12px;
  margin-bottom: 18px;
}

.management-section h3 {
  margin: 0;
}

.permission-tip {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
</style>
