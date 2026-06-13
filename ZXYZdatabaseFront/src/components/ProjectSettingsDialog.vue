<template>
  <el-dialog
    :model-value="visible"
    title="项目配置"
    width="460px"
    destroy-on-close
    @update:model-value="emitVisibleChange"
  >
    <el-form label-position="top" @submit.prevent>
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
        <small class="quota-tip">当前已使用 {{ formatStorageSize(form.usedStorage) }}</small>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="emitVisibleChange(false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="emit('submit')">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { formatSize as formatStorageSize } from '@/utils/format'

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
})

const emit = defineEmits(['update:visible', 'update:form', 'submit'])

function emitVisibleChange(visible) {
  emit('update:visible', visible)
}

function updateFormField(field, value) {
  emit('update:form', {
    ...props.form,
    [field]: value,
  })
}
</script>

<style scoped>
.quota-unit {
  margin-left: 8px;
}

.quota-tip {
  display: block;
  margin-top: 8px;
  color: #909399;
}
</style>
