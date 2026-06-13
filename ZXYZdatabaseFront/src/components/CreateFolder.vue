<template>
  <InputDialog
    :visible="visible"
    title="新建文件夹"
    placeholder="请输入文件夹名称"
    confirm-text="创建文件夹"
    :default-value="defaultValue"
    :submitting="submitting"
    :validator="validateFolderName"
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
    default: '新建文件夹',
  },
  submitting: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['submit', 'update:visible'])

function validateFolderName(value) {
  const name = String(value || '').trim()

  if (!name) {
    return '文件夹名称不能为空'
  }

  if (name === '.' || name === '..') {
    return '文件夹名称不能为 . 或 ..'
  }

  if (name.includes('/') || name.includes('\\')) {
    return '文件夹名称不能包含 / 或 \\'
  }

  if (name.length > 100) {
    return '文件夹名称长度不能超过 100 个字符'
  }

  return true
}

function handleSubmit(value) {
  emit('submit', value)
}

function handleVisibleChange(value) {
  emit('update:visible', value)
}
</script>

<style></style>
