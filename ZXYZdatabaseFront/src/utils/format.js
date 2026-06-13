export function fmtTime(iso) {
  if (!iso) {
    return '-'
  }

  const rawValue = String(iso).trim()
  if (!rawValue) {
    return '-'
  }

  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(rawValue)) {
    return rawValue
  }

  const parsedDate = new Date(rawValue)
  if (Number.isNaN(parsedDate.getTime())) {
    return rawValue
  }

  return parsedDate.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
}

export function formatSize(size) {
  if (size === null || size === undefined || size === '') {
    return '-'
  }

  const bytes = Number(size)
  if (Number.isNaN(bytes) || bytes < 0) {
    return '-'
  }
  if (bytes === 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)

  if (unitIndex === 0) {
    return `${bytes} B`
  }

  return `${(bytes / Math.pow(1024, unitIndex)).toFixed(2)} ${units[unitIndex]}`
}

export const GB = 1024 * 1024 * 1024

export function formatStorageText(value) {
  if (value == null) {
    return '--'
  }
  return formatSize(Number(value || 0))
}
