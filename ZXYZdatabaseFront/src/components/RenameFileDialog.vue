<template>
  <InputDialog
    :visible="visible"
    :title="dialogTitle"
    :placeholder="placeholder"
    :confirm-text="confirmText"
    :default-value="defaultValue"
    :submitting="submitting"
    :validator="validateName"
    @update:visible="handleVisibleChange"
    @submit="handleSubmit"
  />
</template>

<script setup>
import { computed } from 'vue'

import InputDialog from '@/components/InputDialog.vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  defaultValue: {
    type: String,
    default: '',
  },
  submitting: {
    type: Boolean,
    default: false,
  },
  targetType: {
    type: Number,
    default: 1,
  },
})

const emit = defineEmits(['submit', 'update:visible'])

const dialogTitle = computed(() => (props.targetType === 0 ? '重命名文件夹' : '重命名文件'))
const placeholder = computed(() => (props.targetType === 0 ? '请输入文件夹名称' : '请输入文件名称'))
const confirmText = '确认重命名'

function validateName(value) {
  const name = String(value || '').trim()

  if (!name) {
    return props.targetType === 0 ? '文件夹名称不能为空' : '文件名称不能为空'
  }

  if (name === '.' || name === '..') {
    return '名称不能为 . 或 ..'
  }

  if (name.includes('/') || name.includes('\\')) {
    return '名称不能包含 / 或 \\'
  }

  if (name.length > 100) {
    return '名称长度不能超过 100 个字符'
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
