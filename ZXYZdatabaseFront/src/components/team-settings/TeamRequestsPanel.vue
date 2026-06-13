<template>
  <div>
    <div class="management-section">
      <h3>邀请链接</h3>
      <p v-if="!canManageInviteLink" class="permission-tip">
        缺少 `team:invite-link:manage` 权限，当前分区只读。
      </p>
      <div class="inline-form">
        <el-input-number
          v-model="expireHours"
          :min="1"
          :max="720"
          :disabled="!canManageInviteLink"
        />
        <el-input-number v-model="maxUses" :min="0" :max="10000" :disabled="!canManageInviteLink" />
        <el-button
          type="primary"
          :disabled="!canManageInviteLink"
          @click="$emit('create-invite-link')"
          >生成链接</el-button
        >
      </div>
      <el-input v-if="inviteLink" :model-value="joinLinkText" readonly />
    </div>
    <p v-if="!canReviewJoinRequests" class="permission-tip">
      缺少 `team:join-request:review` 权限，当前审批操作已禁用。
    </p>
    <el-table :data="joinRequests" height="260">
      <el-table-column label="申请人" min-width="140">
        <template #default="{ row }">{{ row.name || row.username || row.userId }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="申请时间" min-width="160" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :disabled="!canReviewJoinRequests"
            @click="$emit('approve-join-request', row.id)"
            >通过</el-button
          >
          <el-button
            size="small"
            :disabled="!canReviewJoinRequests"
            @click="$emit('reject-join-request', row.id)"
            >拒绝</el-button
          >
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  joinRequests: { type: Array, default: () => [] },
  inviteLinkForm: { type: Object, required: true },
  inviteLink: { type: Object, default: null },
  joinLinkText: { type: String, default: '' },
  canManageInviteLink: { type: Boolean, default: false },
  canReviewJoinRequests: { type: Boolean, default: false },
})

const emit = defineEmits([
  'create-invite-link',
  'approve-join-request',
  'reject-join-request',
  'update:inviteLinkForm',
])

const expireHours = computed({
  get: () => props.inviteLinkForm.expireHours,
  set: (val) => emit('update:inviteLinkForm', { ...props.inviteLinkForm, expireHours: val }),
})

const maxUses = computed({
  get: () => props.inviteLinkForm.maxUses,
  set: (val) => emit('update:inviteLinkForm', { ...props.inviteLinkForm, maxUses: val }),
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
