<template>
  <el-dialog
    v-model="fileUploadDialog"
    title="文件上传"
    width="500"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @closed="resetFileUploadState"
  >
    <div
      class="upload-drag"
      @dragover.prevent="handleDragOver"
      @drop.prevent="handleDrop"
      @click="triggerSelect"
    >
      <input
        ref="fileInput"
        type="file"
        multiple
        class="hidden-input"
        accept="image/*,video/*,audio/*,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.json,.xml,.md,.zip,.rar,.7z,.tar,.gz,.bz2,.xz,.html,.css,.ts,.vue,.java,.py,.go,.rs,.c,.cpp,.h,.hpp,.rb,.php,.swift,.kt,.dart,.sql,.log,.yml,.yaml,.toml,.ini,.cfg,.conf,.svg,.ico,.webp,.bmp,.tiff,.tif,.eps,.ai,.psd,.sketch,.figma"
        @change="handleSelect"
      />

      <div class="upload-content">
        <p>将文件拖到此处或 <em>点击上传</em></p>
        <p class="tip">
          支持图片、视频、音频、文档、压缩包、代码等常见格式，单文件最大
          1GB，不支持可执行文件（.exe/.bat/.sh 等）。重名文件将由后端自动改名
        </p>
      </div>
    </div>

    <div v-if="fileList.length" class="file-list">
      <div v-for="(f, index) in fileList" :key="getFileKey(f)" class="file-item">
        <div class="file-meta">
          <span class="file-name">{{ f.name }}</span>
          <span v-if="isPredictedRenamed(f)" class="predicted-name">
            预计名称：{{ getPredictedName(f) }}
          </span>
        </div>
        <span class="file-size">
          {{ formatSize(f.size) }}
        </span>
        <el-icon class="remove-btn" @click.stop="removeFile(index)">
          <Close />
        </el-icon>
      </div>
    </div>

    <div v-if="fileList.length" class="upload-summary">
      <span>已选择 {{ fileList.length }} 个文件</span>
      <span>总大小：{{ formatSize(totalFileSize) }}</span>
    </div>

    <div v-if="uploading" class="upload-progress">
      <div class="progress-text">
        <span>已上传 {{ formatSize(uploadedBytes) }}</span>
        <span>总大小 {{ formatSize(totalFileSize) }}</span>
      </div>
      <el-progress :percentage="progress" status="success" :stroke-width="12" />
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="uploading" @click="handleCancelFileUpload"> 取消 </el-button>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="uploading || !fileList.length"
          @click="doUpload"
        >
          {{ uploading ? '上传中...' : '上传' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'

import { formatUploadSummary } from '@/components/uploader/shared'
import { useProvidedSpaceContext } from '@/composables/useCurrentSpaceContext'
import { useFileUpload } from '@/composables/useFileUpload'
import { useCurrentIdStore } from '@/store/currentId'
import { handleBusinessError } from '@/utils/error'
import { formatSize } from '@/utils/format'
import { logger } from '@/utils/logger'

const props = defineProps({
  getSiblingEntries: {
    type: Function,
    default: null,
  },
})

const emit = defineEmits(['success'])

const currentIdStore = useCurrentIdStore()
const spaceContext = useProvidedSpaceContext()
const { currentId } = storeToRefs(currentIdStore)
const {
  fileUploadDialog,
  fileInput,
  fileList,
  uploading,
  progress,
  totalFileSize,
  uploadedBytes,
  triggerSelect,
  handleDragOver,
  getFileKey,
  getPredictedName,
  isPredictedRenamed,
  resetFileUploadState,
  handleSelect,
  handleDrop,
  removeFile,
  handleCancelFileUpload,
  doUpload: doFileUpload,
  openFileUpload,
} = useFileUpload(currentId, {
  getSiblingEntries: () => props.getSiblingEntries?.() || [],
  spaceContext,
  onSuccess() {
    emit('success')
  },
})

async function doUpload() {
  const filesToUpload = [...fileList.value]

  try {
    const { successList, failList } = await doFileUpload()

    if (!failList.length) {
      ElMessage.success(formatUploadSummary(successList, failList, '全部文件上传成功'))
      fileUploadDialog.value = false
      return
    }

    if (successList.length) {
      ElMessage.warning(formatUploadSummary(successList, failList, '部分文件上传成功'))
      fileUploadDialog.value = false
      return
    }

    ElMessage.error(formatUploadSummary(successList, failList, '文件上传失败'))
  } catch (error) {
    logger.error('[文件上传流程失败]', {
      files: filesToUpload.map((file) => file.name),
      message: error?.message,
    })
    handleBusinessError(error, '文件上传失败，请重试')
  }
}

defineExpose({
  openFileUpload,
})
</script>

<style scoped>
.upload-drag {
  width: 100%;
  padding: 40px;
  border: 2px dashed #d9d9d9;
  border-radius: 10px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.3s ease;
}

.upload-drag:hover {
  border-color: #409eff;
}

.hidden-input {
  display: none;
}

.tip {
  margin-top: 8px;
  color: #999;
}

.file-list {
  margin-top: 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  max-height: 200px;
  overflow-y: auto;
  padding: 6px 0;
}

.file-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 12px;
  border-bottom: 1px solid #f0f0f0;
  align-items: center;
  font-size: 14px;
  transition: background-color 0.2s ease;
}

.file-item:hover {
  background-color: #fafafa;
}

.file-item:last-child {
  border-bottom: none;
}

.file-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.file-name {
  word-break: break-all;
}

.predicted-name {
  font-size: 12px;
  color: #e6a23c;
  word-break: break-all;
}

.file-size {
  margin-left: auto;
  padding-left: 12px;
  font-size: 12px;
  color: #909399;
}

.remove-btn {
  cursor: pointer;
  color: #f56c6c;
  transition: color 0.3s ease;
  margin-left: 12px;
}

.remove-btn:hover {
  color: #ff4141;
}

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
