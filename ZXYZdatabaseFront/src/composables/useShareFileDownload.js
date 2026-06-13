import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { getPublicShareDownloadUrl } from '@/api/share'
import { downloadBlobByUrl } from '@/utils/download'

/**
 * 公开分享文件下载 composable，获取分享文件的下载链接并触发下载。
 *
 * @returns {{ downloadFile: Function }} 分享文件下载方法。
 */
export function useShareFileDownload() {
  const route = useRoute()
  const shareKey = computed(() => String(route.params.shareKey || ''))

  async function downloadFile(row) {
    const response = await getPublicShareDownloadUrl(shareKey.value, row.id)
    const downloadUrl = response?.data?.downloadUrl

    if (!downloadUrl) {
      throw new Error('未获取到下载链接')
    }

    await downloadBlobByUrl(downloadUrl, row.fileName)
  }

  return {
    downloadFile,
  }
}
