<template>
  <el-dialog
    :model-value="visible"
    :title="canManageProjects ? '新建项目组' : '申请项目组'"
    width="520px"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="emitVisibleChange"
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="项目名称">
        <el-input
          :model-value="form.name"
          maxlength="80"
          placeholder="请输入项目名称"
          @update:model-value="updateFormField('name', $event)"
        />
      </el-form-item>
      <el-form-item label="说明">
        <el-input
          :model-value="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          placeholder="可选"
          @update:model-value="updateFormField('description', $event)"
        />
      </el-form-item>
      <el-form-item label="项目负责人">
        <el-select
          :model-value="form.leaderUserId"
          filterable
          placeholder="请选择负责人"
          style="width: 100%"
          @update:model-value="updateFormField('leaderUserId', $event)"
        >
          <el-option
            v-for="member in teamMembers"
            :key="member.userId"
            :label="memberLabel(member)"
            :value="member.userId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="项目空间配额">
        <el-checkbox
          :model-value="form.unlimited"
          @update:model-value="updateFormField('unlimited', $event)"
        >
          无限
        </el-checkbox>
        <el-input-number
          v-if="!form.unlimited"
          :model-value="form.storageLimitGb"
          :min="1"
          :precision="0"
          controls-position="right"
          @update:model-value="updateFormField('storageLimitGb', $event)"
        />
        <span v-if="!form.unlimited" class="quota-unit">GB</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="emitVisibleChange(false)">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">
        {{ canManageProjects ? '创建' : '提交申请' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  form: {
    type: Object,
    required: true,
  },
  submitting: {
    type: Boolean,
    default: false,
  },
  canManageProjects: {
    type: Boolean,
    default: false,
  },
  teamMembers: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['update:visible', 'update:form', 'submit'])

function emitVisibleChange(visible) {
  if (!visible && props.submitting) {
    return
  }

  emit('update:visible', visible)
}

function updateFormField(field, value) {
  emit('update:form', {
    ...props.form,
    [field]: value,
  })
}

function memberLabel(member = {}) {
  return member.name || member.username || `用户 ${member.userId}`
}

function submit() {
  if (props.submitting) {
    return
  }

  emit('submit')
}
</script>

<style scoped>
.quota-unit {
  margin-left: 8px;
}
</style>
