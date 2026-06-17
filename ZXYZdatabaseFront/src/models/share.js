import { createDisplayFileEntry, normalizeFileType } from '@/models/filePresentation'
import { fmtTime } from '@/utils/format'
import { parseCrumbs } from '@/utils/pathUtils'

const SHARE_BRAND_NAME = '指绣云章'
const SHARE_PASSWORD_PATTERN = /^[A-Za-z0-9]{4}$/

export const SHARE_STATUS_MAP = {
  0: '生效中',
  1: '已取消',
  2: '已过期',
  3: '次数用尽',
}

export const SHARE_EXPIRE_OPTIONS = [
  { label: '1天', value: '1d' },
  { label: '7天', value: '7d' },
  { label: '30天', value: '30d' },
  { label: '永久有效', value: 'forever' },
]

export function generateSharePassword() {
  // 排除易混淆字符：I/l/1、O/0
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  let password = ''

  while (password.length < 4) {
    password += chars[Math.floor(Math.random() * chars.length)]
  }

  return password
}

export function sanitizeSharePassword(value) {
  return String(value || '')
    .replace(/[^A-Za-z0-9]/g, '')
    .slice(0, 4)
}

export function isValidSharePassword(value) {
  return SHARE_PASSWORD_PATTERN.test(String(value || ''))
}

export function getShareTargetTitle(items = []) {
  if (!items.length) {
    return ''
  }

  if (items.length === 1) {
    return items[0].fileName || ''
  }

  return `${items[0].fileName || '所选内容'}等 ${items.length} 项`
}

export function buildShareMessage(shareUrl, password) {
  if (password) {
    return `${SHARE_BRAND_NAME}给你分享了文件：${shareUrl}，提取码为：${password}`
  }

  return `${SHARE_BRAND_NAME}给你分享了文件：${shareUrl}`
}

export function formatShareExpireText(record = {}) {
  if (record.expireType === 'forever' || !record.expireTime) {
    return '永久有效'
  }

  return fmtTime(record.expireTime)
}

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
    statusText: item.statusText || SHARE_STATUS_MAP[item.status] || '-',
    createTime: item.createTime ?? null,
  }
}

export function mapMyShareRecords(data = {}) {
  return {
    total: Number(data?.total) || 0,
    rows: Array.isArray(data?.rows) ? data.rows.map(mapMyShareRecord) : [],
  }
}

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

export function mapShareFileEntries(data = []) {
  return (Array.isArray(data) ? data : []).map(mapShareFileEntry)
}

export function splitSharePath(path) {
  return parseCrumbs(path, { decode: false })
}
