import { ElLoading } from 'element-plus'

import { buildAndDownloadArchive, formatArchiveLoadingText } from '@/utils/archive/frontendArchive'

export async function runArchiveDownload(options = {}) {
  const { collectEntries, archiveName, setSubmitting, onEmpty, onSuccess } = options

  setSubmitting?.(true)

  // 由执行器统一持有 loading 和提交态，避免各业务场景重复维护相同的收尾逻辑。
  const loading = ElLoading.service({
    lock: true,
    text: formatArchiveLoadingText(0, 0, '准备开始打包'),
    background: 'rgba(255, 255, 255, 0.7)',
  })

  try {
    const entries = await collectEntries?.()

    if (!entries?.length) {
      onEmpty?.()
      return
    }

    await buildAndDownloadArchive(entries, archiveName, ({ current, total, archivePath }) => {
      loading.setText(formatArchiveLoadingText(current, total, archivePath))
    })

    onSuccess?.()
  } finally {
    loading.close()
    setSubmitting?.(false)
  }
}
