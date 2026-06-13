<template>
  <InputDialog
    :visible="visible"
    title="打包下载"
    placeholder="请输入压缩包名称"
    confirm-text="开始打包"
    :default-value="defaultValue"
    :submitting="submitting"
    :validator="validateArchiveName"
    @update:visible="handleVisibleChange"
    @submit="handleSubmit"
  />
</template>

<script setup>
import InputDialog from '@/components/InputDialog.vue'

defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  defaultValue: {
    type: String,
    default: 'archive',
  },
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['submit', 'update:visible'])

function validateArchiveName(value) {
  return value ? true : '压缩包名称不能为空'
}

function handleSubmit(value) {
  emit('submit', value)
}

function handleVisibleChange(value) {
  emit('update:visible', value)
}
</script>

<style></style>
