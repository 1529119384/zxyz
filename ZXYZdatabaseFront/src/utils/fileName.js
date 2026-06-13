export function splitFileName(fileName) {
  const normalizedFileName = String(fileName || '')
  const lastDotIndex = normalizedFileName.lastIndexOf('.')

  // 隐藏文件和以点结尾的名称不应误判为扩展名，保留完整名称。
  if (!normalizedFileName || lastDotIndex <= 0 || lastDotIndex === normalizedFileName.length - 1) {
    return {
      baseName: normalizedFileName,
      extension: '',
      fullName: normalizedFileName,
    }
  }

  return {
    baseName: normalizedFileName.slice(0, lastDotIndex),
    extension: normalizedFileName.slice(lastDotIndex),
    fullName: normalizedFileName,
  }
}
