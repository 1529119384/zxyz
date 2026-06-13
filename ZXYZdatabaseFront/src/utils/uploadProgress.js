export function getUploadTrackingKey(file) {
  if (file?.webkitRelativePath) {
    return file.webkitRelativePath
  }

  return `${file?.name || 'unknown'}_${file?.size || 0}_${file?.lastModified || 0}`
}

export function sumUploadedBytes(progressMap = {}) {
  return Object.values(progressMap).reduce((total, loaded) => total + (loaded || 0), 0)
}

export function calculateUploadPercentage(uploadedBytes, totalBytes, options = {}) {
  const { allowComplete = false } = options

  if (!totalBytes) {
    return allowComplete ? 100 : 0
  }

  const rawPercentage = Math.round((uploadedBytes / totalBytes) * 100)
  return Math.min(rawPercentage, allowComplete ? 100 : 99)
}
