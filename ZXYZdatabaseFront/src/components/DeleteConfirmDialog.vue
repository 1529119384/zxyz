<template>
  <el-dialog
    :model-value="visible"
    title="删除确认"
    width="420"
    center
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @update:model-value="handleVisibleChange"
  >
    <div class="delete-confirm-content">
      <p class="delete-confirm-title">
        {{ confirmMessage }}
      </p>
      <p class="delete-confirm-tip">
        {{ tip }}
      </p>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="submitting" @click="close"> 取消 </el-button>
        <el-button
          type="danger"
          :loading="submitting"
          :disabled="submitting"
          @click="handleConfirm"
        >
          {{ confirmText }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  submitting: {
    type: Boolean,
    default: false,
  },
  fileName: {
    type: String,
    default: '当前文件',
  },
  type: {
    type: Number,
    default: 1,
  },
  message: {
    type: String,
    default: '',
  },
  tip: {
    type: String,
    default: '删除后文件会进入回收站，可在回收站中恢复或彻底删除。',
  },
  confirmText: {
    type: String,
    default: '确认删除',
  },
})

const emit = defineEmits(['submit', 'update:visible'])

const targetTypeLabel = computed(() => (props.type === 0 ? '文件夹' : '文件'))
const confirmMessage = computed(() => {
  if (props.message) {
    return props.message
  }

  return `确认删除${targetTypeLabel.value}“${props.fileName}”吗？`
})

function handleConfirm() {
  if (props.submitting) {
    return
  }

  // 由父组件决定真正的删除动作，弹窗组件只负责发出确认事件。
  emit('submit')
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

<style scoped>
.delete-confirm-content {
  padding: 8px 0 4px;
}

.delete-confirm-title {
  margin: 0;
  color: #303133;
  font-size: 16px;
  line-height: 24px;
  word-break: break-all;
}

.delete-confirm-tip {
  margin: 12px 0 0;
  color: #909399;
  font-size: 13px;
  line-height: 20px;
}
</style>
