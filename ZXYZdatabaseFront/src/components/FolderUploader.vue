<template>
  <div>
    <input
      ref="folderInput"
      type="file"
      webkitdirectory
      multiple
      hidden
      @change="onFolderSelected"
    />

    <el-dialog
      v-model="folderUploadDialog"
      title="文件夹上传"
      width="500"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      @closed="resetFolderUploadState"
    >
      <div v-if="folderTree.length" class="upload-summary">
        <span>共 {{ folderStats.folderCount }} 个文件夹</span>
        <span>{{ folderStats.fileCount }} 个文件</span>
        <span>总大小：{{ formatSize(folderStats.totalSize) }}</span>
      </div>

      <el-scrollbar v-if="folderTree.length" height="260px">
        <el-tree-v2
          :data="folderTree"
          :default-expanded-keys="expandedFolderKeys"
          :props="{ label: 'name' }"
        >
          <template #default="{ node }">
            <el-icon>
              <Document v-if="node.isLeaf" />
              <Folder v-else-if="!node.expanded" />
              <FolderOpened v-else />
            </el-icon>
            <span>{{ node.label }}</span>
            <span v-if="node.data.isLeaf" class="file-size">
              {{ formatSize(node.data.size) }}
            </span>
          </template>
        </el-tree-v2>
      </el-scrollbar>

      <div v-if="uploadLoading" class="upload-progress">
        <div class="progress-text">
          <span>正在上传 {{ currentFolderUploadName || '文件夹内容' }}</span>
          <span
            >{{ uploadedFolderFileCount }}/{{ folderStats.fileCount }} ·
            {{ formatSize(uploadedFolderBytes) }}/{{ formatSize(folderStats.totalSize) }}</span
          >
        </div>
        <el-progress :percentage="folderUploadProgress" status="success" :stroke-width="12" />
      </div>

      <template #footer>
        <el-button :disabled="uploadLoading" @click="handleCancelFolderUpload"> 取消 </el-button>
        <el-button
          type="primary"
          :loading="uploadLoading"
          :disabled="uploadLoading || !folderTree.length"
          @click="uploadSelectedFiles"
        >
          <el-icon>
            <Upload />
          </el-icon>
          {{ uploadLoading ? '上传中...' : '上传文件夹' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ElLoading, ElMessage } from 'element-plus'
import { Document, Folder, FolderOpened, Upload } from '@element-plus/icons-vue'

import { formatUploadSummary } from '@/components/uploader/shared'
import { useProvidedSpaceContext } from '@/composables/useCurrentSpaceContext'
import { useFolderUpload } from '@/composables/useFolderUpload'
import { useCurrentIdStore } from '@/store/currentId'
import { uploadFolderTree } from '@/services/upload'
import { handleBusinessError } from '@/utils/error'
import { formatSize } from '@/utils/format'
import { logger } from '@/utils/logger'

const emit = defineEmits(['success'])

const currentIdStore = useCurrentIdStore()
const spaceContext = useProvidedSpaceContext()
const {
  folderInput,
  folderUploadDialog,
  folderTree,
  expandedFolderKeys,
  uploadLoading,
  uploadedFolderFileCount,
  currentFolderUploadName,
  folderStats,
  folderUploadProgress,
  uploadedFolderBytes,
  resetFolderUploadState,
  setUploadLoading,
  resetUploadProgress,
  markFileStart,
  markFileProgress,
  markFileSuccess,
  completeUpload,
  onFolderSelected,
  handleCancelFolderUpload,
  getUploadContext,
  openFolderUpload,
} = useFolderUpload()

async function uploadSelectedFiles() {
  if (!folderTree.value.length) {
    return
  }

  const { tree, fileMap, stats } = getUploadContext()
  resetUploadProgress()
  setUploadLoading(true)
  const loading = ElLoading.service({ text: '文件上传中...' })

  try {
    const { successList, failList } = await uploadFolderTree(tree, currentIdStore.currentId, {
      fileMap,
      onFileStart: markFileStart,
      onFileProgress: markFileProgress,
      onFileSuccess: markFileSuccess,
      ...spaceContext.resolveRequestParams(),
    })
    const fileSuccessCount = successList.filter((item) => item.type === 1).length

    if (successList.length) {
      if (fileSuccessCount === stats.fileCount) {
        completeUpload()
      }
      emit('success')
    }

    if (!failList.length) {
      ElMessage.success(formatUploadSummary(successList, failList, '上传成功'))
      folderUploadDialog.value = false
      return
    }

    if (successList.length) {
      ElMessage.warning(formatUploadSummary(successList, failList))
      folderUploadDialog.value = false
      return
    }

    ElMessage.error(formatUploadSummary(successList, failList))
  } catch (error) {
    logger.error('[文件夹上传流程失败]', {
      currentFile: currentFolderUploadName.value,
      uploadedCount: uploadedFolderFileCount.value,
      total: stats.fileCount,
      message: error?.message,
    })
    handleBusinessError(error, '上传失败，请重试')
  } finally {
    setUploadLoading(false)
    loading.close()
  }
}

defineExpose({
  openFolderUpload,
})
</script>

<style scoped>
.upload-summary {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  color: #606266;
  font-size: 13px;
}

.file-size {
  margin-left: auto;
  padding-left: 12px;
  font-size: 12px;
  color: #909399;
}

.upload-progress {
  margin-top: 20px;
}

.progress-text {
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}
</style>
