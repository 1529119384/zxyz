import { createDisplayFileEntry, getFileIcon, splitFileNameParts } from '@/models/filePresentation'

function mapBaseFileEntry(item = {}) {
  return createDisplayFileEntry({
    id: item.id,
    fileName: item.originalName,
    type: item.fileType,
    category: item.category,
    fileSize: item.fileSize,
  })
}

// 空间列表和搜索列表当前共用完整文件字段，新增通用字段只维护这里。
function mapFullFileEntry(item = {}) {
  return {
    ...mapBaseFileEntry(item),
    parentId: item.parentId ?? null,
    teamId: item.teamId ?? null,
    storePath: item.storePath || '',
    createTime: item.createTime ?? null,
    modifyTime: item.modifyTime ?? null,
    virtualType: item.virtualType || null,
    projectId: item.projectId ?? null,
    conversationId: item.conversationId ?? null,
  }
}

export const mapSpaceFileEntry = mapFullFileEntry
export const mapSearchFileEntry = mapFullFileEntry

export function mapRecycleFileEntry(item = {}) {
  return {
    ...mapBaseFileEntry(item),
    deleteTime: item.modifyTime,
    teamId: item.teamId ?? null,
    storePath: item.storePath || '',
  }
}

export function mapSpaceFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapSpaceFileEntry)
}

export function mapRecycleFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapRecycleFileEntry)
}

export function mapSearchFileEntries(data = {}) {
  const list = Array.isArray(data?.list) ? data.list : []

  return {
    total: Number(data?.total) || 0,
    list: list.map(mapSearchFileEntry),
  }
}

export { getFileIcon, splitFileNameParts }
