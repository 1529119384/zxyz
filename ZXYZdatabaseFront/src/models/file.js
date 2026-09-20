// @ts-check
import { createDisplayFileEntry, getFileIcon, splitFileNameParts } from '@/models/filePresentation'

/**
 * 后端返回的文件/文件夹原始记录（宽松描述：只列本模块读取的字段）。
 * @typedef {object} RawFileRecord
 * @property {string|number} [id]
 * @property {string} [originalName]
 * @property {number} [fileType]
 * @property {string} [category]
 * @property {number} [fileSize]
 * @property {string|number|null} [parentId]
 * @property {string|number|null} [teamId]
 * @property {string} [storePath]
 * @property {string|null} [createTime]
 * @property {string|null} [modifyTime]
 * @property {string|number|null} [virtualType]
 * @property {string|number|null} [projectId]
 * @property {string|number|null} [conversationId]
 */

/**
 * @param {RawFileRecord} [item]
 * @returns {ReturnType<typeof createDisplayFileEntry>}
 */
function mapBaseFileEntry(item = {}) {
  return createDisplayFileEntry({
    id: item.id,
    fileName: item.originalName,
    type: item.fileType,
    category: item.category,
    fileSize: item.fileSize,
  })
}

/**
 * 空间列表和搜索列表当前共用完整文件字段，新增通用字段只维护这里。
 * @param {RawFileRecord} [item]
 */
function mapFullFileEntry(item = {}) {
  return {
    ...mapBaseFileEntry(item),
    parentId: item.parentId ?? null,
    teamId: item.teamId ?? null,
    storePath: item.storePath || '',
    createTime: item.createTime ?? null,
    modifyTime: item.modifyTime ?? null,
    virtualType: item.virtualType ?? null,
    projectId: item.projectId ?? null,
    conversationId: item.conversationId ?? null,
  }
}

export const mapSpaceFileEntry = mapFullFileEntry
export const mapSearchFileEntry = mapFullFileEntry

/**
 * @param {RawFileRecord} [item]
 */
export function mapRecycleFileEntry(item = {}) {
  return {
    ...mapBaseFileEntry(item),
    deleteTime: item.modifyTime,
    teamId: item.teamId ?? null,
    storePath: item.storePath || '',
  }
}

/**
 * @param {RawFileRecord[]} [data]
 */
export function mapSpaceFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapSpaceFileEntry)
}

/**
 * @param {RawFileRecord[]} [data]
 */
export function mapRecycleFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapRecycleFileEntry)
}

/**
 * @param {{ list?: RawFileRecord[], total?: number|string, page?: number|string, pageSize?: number|string }} [data]
 */
export function mapSearchFileEntries(data = {}) {
  const list = Array.isArray(data?.list) ? data.list : []

  return {
    total: Number(data?.total) || 0,
    // 搜索信封自 07-P2-4 起也带 page / pageSize（此前只有 total/list）。
    // 0 表示「后端没回传」，useFileSearch 会保持本地值而不是把页长改成 0。
    page: Number(data?.page) || 0,
    pageSize: Number(data?.pageSize) || 0,
    list: list.map(mapSearchFileEntry),
  }
}

export { getFileIcon, splitFileNameParts }
