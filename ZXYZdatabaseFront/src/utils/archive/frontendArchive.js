import { BlobWriter, ZipWriter } from '@zip.js/zip.js'

import { triggerDownloadByBlob } from '@/utils/download'

export function resolveArchiveEntryName(entry, index) {
  const rawPath = entry?.archivePath || entry?.fileName || `file-${index + 1}`

  return String(rawPath)
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .split('/')
    .filter(Boolean)
    .join('/')
}

export function formatArchiveLoadingText(current, total, archivePath) {
  return `处理中：(${current}/${total}) ${archivePath || ''}`.trim()
}

export async function buildAndDownloadArchive(entries, archiveName, onProgress) {
  const zipWriter = new ZipWriter(new BlobWriter('application/zip'))
  const total = entries.length

  for (const [index, entry] of entries.entries()) {
    // archivePath 直接作为 zip 内路径，保证导出的目录层级稳定。
    const entryName = resolveArchiveEntryName(entry, index)
    const downloadUrl = entry?.downloadUrl

    if (!entryName || !downloadUrl) {
      throw new Error(`打包条目缺少必要字段: ${JSON.stringify(entry)}`)
    }

    onProgress?.({
      current: index + 1,
      total,
      archivePath: entryName,
    })

    const fileResponse = await fetch(downloadUrl)

    if (!fileResponse.ok || !fileResponse.body) {
      throw new Error(`文件拉取失败: ${entryName}`)
    }

    await zipWriter.add(entryName, fileResponse.body)
  }

  const zipBlob = await zipWriter.close()
  triggerDownloadByBlob(zipBlob, `${archiveName}.zip`)
}
