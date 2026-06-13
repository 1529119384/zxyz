export const MAX_FILE_SIZE = 1 * 1024 * 1024 * 1024 // 1GB — aligned with backend spring.servlet.multipart.max-file-size

export const DANGEROUS_EXTENSIONS = new Set([
  'exe',
  'bat',
  'cmd',
  'sh',
  'ps1',
  'vbs',
  'msi',
  'dll',
  'com',
  'scr',
  'js',
  'jar',
  'war',
  'app',
  'deb',
  'rpm',
  'dmg',
  'pkg',
  'apk',
])

export function resolveExtension(fileName = '') {
  const dotIndex = fileName.lastIndexOf('.')
  return dotIndex >= 0 && dotIndex < fileName.length - 1
    ? fileName.slice(dotIndex + 1).toLowerCase()
    : ''
}

export function validateFiles(files) {
  const valid = []
  const rejected = []

  for (const file of files) {
    if (file.size > MAX_FILE_SIZE) {
      rejected.push(`${file.name}（超过 1GB）`)
      continue
    }

    const extension = resolveExtension(file.name)
    if (extension && DANGEROUS_EXTENSIONS.has(extension)) {
      rejected.push(`${file.name}（可执行文件）`)
      continue
    }

    valid.push(file)
  }

  return { valid, rejected }
}
