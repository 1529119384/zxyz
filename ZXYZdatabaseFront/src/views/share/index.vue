<template>
  <div class="share-page">
    <div class="share-card">
      <div class="share-card__header">
        <img src="@/assets/images/logo2.png" alt="logo" class="share-logo" />
        <div>
          <h1 class="share-title">文件分享</h1>
          <p class="share-subtitle">
            {{ shareInfo?.showUsername || '来自分享链接的内容' }}
          </p>
        </div>
      </div>

      <el-skeleton v-if="pageLoading" animated :rows="5" />

      <template v-else-if="displayPageError">
        <el-empty :description="displayPageError" />
      </template>

      <template v-else-if="!canViewContent">
        <div class="password-panel">
          <p class="password-panel__tip">此分享已开启提取码，请先完成校验</p>
          <el-input
            v-model="passwordInput"
            maxlength="4"
            placeholder="请输入 4 位提取码"
            class="password-panel__input"
            @keyup.enter="handleVerify"
          />
          <el-button type="primary" :loading="verifying" @click="handleVerify">
            提交提取码
          </el-button>
        </div>
      </template>

      <template v-else>
        <ShareFileExplorer
          :list="fileList"
          :current-path="currentPath"
          :loading="fileLoading"
          @navigate="navigateToPath"
          @open-folder="openFolder"
          @download="handleDownload"
          @archive-download="openArchiveNameDialog"
          @archive-download-folder="openFolderArchiveNameDialog"
        />
      </template>
    </div>

    <ArchiveNameDialog
      :visible="archiveDialogVisible"
      :default-value="archiveDefaultName"
      :submitting="archiveSubmitting"
      @update:visible="handleArchiveDialogVisibleChange"
      @submit="handleArchiveDownload"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import ArchiveNameDialog from '@/components/ArchiveNameDialog.vue'
import ShareFileExplorer from '@/components/ShareFileExplorer.vue'
import { useShareArchiveDownload } from '@/composables/useShareArchiveDownload'
import { useShareFileDownload } from '@/composables/useShareFileDownload'
import { useShareFileList } from '@/composables/useShareFileList'
import { useShareFileNavigation } from '@/composables/useShareFileNavigation'
import { sanitizeSharePassword } from '@/models/share'
import { useShareVisit } from '@/composables/useShareVisit'
import { handleBusinessError } from '@/utils/error'

const route = useRoute()
const shareKey = computed(() => String(route.params.shareKey || ''))
const shareVisit = useShareVisit()
const shareNavigation = useShareFileNavigation()
const shareFileList = useShareFileList({
  shareKey,
  currentPath: shareNavigation.currentPath,
  canViewContent: shareVisit.canViewContent,
})
const shareFileDownload = useShareFileDownload()
const shareArchiveDownload = useShareArchiveDownload({
  shareKey,
  currentPath: shareNavigation.currentPath,
})

const {
  shareInfo,
  pageLoading,
  verifying,
  submitPassword,
  pageError,
  canViewContent,
  passByPassword,
} = shareVisit

const { fileList, fileLoading, fileListError } = shareFileList

const { currentPath, navigateToPath, openFolder } = shareNavigation

const {
  archiveDialogVisible,
  archiveSubmitting,
  archiveDefaultName,
  openArchiveNameDialog,
  closeArchiveNameDialog,
  handleArchiveDownloadSubmit,
} = shareArchiveDownload

const passwordInput = computed({
  get: () => submitPassword.value,
  set: (value) => {
    submitPassword.value = sanitizeSharePassword(value)
  },
})

// 访问错误优先于列表错误，保持页面级空状态展示语义一致。
const displayPageError = computed(() => pageError.value || fileListError.value)

async function handleVerify() {
  await passByPassword(submitPassword.value)
}

async function handleDownload(row) {
  try {
    await shareFileDownload.downloadFile(row)
  } catch (error) {
    handleBusinessError(error, '下载失败，请稍后重试')
  }
}

function openFolderArchiveNameDialog(row) {
  shareArchiveDownload.openArchiveNameDialog(row)
}

function handleArchiveDialogVisibleChange(visible) {
  if (!visible) {
    closeArchiveNameDialog()
  }
}

async function handleArchiveDownload(archiveName) {
  try {
    await handleArchiveDownloadSubmit(archiveName)
  } catch (error) {
    handleBusinessError(error, '打包下载失败，请稍后重试')
  }
}
</script>

<style scoped>
.share-page {
  min-height: 100%;
  padding: 40px 24px;
  overflow: auto;
  background:
    radial-gradient(circle at top left, rgba(64, 158, 255, 0.16), transparent 30%),
    linear-gradient(180deg, #f7f9fc 0%, #eef3f8 100%);
}

.share-card {
  max-width: 1100px;
  margin: 0 auto;
  padding: 28px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.share-card__header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.share-logo {
  width: 64px;
  height: 64px;
  object-fit: contain;
}

.share-title {
  font-size: 28px;
  line-height: 1.2;
  color: #1f2937;
}

.share-subtitle {
  margin-top: 6px;
  color: #6b7280;
}

.password-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 16px;
  max-width: 320px;
}

.password-panel__tip {
  color: #606266;
  line-height: 22px;
}

.password-panel__input {
  width: 100%;
}

@media (max-width: 768px) {
  .share-page {
    padding: 20px 12px;
  }

  .share-card {
    padding: 18px;
  }

  .share-card__header {
    align-items: flex-start;
  }
}
</style>
