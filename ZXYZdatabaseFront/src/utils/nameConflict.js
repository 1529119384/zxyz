import { splitFileName } from '@/utils/fileName'

const FILE_TYPE = {
  FOLDER: 0,
  FILE: 1,
}

function normalizeName(value) {
  return String(value || '').trim()
}

function getEntryName(entry) {
  if (!entry || typeof entry !== 'object') {
    return ''
  }

  return normalizeName(entry.finalName || entry.fileName || entry.originalName || entry.name)
}

function getEntryType(entry) {
  if (!entry || typeof entry !== 'object') {
    return null
  }

  if (entry.type === FILE_TYPE.FOLDER || entry.fileType === FILE_TYPE.FOLDER) {
    return FILE_TYPE.FOLDER
  }

  if (entry.type === FILE_TYPE.FILE || entry.fileType === FILE_TYPE.FILE) {
    return FILE_TYPE.FILE
  }

  return null
}

function createFolderName(baseName, index) {
  return index === 0 ? baseName : `${baseName}(${index})`
}

function createFileName(baseName, extension, index) {
  if (index === 0) {
    return `${baseName}${extension}`
  }

  return `${baseName}(${index})${extension}`
}

export function collectExistingNames(entries = [], targetType) {
  const nameSet = new Set()

  entries.forEach((entry) => {
    if (getEntryType(entry) !== targetType) {
      return
    }

    const entryName = getEntryName(entry)
    if (!entryName) {
      return
    }

    nameSet.add(entryName)
  })

  return nameSet
}

export function resolveUniqueName(
  name,
  existingEntries = [],
  targetType = FILE_TYPE.FILE,
  reservedNames = [],
) {
  const normalizedName = normalizeName(name)
  if (!normalizedName) {
    return ''
  }

  const existingNames = collectExistingNames(existingEntries, targetType)
  reservedNames.forEach((reservedName) => {
    const normalizedReservedName = normalizeName(reservedName)
    if (normalizedReservedName) {
      existingNames.add(normalizedReservedName)
    }
  })

  if (targetType === FILE_TYPE.FOLDER) {
    let nextIndex = 0
    let nextName = createFolderName(normalizedName, nextIndex)

    while (existingNames.has(nextName)) {
      nextIndex += 1
      nextName = createFolderName(normalizedName, nextIndex)
    }

    return nextName
  }

  const { baseName, extension } = splitFileName(normalizedName)
  let nextIndex = 0
  let nextName = createFileName(baseName, extension, nextIndex)

  while (existingNames.has(nextName)) {
    nextIndex += 1
    nextName = createFileName(baseName, extension, nextIndex)
  }

  return nextName
}

export function buildBatchPredictedNames(
  files = [],
  existingEntries = [],
  targetType = FILE_TYPE.FILE,
) {
  const reservedNames = []

  return files.map((file) => {
    const originalName = normalizeName(file?.name)
    const predictedName = resolveUniqueName(
      originalName,
      existingEntries,
      targetType,
      reservedNames,
    )

    if (predictedName) {
      reservedNames.push(predictedName)
    }

    return {
      originalName,
      predictedName,
      renamed: Boolean(predictedName && predictedName !== originalName),
    }
  })
}

export { FILE_TYPE }
