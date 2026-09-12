import { BlobWriter, ZipWriter } from '@zip.js/zip.js'

import { assertNotHtmlResponse, triggerDownloadByBlob } from '@/utils/download'

export function resolveArchiveEntryName(entry, index) {
  const rawPath = entry?.archivePath || entry?.fileName || `file-${index + 1}`

  // 过滤空段与 . / .. 段：阻止 zip 内出现 `../` 路径穿越（解压方可能据此写到目标目录之外）。
  // 正常文件名不会是这两者，过滤掉不会影响目录层级。
  return String(rawPath)
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .split('/')
    .filter((segment) => segment && segment !== '.' && segment !== '..')
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

    // 签名过期 / 代理异常时接口会以 200 返回 HTML 错误页；不拦截就会把错误页
    // 当成文件内容压进 zip，用户解压后才发现全是残档（审计 12-第三节低危项）。
    assertNotHtmlResponse(
      fileResponse,
      `文件拉取失败（返回了页面而非文件，签名可能已过期）: ${entryName}`,
    )

    await zipWriter.add(entryName, fileResponse.body)
  }

  const zipBlob = await zipWriter.close()
  triggerDownloadByBlob(zipBlob, `${archiveName}.zip`)
}
