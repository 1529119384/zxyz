// @ts-check
import { splitFileName } from '@/utils/fileName'

/**
 * 文件条目类型（与后端 fileType 对齐：0=文件夹、1=文件）。
 * @type {Readonly<{ FOLDER: 0, FILE: 1 }>}
 */
const FILE_ENTRY_TYPES = {
  FOLDER: 0,
  FILE: 1,
}

/**
 * 分类编号 → 图标 id（键为后端 category 0..8）。
 * JS 取对象属性恒按字符串匹配，故声明成字符串索引签名，查找处统一 String() 归一：
 * `undefined`/`null` 仍会命中不存在的键（落回默认图标），不会被误映射到 0 号分类。
 * @type {Readonly<Record<string, string>>}
 */
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

/**
 * 分类编号 → 该分类下的扩展名清单。
 * @type {Readonly<Record<string, string[]>>}
 */
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

/**
 * 扩展名 → 分类编号 的反查表（由 FILE_EXTENSION_CATEGORY_MAP 展开）。
 * 值刻意保留 `undefined`：查不到扩展名是正常分支，调用方据此继续降级。
 * @type {Record<string, number|undefined>}
 */
const EXTENSION_CATEGORY_LOOKUP = Object.entries(FILE_EXTENSION_CATEGORY_MAP).reduce(
  (result, [category, extensions]) => {
    extensions.forEach((extension) => {
      result[extension] = Number(category)
    })
    return result
  },
  /** @type {Record<string, number|undefined>} */ ({}),
)

/**
 * @param {unknown} value
 * @returns {number|null}
 */
function normalizeNumericValue(value) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : null
}

/**
 * @param {string} [fileName]
 * @returns {string[]}
 */
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

/**
 * @param {string} [fileName]
 * @returns {number|null}
 */
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

/**
 * @param {{ type?: number|null, isFolder?: boolean|null }} [options]
 * @returns {0|1}
 */
export function normalizeFileType({ type, isFolder } = {}) {
  return type === FILE_ENTRY_TYPES.FOLDER || Boolean(isFolder)
    ? FILE_ENTRY_TYPES.FOLDER
    : FILE_ENTRY_TYPES.FILE
}

/**
 * 分类优先级：preferFileName 时文件名优先，否则显式 category > 文件名推断 > fallbackCategory。
 * @param {{
 *   fileName?: string,
 *   category?: number|string|null,
 *   fallbackCategory?: number|string|null,
 *   preferFileName?: boolean
 * }} [options]
 * @returns {number|null}
 */
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

/**
 * 把后端文件记录收敛成统一的展示条目（空间列表/搜索/回收站/分享共用）。
 * 仅吃这里列出的字段，额外字段靠 options.extra 原样透传。
 * @param {{
 *   id?: string|number|null,
 *   fileName?: string,
 *   type?: number|null,
 *   isFolder?: boolean|null,
 *   category?: number|string|null,
 *   fallbackCategory?: number|string|null,
 *   fileSize?: number|string|null,
 *   timeFieldName?: string,
 *   timeValue?: string|number|null,
 *   extra?: Record<string, unknown>
 * }} [data]
 * @param {{ preferFileNameCategory?: boolean }} [options]
 */
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

/**
 * @param {{ type?: number|null, category?: number|string|null }} [row]
 * @returns {string}
 */
export function getFileIcon(row = {}) {
  if (normalizeFileType({ type: row.type }) === FILE_ENTRY_TYPES.FOLDER) {
    return FOLDER_ICON
  }

  return FILE_CATEGORY_ICONS[String(row.category)] || DEFAULT_FILE_ICON
}

/**
 * @param {{ type?: number|null, fileName?: string }} [row]
 * @returns {{ baseName: string, extension: string, fullName: string }}
 */
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
