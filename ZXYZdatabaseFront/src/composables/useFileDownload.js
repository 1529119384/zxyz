import { ElMessage } from 'element-plus'

import { getFileDownloadUrl } from '@/api/files'
import { copyText } from '@/utils/clipboard'
import { downloadBlobByUrl } from '@/utils/download'

/**
 * @typedef {Object} UseFileDownloadOptions
 * @property {Object} router - Vue Router 实例，用于解析路由链接。
 */

/**
 * 文件下载与链接复制功能。
 *
 * @param {UseFileDownloadOptions} options - 配置项。
 * @returns {{ downloadFile: Function, copyDownloadLink: Function, openFolderInNewTab: Function }} 文件下载操作方法。
 */
export function useFileDownload(options) {
  const { router } = options

  async function getDownloadUrl(row) {
    const response = await getFileDownloadUrl(row.id)
    const downloadUrl = response?.data?.downloadUrl
    const directDownload = response?.data?.directDownload
    const fileName = response?.data?.fileName || row.fileName || row.originalName

    if (!downloadUrl) {
      throw new Error('未获取到下载链接')
    }

    return { downloadUrl, directDownload, fileName }
  }

  async function downloadFile(row) {
    const { downloadUrl, directDownload, fileName } = await getDownloadUrl(row)
    if (!downloadUrl) {
      return
    }

    if (directDownload !== false) {
      // 直下：直接使用预签名 URL（OSS 等）
      await downloadBlobByUrl(downloadUrl, fileName)
    } else {
      // 流式下载：调用后端流式下载接口（本地存储等）
      const streamUrl = `/api/files/${row.id}/stream`
      await downloadBlobByUrl(streamUrl, fileName)
    }
  }

  async function copyDownloadLink(row) {
    const { downloadUrl } = await getDownloadUrl(row)
    if (!downloadUrl) {
      return
    }

    await copyText(downloadUrl)
    ElMessage.success('下载链接已复制')
  }

  function openFolderInNewTab(targetPath) {
    const targetUrl = router.resolve({
      name: 'index',
      query: { path: targetPath },
    })
    window.open(targetUrl.href, '_blank', 'noopener')
  }

  return {
    downloadFile,
    copyDownloadLink,
    openFolderInNewTab,
  }
}
