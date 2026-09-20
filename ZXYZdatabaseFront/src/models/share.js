// @ts-check
import { SHARE_STATUS } from '@/constants/shareStatus'
import { createDisplayFileEntry, normalizeFileType } from '@/models/filePresentation'
import { fmtTime } from '@/utils/format'
import { parseCrumbs } from '@/utils/pathUtils'

/**
 * 我的分享列表项（后端原始记录：只列本模块读取的字段）。
 * @typedef {object} RawShareRecord
 * @property {string|number} [shareId]
 * @property {string} [shareKey]
 * @property {string} [shareUrl]
 * @property {boolean} [hasPassword]
 * @property {string} [expireType]
 * @property {string|null} [expireTime]
 * @property {number} [maxAccessCount]
 * @property {number} [currentAccessCount]
 * @property {number} [status]
 * @property {string} [statusText]
 * @property {string|null} [createTime]
 */

/**
 * 分享内容里的文件/文件夹条目（后端原始记录）。
 * @typedef {object} RawShareFileRecord
 * @property {string|number} [fileId]
 * @property {string} [fileName]
 * @property {boolean} [isFolder]
 * @property {number} [fileType]
 * @property {number|string} [category]
 * @property {number|string} [size]
 * @property {string|null} [modifyTime]
 * @property {boolean} [invalid]
 * @property {number|boolean} [deleted]
 * @property {string} [invalidText]
 */

const SHARE_BRAND_NAME = '指绣云章'
const SHARE_PASSWORD_PATTERN = /^[A-Za-z0-9]{4}$/

/**
 * 分享状态编号 → 文案，编号取自 `@/constants/shareStatus` 的 `SHARE_STATUS`（唯一权威来源）。
 * JS 取对象属性恒按字符串匹配，查找处统一 String() 归一：
 * `undefined` 仍会落到不存在的键（由 `|| '-'` 兜底），不会被误当成 0 号「生效中」。
 * @type {Readonly<Record<string, string>>}
 */
export const SHARE_STATUS_MAP = {
  [SHARE_STATUS.ACTIVE]: '生效中',
  [SHARE_STATUS.CANCELLED]: '已取消',
  [SHARE_STATUS.EXPIRED]: '已过期',
  [SHARE_STATUS.EXHAUSTED]: '次数用尽',
}

export const SHARE_EXPIRE_OPTIONS = [
  { label: '1天', value: '1d' },
  { label: '7天', value: '7d' },
  { label: '30天', value: '30d' },
  { label: '永久有效', value: 'forever' },
]

/** @returns {string} */
export function generateSharePassword() {
  // 排除易混淆字符：I/l/1、O/0
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  let password = ''

  while (password.length < 4) {
    password += chars[Math.floor(Math.random() * chars.length)]
  }

  return password
}

/**
 * @param {unknown} [value]
 * @returns {string}
 */
export function sanitizeSharePassword(value) {
  return String(value || '')
    .replace(/[^A-Za-z0-9]/g, '')
    .slice(0, 4)
}

/**
 * @param {unknown} [value]
 * @returns {boolean}
 */
export function isValidSharePassword(value) {
  return SHARE_PASSWORD_PATTERN.test(String(value || ''))
}

/**
 * @param {Array<{ fileName?: string }>} [items]
 * @returns {string}
 */
export function getShareTargetTitle(items = []) {
  if (!items.length) {
    return ''
  }

  if (items.length === 1) {
    return items[0].fileName || ''
  }

  return `${items[0].fileName || '所选内容'}等 ${items.length} 项`
}

/**
 * @param {string} shareUrl
 * @param {string} [password]
 * @returns {string}
 */
export function buildShareMessage(shareUrl, password) {
  if (password) {
    return `${SHARE_BRAND_NAME}给你分享了文件：${shareUrl}，提取码为：${password}`
  }

  return `${SHARE_BRAND_NAME}给你分享了文件：${shareUrl}`
}

/**
 * @param {Pick<RawShareRecord, 'expireType'|'expireTime'>} [record]
 * @returns {string}
 */
export function formatShareExpireText(record = {}) {
  if (record.expireType === 'forever' || !record.expireTime) {
    return '永久有效'
  }

  return fmtTime(record.expireTime)
}

/**
 * @param {RawShareRecord} [item]
 */
export function mapMyShareRecord(item = {}) {
  return {
    shareId: item.shareId,
    shareKey: item.shareKey || '',
    shareUrl: item.shareUrl || '',
    hasPassword: Boolean(item.hasPassword),
    expireType: item.expireType || '',
    expireTime: item.expireTime ?? null,
    maxAccessCount: item.maxAccessCount ?? 0,
    currentAccessCount: item.currentAccessCount ?? 0,
    status: item.status ?? 0,
    statusText: item.statusText || SHARE_STATUS_MAP[String(item.status)] || '-',
    createTime: item.createTime ?? null,
  }
}

/**
 * @param {{ total?: number|string|null, rows?: RawShareRecord[] }} [data]
 */
export function mapMyShareRecords(data = {}) {
  return {
    total: Number(data?.total) || 0,
    rows: Array.isArray(data?.rows) ? data?.rows.map(mapMyShareRecord) : [],
  }
}

/**
 * @param {RawShareFileRecord} [item]
 */
export function mapShareFileEntry(item = {}) {
  const type = normalizeFileType({
    type: item.isFolder || item.fileType === 0 ? 0 : 1,
    isFolder: item.isFolder,
  })

  return createDisplayFileEntry({
    id: item.fileId,
    fileName: item.fileName || '',
    type,
    category: item.category,
    fileSize: item.size,
    timeValue: item.modifyTime ?? null,
    extra: {
      invalid: Boolean(item.invalid || Number(item.deleted) !== 0),
      invalidText: item.invalidText || (Number(item.deleted) !== 0 ? '已失效' : ''),
    },
  })
}

/**
 * @param {unknown} [data]
 */
export function mapShareFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapShareFileEntry)
}

/**
 * @param {string} [path]
 */
export function splitSharePath(path) {
  return parseCrumbs(path, { decode: false })
}
