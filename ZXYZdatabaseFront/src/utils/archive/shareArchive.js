import { fetchPublicShareFiles, getPublicShareDownloadUrl } from '@/api/share'
import { joinArchivePath } from '@/utils/archive/backendArchive'

export function getDefaultShareArchiveName(path = '') {
  const segments = String(path || '')
    .split('/')
    .filter(Boolean)

  return segments[segments.length - 1] || 'share-download'
}

async function collectPublicShareFileEntry(shareKey, file, basePath) {
  const response = await getPublicShareDownloadUrl(shareKey, file.id)
  const downloadUrl = response?.data?.downloadUrl

  if (!downloadUrl) {
    throw new Error(`未获取到下载链接: ${file.fileName}`)
  }

  return {
    fileName: file.fileName,
    archivePath: joinArchivePath(basePath, file.fileName),
    downloadUrl,
  }
}

async function collectPublicShareFolderEntries(shareKey, currentPath, basePath) {
  const response = await fetchPublicShareFiles(shareKey, currentPath)
  const children = Array.isArray(response?.data) ? response.data : []
  const entries = []

  for (const child of children) {
    if (child.invalid) {
      continue
    }

    if (child.type === 0) {
      const nextCurrentPath = joinArchivePath(currentPath, child.fileName)
      const nextBasePath = joinArchivePath(basePath, child.fileName)
      entries.push(
        ...(await collectPublicShareFolderEntries(shareKey, nextCurrentPath, nextBasePath)),
      )
      continue
    }

    entries.push(await collectPublicShareFileEntry(shareKey, child, basePath))
  }

  return entries
}

export async function collectPublicShareArchiveEntries(shareKey, path = '') {
  return collectPublicShareFolderEntries(shareKey, String(path || ''), '')
}
