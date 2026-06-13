<template>
  <el-dialog
    :model-value="visible"
    title="分享已创建"
    width="520"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="handleVisibleChange"
  >
    <div class="success-content">
      <div class="success-tip">分享文案</div>
      <el-input :model-value="message" type="textarea" :rows="5" resize="none" readonly />
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="close"> 关闭 </el-button>
        <el-button type="primary" @click="$emit('copy')"> 复制链接 </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  message: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['copy', 'update:visible'])

function close() {
  emit('update:visible', false)
}

function handleVisibleChange(value) {
  emit('update:visible', value)
}
</script>

<style scoped>
.success-content {
  padding-top: 4px;
}

.success-tip {
  margin-bottom: 6px;
  color: #606266;
  font-size: 14px;
}
</style>
