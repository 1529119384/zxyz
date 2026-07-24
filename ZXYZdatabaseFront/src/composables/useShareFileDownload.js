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
    const { downloadUrl, directDownload, fileName } = response?.data || {}

    if (directDownload === false) {
      // 本地存储：使用后端流式下载接口
      const streamUrl = `/api/public/shares/${shareKey.value}/files/${row.id}/stream`
      await downloadBlobByUrl(streamUrl, fileName || row.fileName)
      return
    }

    if (!downloadUrl) {
      throw new Error('未获取到下载链接')
    }

    await downloadBlobByUrl(downloadUrl, fileName || row.fileName)
  }

  return {
    downloadFile,
  }
}
