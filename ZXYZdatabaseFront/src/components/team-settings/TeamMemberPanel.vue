<template>
  <div>
    <div class="section-toolbar">
      <el-button :disabled="!canInviteMember" @click="$emit('show-invite-dialog')"
        >邀请成员</el-button
      >
      <el-button plain :disabled="!canAssignRole" @click="$emit('open-permission-center')"
        >分配角色</el-button
      >
      <el-button type="danger" plain @click="$emit('leave-team')">退出团队</el-button>
    </div>
    <section class="management-section member-create-section">
      <h3>创建成员账号</h3>
      <p v-if="!canCreateMember" class="permission-tip">
        缺少 `team:member:create` 权限，不能创建成员账号。
      </p>
      <div class="member-create-grid">
        <el-input
          v-model="memberUsername"
          maxlength="64"
          placeholder="成员用户名"
          :disabled="!canCreateMember"
        />
        <el-input
          v-model="memberPassword"
          type="password"
          show-password
          placeholder="初始密码"
          :disabled="!canCreateMember"
        />
        <el-input
          v-model="memberName"
          maxlength="64"
          placeholder="成员昵称（可选）"
          :disabled="!canCreateMember"
        />
        <el-select v-model="memberRoleCode" :disabled="!canCreateMember">
          <el-option label="成员" value="team_member" />
          <el-option label="管理员" value="team_admin" />
        </el-select>
        <el-button
          type="primary"
          :loading="creatingMember"
          :disabled="!canCreateMember"
          @click="$emit('create-member')"
          >创建账号</el-button
        >
      </div>
    </section>
    <p
      v-if="!canCreateMember && !canInviteMember && !canAssignRole && !canRemoveMember"
      class="permission-tip"
    >
      当前成员页保留查看、私聊和退出团队能力，成员管理操作已禁用。
    </p>
    <el-table :data="teamMembers" height="280">
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
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="row.userId === currentUserId"
            @click="$emit('start-direct-chat', row.userId)"
            >私聊</el-button
          >
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="row.userId === currentUserId || !canRemoveMember"
            @click="$emit('remove-member', row.userId)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  teamMembers: { type: Array, default: () => [] },
  memberForm: { type: Object, required: true },
  creatingMember: { type: Boolean, default: false },
  currentUserId: { type: [Number, null], default: null },
  canCreateMember: { type: Boolean, default: false },
  canInviteMember: { type: Boolean, default: false },
  canAssignRole: { type: Boolean, default: false },
  canRemoveMember: { type: Boolean, default: false },
  roleText: { type: Function, required: true },
  displayName: { type: Function, required: true },
})

const emit = defineEmits([
  'show-invite-dialog',
  'open-permission-center',
  'leave-team',
  'create-member',
  'start-direct-chat',
  'remove-member',
  'update:memberForm',
])

const memberUsername = computed({
  get: () => props.memberForm.username,
  set: (val) => emit('update:memberForm', { ...props.memberForm, username: val }),
})

const memberPassword = computed({
  get: () => props.memberForm.password,
  set: (val) => emit('update:memberForm', { ...props.memberForm, password: val }),
})

const memberName = computed({
  get: () => props.memberForm.name,
  set: (val) => emit('update:memberForm', { ...props.memberForm, name: val }),
})

const memberRoleCode = computed({
  get: () => props.memberForm.roleCode,
  set: (val) => emit('update:memberForm', { ...props.memberForm, roleCode: val }),
})
</script>

<style scoped>
.section-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.management-section {
  display: grid;
  gap: 12px;
  margin-bottom: 18px;
}

.management-section h3 {
  margin: 0;
}

.member-create-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
  gap: 10px;
  align-items: center;
}

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
