import { splitFileName } from '@/utils/fileName'

const FILE_ENTRY_TYPES = {
  FOLDER: 0,
  FILE: 1,
}

const FILE_CATEGORY_ICONS = {
  0: '#icon-yasuobao',
  1: '#icon-word',
  2: '#icon-ppt',
  3: '#icon-excel',
  4: '#icon-pdf',
  5: '#icon-jpg',
  6: '#icon-mp',
  7: '#icon-mp4',
  8: '#icon-txt',
}

const FILE_EXTENSION_CATEGORY_MAP = {
  0: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'tgz', 'tar.gz'],
  1: ['doc', 'docx', 'wps'],
  2: ['ppt', 'pptx', 'pps', 'ppsx'],
  3: ['xls', 'xlsx', 'csv', 'et'],
  4: ['pdf'],
  5: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'heic'],
  6: ['mp3', 'wav', 'aac', 'flac', 'ogg', 'm4a', 'wma'],
  7: ['mp4', 'avi', 'mkv', 'mov', 'wmv', 'flv', 'webm', 'm4v'],
  8: ['txt', 'md', 'log', 'json', 'xml', 'yaml', 'yml'],
}

const DEFAULT_FILE_ICON = '#icon-wenjianlei_weizhiwenjian'
const FOLDER_ICON = '#icon-wenjianjia'

const EXTENSION_CATEGORY_LOOKUP = Object.entries(FILE_EXTENSION_CATEGORY_MAP).reduce(
  (result, [category, extensions]) => {
    extensions.forEach((extension) => {
      result[extension] = Number(category)
    })
    return result
  },
  {},
)

function normalizeNumericValue(value) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : null
}

function getNormalizedExtensionParts(fileName) {
  const normalizedFileName = String(fileName || '').toLowerCase()

  if (!normalizedFileName) {
    return []
  }

  if (normalizedFileName.endsWith('.tar.gz')) {
    return ['tar.gz', 'gz']
  }

  if (normalizedFileName.endsWith('.tar.bz2')) {
    return ['tar.bz2', 'bz2']
  }

  if (normalizedFileName.endsWith('.tar.xz')) {
    return ['tar.xz', 'xz']
  }

  const { extension } = splitFileName(normalizedFileName)
  if (!extension) {
    return []
  }

  return [extension.slice(1)]
}

function inferCategoryFromFileName(fileName) {
  const extensionParts = getNormalizedExtensionParts(fileName)

  for (const extensionPart of extensionParts) {
    const matchedCategory = EXTENSION_CATEGORY_LOOKUP[extensionPart]
    if (matchedCategory !== undefined) {
      return matchedCategory
    }
  }

  return null
}

export function normalizeFileType({ type, isFolder } = {}) {
  return type === FILE_ENTRY_TYPES.FOLDER || Boolean(isFolder)
    ? FILE_ENTRY_TYPES.FOLDER
    : FILE_ENTRY_TYPES.FILE
}

export function resolveFileCategory(options = {}) {
  const {
    fileName = '',
    category = null,
    fallbackCategory = null,
    preferFileName = false,
  } = options

  const inferredCategory = inferCategoryFromFileName(fileName)
  const normalizedCategory = normalizeNumericValue(category)
  const normalizedFallbackCategory = normalizeNumericValue(fallbackCategory)

  if (preferFileName && inferredCategory !== null) {
    return inferredCategory
  }

  if (normalizedCategory !== null) {
    return normalizedCategory
  }

  if (inferredCategory !== null) {
    return inferredCategory
  }

  if (normalizedFallbackCategory !== null) {
    return normalizedFallbackCategory
  }

  return null
}

export function createDisplayFileEntry(data = {}, options = {}) {
  const {
    id,
    fileName = '',
    type,
    isFolder = false,
    category = null,
    fallbackCategory = null,
    fileSize,
    timeFieldName = 'modifyTime',
    timeValue = '',
    extra = {},
  } = data
  const { preferFileNameCategory = false } = options
  const normalizedType = normalizeFileType({ type, isFolder })

  return {
    id,
    fileName: String(fileName || ''),
    type: normalizedType,
    category:
      normalizedType === FILE_ENTRY_TYPES.FOLDER
        ? null
        : resolveFileCategory({
            fileName,
            category,
            fallbackCategory,
            preferFileName: preferFileNameCategory,
          }),
    fileSize,
    [timeFieldName]: timeValue,
    ...extra,
  }
}

export function getFileIcon(row = {}) {
  if (normalizeFileType({ type: row.type }) === FILE_ENTRY_TYPES.FOLDER) {
    return FOLDER_ICON
  }

  return FILE_CATEGORY_ICONS[row.category] || DEFAULT_FILE_ICON
}

export function splitFileNameParts(row = {}) {
  if (normalizeFileType({ type: row.type }) === FILE_ENTRY_TYPES.FOLDER) {
    const fullName = String(row.fileName || '')
    return {
      baseName: fullName,
      extension: '',
      fullName,
    }
  }

  return splitFileName(row.fileName || '')
}
