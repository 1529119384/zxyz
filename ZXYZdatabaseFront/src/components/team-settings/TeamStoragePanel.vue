<template>
  <div>
    <p v-if="!canAllocateStorage" class="permission-tip">
      缺少 `team:storage:allocate` 权限，只能查看成员存储用量。
    </p>
    <el-table v-loading="loading" :data="memberStorageList" height="300">
      <el-table-column label="成员" min-width="160">
        <template #default="{ row }">
          <div class="member-cell">
            <el-avatar :size="28" :src="row.avatar">{{ displayName(row).slice(0, 1) }}</el-avatar>
            <span>{{ displayName(row) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="100">
        <template #default="{ row }">{{ roleText(row.roleCode) }}</template>
      </el-table-column>
      <el-table-column label="个人存储用量" min-width="140">
        <template #default="{ row }">
          {{ formatSize(row.personalStorageUsed) }}
        </template>
      </el-table-column>
      <el-table-column label="个人存储限额 (GB)" min-width="160">
        <template #default="{ row }">
          <el-input-number
            :model-value="getStorageLimit(row.userId)"
            :min="0"
            :max="1048576"
            :disabled="!canAllocateStorage"
            controls-position="right"
            size="small"
            :value-on-clear="0"
            @update:model-value="setStorageLimit(row.userId, $event)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :loading="saving"
            :disabled="!canAllocateStorage"
            @click="$emit('save-member-limit', row)"
          >
            保存
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { formatSize } from '@/utils/format'

const props = defineProps({
  memberStorageList: { type: Array, default: () => [] },
  storageLimitForms: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  canAllocateStorage: { type: Boolean, default: false },
  roleText: { type: Function, required: true },
  displayName: { type: Function, required: true },
})

const emit = defineEmits(['save-member-limit', 'update:storageLimitForms'])

function getStorageLimit(userId) {
  return props.storageLimitForms[userId]
}

function setStorageLimit(userId, value) {
  emit('update:storageLimitForms', { ...props.storageLimitForms, [userId]: value })
}
</script>

<style scoped>
.member-cell {
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
