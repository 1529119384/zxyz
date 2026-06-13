<template>
  <div class="recycle-bin-page">
    <RecycleBinExplorer
      ref="fileShowRef"
      :can-write="canDelete"
      @selection-change="handleSelectionChange"
      @context-action="handleContextAction"
      @row-action="handleRowAction"
    />
    <DeleteConfirmDialog
      :visible="deleteDialogVisible"
      :file-name="deleteDialogOptions.fileName"
      :type="deleteDialogOptions.type"
      :message="deleteDialogOptions.message"
      :tip="deleteDialogOptions.tip"
      :confirm-text="deleteDialogOptions.confirmText"
      :submitting="deleteSubmitting"
      @update:visible="handleDeleteDialogVisibleChange"
      @submit="handleDeleteForeverSubmit"
    />
  </div>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { ref } from 'vue'

import DeleteConfirmDialog from '@/components/DeleteConfirmDialog.vue'
import RecycleBinExplorer from '@/components/RecycleBinExplorer.vue'
import { useRecycleBinActions } from '@/composables/useRecycleBinActions'
import { useCurrentUserStore } from '@/store/currentUser'

const fileShowRef = ref(null)
const currentUserStore = useCurrentUserStore()
const { canDelete } = storeToRefs(currentUserStore)

function refreshFileList() {
  return fileShowRef.value?.refresh?.()
}

function clearFileSelection() {
  fileShowRef.value?.clearSelection?.()
}

const {
  deleteDialogVisible,
  deleteSubmitting,
  deleteDialogOptions,
  handleDeleteDialogVisibleChange,
  handleDeleteForeverSubmit,
  handleSelectionChange,
  handleContextAction,
  handleRowAction,
} = useRecycleBinActions({
  onRefresh: refreshFileList,
  onClearSelection: clearFileSelection,
})
</script>

<style scoped>
.recycle-bin-page {
  height: 100%;
  min-height: 100%;
}
</style>
