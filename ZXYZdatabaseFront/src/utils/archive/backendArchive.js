import { fetchFileList, getFileDownloadUrl } from '@/api/files'
import { logger } from '@/utils/logger'
import { joinPath } from '@/utils/pathUtils'

export function getArchiveTargets(selectedItems = [], spaceParams = {}) {
  const seen = new Set()

  return (Array.isArray(selectedItems) ? selectedItems : [])
    .filter((item) => {
      if (!item?.id || seen.has(item.id)) {
        return false
      }

      seen.add(item.id)
      return true
    })
    .map((item) => ({
      id: item.id,
      type: item.type,
      fileName: item.fileName || '',
      teamId: item.teamId ?? spaceParams.teamId ?? null,
      spaceType: item.spaceType ?? spaceParams.spaceType ?? null,
      projectId: item.projectId ?? spaceParams.projectId ?? null,
    }))
}

export function getDefaultArchiveName(targets) {
  if (targets.length !== 1) {
    return 'archive'
  }

  const fileName = targets[0].fileName || 'archive'
  if (targets[0].type === 0) {
    return fileName
  }

  const lastDotIndex = fileName.lastIndexOf('.')
  if (lastDotIndex <= 0) {
    return fileName
  }

  return fileName.slice(0, lastDotIndex) || 'archive'
}

export function normalizeArchiveName(input) {
  const normalized = String(input || '')
    .trim()
    .replace(/[\\/:*?"<>|]/g, '')
    .replace(/\.zip$/i, '')
    .trim()

  return normalized || 'archive'
}

export function joinArchivePath(basePath, fileName) {
  return joinPath(basePath, fileName, { leadingSlash: false, decode: false })
}

export async function collectFileEntry(file, basePath) {
  const response = await getFileDownloadUrl(file.id)
  const downloadUrl = response?.data?.downloadUrl
  const directDownload = response?.data?.directDownload

  if (!downloadUrl) {
    throw new Error(`未获取到下载链接: ${file.fileName}`)
  }

  const effectiveDownloadUrl =
    directDownload === false
      ? `/api/files/${file.id}/stream`
      : downloadUrl

  return {
    fileName: file.fileName,
    archivePath: joinArchivePath(basePath, file.fileName),
    downloadUrl: effectiveDownloadUrl,
    directDownload,
  }
}

export async function collectFolderEntries(folder, basePath) {
  const currentPath = joinArchivePath(basePath, folder.fileName)
  const spaceParams = {
    teamId: folder.teamId || null,
    spaceType: folder.spaceType || null,
    projectId: folder.projectId || null,
  }

  try {
    const response = await fetchFileList(folder.id, spaceParams)
    const children = Array.isArray(response?.data) ? response.data : []
    const entries = []

    for (const child of children) {
      if (child.type === 0) {
        entries.push(
          ...(await collectFolderEntries(
            {
              ...child,
              teamId: child.teamId || spaceParams.teamId,
              spaceType: child.spaceType || spaceParams.spaceType,
              projectId: child.projectId || spaceParams.projectId,
            },
            currentPath,
          )),
        )
        continue
      }

      entries.push(await collectFileEntry(child, currentPath))
    }

    return entries
  } catch (error) {
    logger.error('读取文件夹内容失败:', {
      id: folder.id,
      fileName: folder.fileName,
      archivePath: currentPath,
      error,
    })
    throw error
  }
}

export async function collectArchiveEntries(targets) {
  const entries = []

  for (const target of targets) {
    try {
      if (target.type === 0) {
        entries.push(...(await collectFolderEntries(target, '')))
        continue
      }

      entries.push(await collectFileEntry(target, ''))
    } catch (error) {
      logger.error('收集打包条目失败:', {
        id: target.id,
        fileName: target.fileName,
        type: target.type,
        error,
      })
      throw error
    }
  }

  return entries
}
