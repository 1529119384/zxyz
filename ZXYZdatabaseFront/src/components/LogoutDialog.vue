<template>
  <el-dialog v-model="visible" title="退出登录" width="420px" destroy-on-close append-to-body>
    <span>确认退出当前登录状态吗？</span>
    <template #footer>
      <div class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="danger" @click="handleConfirm">退出</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
const emit = defineEmits(['confirm', 'cancel'])

const visible = defineModel({
  type: Boolean,
  default: false,
})

function handleCancel() {
  // 组件内部统一关闭弹窗，并通知父组件本次操作已取消。
  visible.value = false
  emit('cancel')
}

function handleConfirm() {
  // 先关闭弹窗，再由父组件执行真正的退出登录逻辑。
  visible.value = false
  emit('confirm')
}
</script>

<style scoped>
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
