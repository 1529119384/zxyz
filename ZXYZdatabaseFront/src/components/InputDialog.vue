<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="300"
    center
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @update:model-value="handleVisibleChange"
  >
    <el-input
      v-model="inputValue"
      focusable
      clearable
      style="width: 240px"
      :placeholder="placeholder"
      @keyup.enter="handleSubmit"
    />

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="close"> 取消 </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="submitting"
          @click="handleSubmit"
        >
          {{ confirmText }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  title: {
    type: String,
    required: true,
  },
  placeholder: {
    type: String,
    required: true,
  },
  confirmText: {
    type: String,
    required: true,
  },
  defaultValue: {
    type: String,
    default: '',
  },
  submitting: {
    type: Boolean,
    default: false,
  },
  validator: {
    type: Function,
    default: null,
  },
})

const emit = defineEmits(['submit', 'update:visible'])

const inputValue = ref(props.defaultValue)

watch(
  () => props.visible,
  (visible, previousVisible) => {
    // 每次重新打开弹窗时都重置输入值，避免上一次交互的内容残留。
    if (visible && !previousVisible) {
      inputValue.value = props.defaultValue
    }
  },
)

function getValidationMessage(value) {
  if (!props.validator) {
    return value ? '' : '输入内容不能为空'
  }

  const validateResult = props.validator(value)

  if (validateResult === true || validateResult === undefined || validateResult === null) {
    return ''
  }

  if (validateResult === false) {
    return '输入不合法'
  }

  return String(validateResult)
}

function handleSubmit() {
  if (props.submitting) {
    return
  }

  const normalizedValue = inputValue.value.trim()
  const validationMessage = getValidationMessage(normalizedValue)

  if (validationMessage) {
    ElMessage.warning(validationMessage)
    return
  }

  emit('submit', normalizedValue)
}

function close() {
  if (props.submitting) {
    return
  }

  emit('update:visible', false)
}

function handleVisibleChange(visible) {
  if (!visible) {
    close()
    return
  }

  emit('update:visible', true)
}
</script>

<style></style>
