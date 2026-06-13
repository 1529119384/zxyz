import { ElMessage } from 'element-plus'

import { PROJECT } from '@/constants/conversationTypes'
import {
  getFileCardPreviewEntries,
  getFileCardSummary,
  getFileCardTitle,
} from '@/models/imPresentation'
import { runArchiveDownload } from '@/utils/archive/archiveDownloadRunner'
import { downloadBlobByUrl } from '@/utils/download'
import { handleBusinessError } from '@/utils/error'

export function useChatFileCardActions({ chatStore, teamStore, router, activeConversation }) {
  const imChat = chatStore

  function fileCardTitle(fileCard = {}) {
    return getFileCardTitle(fileCard)
  }

  function fileCardSummary(fileCard = {}) {
    return getFileCardSummary(fileCard)
  }

  function previewEntries(fileCard = {}) {
    return getFileCardPreviewEntries(fileCard)
  }

  function canDownloadFileCard(fileCard = {}) {
    return fileCard?.shareType === 'SINGLE_FILE'
  }

  function canOpenFileCardFolder(fileCard = {}) {
    return Array.isArray(fileCard?.entries) && fileCard.entries.length > 0
  }

  function canArchiveFileCard(fileCard = {}) {
    return fileCard?.shareType === 'SINGLE_FOLDER' || fileCard?.shareType === 'MULTI_FILE'
  }

  async function handleFileCardAction(message, action) {
    try {
      if (!message?.messageId) {
        ElMessage.warning('文件卡片还在发送中，请稍后再试')
        return
      }
      const resolved = await imChat.resolveFileCardMessage(message.messageId)
      if (!resolved || resolved.status === 'DELETED' || resolved.status === 'NO_PERMISSION') {
        ElMessage.warning('文件已删除、移动或你已无权访问')
        return
      }
      if (action === 'download') {
        if (!canDownloadFileCard(message.fileCard) || !resolved.downloadUrl) {
          ElMessage.warning('当前分享不支持直接下载')
          return
        }
        await downloadBlobByUrl(resolved.downloadUrl, resolved.title || 'download')
        return
      }
      if (action === 'openFolder') {
        await openFileCardFolder(resolved)
        return
      }
      if (action === 'archiveDownload') {
        await downloadFileCardArchive(message, resolved)
      }
    } catch (error) {
      handleBusinessError(error, '处理文件卡片失败')
    }
  }

  async function openFileCardFolder(resolved) {
    const targetPath = typeof resolved.folderPath === 'string' ? resolved.folderPath.trim() : ''
    if (!targetPath) {
      ElMessage.warning('当前分享缺少可定位的目录信息')
      return
    }
    if (activeConversation.value?.type === PROJECT && activeConversation.value?.projectId) {
      await router.push({
        name: 'projectSpace',
        params: { projectId: String(activeConversation.value.projectId) },
        query: { path: targetPath },
      })
      return
    }
    const targetTeamId = Number(activeConversation.value?.teamId || teamStore.selectedTeamId)
    if (!Number.isSafeInteger(targetTeamId) || targetTeamId <= 0) {
      ElMessage.warning('当前会话缺少团队上下文，无法打开所在位置')
      return
    }
    await router.push({
      name: 'teamSpace',
      query: { path: targetPath, teamId: String(targetTeamId) },
    })
  }

  async function downloadFileCardArchive(message, resolved) {
    if (!canArchiveFileCard(message.fileCard)) {
      ElMessage.warning('当前分享不支持打包下载')
      return
    }
    await runArchiveDownload({
      collectEntries: async () =>
        Array.isArray(resolved.archiveEntries) ? resolved.archiveEntries : [],
      archiveName: (resolved.title || 'archive').replace(/\.zip$/i, ''),
      onEmpty: () => ElMessage.warning('当前文件夹无可下载内容'),
      onSuccess: () => ElMessage.success('已开始打包下载'),
    })
  }

  return {
    fileCardTitle,
    fileCardSummary,
    previewEntries,
    canDownloadFileCard,
    canOpenFileCardFolder,
    canArchiveFileCard,
    handleFileCardAction,
  }
}
