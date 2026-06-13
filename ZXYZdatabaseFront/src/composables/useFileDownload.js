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

    if (!downloadUrl) {
      throw new Error('未获取到下载链接')
    }

    return downloadUrl
  }

  async function downloadFile(row) {
    const downloadUrl = await getDownloadUrl(row)
    if (!downloadUrl) {
      return
    }

    await downloadBlobByUrl(downloadUrl, row.fileName)
  }

  async function copyDownloadLink(row) {
    const downloadUrl = await getDownloadUrl(row)
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
