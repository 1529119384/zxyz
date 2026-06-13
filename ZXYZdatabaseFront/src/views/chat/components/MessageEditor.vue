<template>
  <footer v-if="!isReadonly" class="chat-editor">
    <el-input
      :model-value="modelValue"
      type="textarea"
      :autosize="{ minRows: 2, maxRows: 4 }"
      :maxlength="5000"
      resize="none"
      placeholder="输入聊天消息，Enter 发送，Shift + Enter 换行"
      @update:model-value="emit('update:modelValue', $event)"
      @keydown.enter.exact.prevent="emit('submit')"
    />
    <div class="editor-actions">
      <span>{{ modelValue.length }}/5000</span>
      <el-button type="primary" :disabled="sendingDisabled" @click="emit('submit')">发送</el-button>
    </div>
  </footer>
  <footer v-else class="system-readonly-tip">{{ readonlyTip }}</footer>
</template>

<script setup>
defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  sendingDisabled: {
    type: Boolean,
    default: true,
  },
  isReadonly: {
    type: Boolean,
    default: false,
  },
  readonlyTip: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:modelValue', 'submit'])
</script>

<style scoped>
.chat-editor {
  padding: 14px 18px;
  border-top: 1px solid #e4e7ed;
  display: grid;
  gap: 10px;
}

.system-readonly-tip {
  padding: 12px 18px;
  border-top: 1px solid #e4e7ed;
  color: #909399;
  font-size: 13px;
  text-align: center;
}

.editor-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: space-between;
  color: #909399;
}
</style>
