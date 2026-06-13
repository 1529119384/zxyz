<template>
  <div class="uploader">
    <FileUploader
      ref="fileUploaderRef"
      :get-sibling-entries="getSiblingEntries"
      @success="emitSuccess"
    />
    <FolderUploader ref="folderUploaderRef" @success="emitSuccess" />
  </div>
</template>

<script setup>
import { ref } from 'vue'

import FileUploader from '@/components/FileUploader.vue'
import FolderUploader from '@/components/FolderUploader.vue'

defineProps({
  getSiblingEntries: {
    type: Function,
    default: null,
  },
})

const emit = defineEmits(['success'])

const fileUploaderRef = ref(null)
const folderUploaderRef = ref(null)

function emitSuccess() {
  emit('success')
}

function openFileUpload() {
  fileUploaderRef.value?.openFileUpload?.()
}

function openFolderUpload() {
  folderUploaderRef.value?.openFolderUpload?.()
}

defineExpose({
  openFileUpload,
  openFolderUpload,
})
</script>

<style scoped>
.uploader {
  display: contents;
}
</style>
