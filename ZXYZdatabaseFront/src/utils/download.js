function triggerDownload(href, fileName, options = {}) {
  const link = document.createElement('a')

  link.href = href
  link.download = fileName || ''

  if (options.target) {
    link.target = options.target
  }

  if (options.rel) {
    link.rel = options.rel
  }

  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

export function triggerDownloadByUrl(url, fileName, options = {}) {
  triggerDownload(url, fileName, options)
}

export function triggerDownloadByBlob(blob, fileName, options = {}) {
  const objectUrl = URL.createObjectURL(blob)

  triggerDownload(objectUrl, fileName, options)
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}

export async function downloadBlobByUrl(url, fileName, options = {}) {
  const response = await fetch(url, {
    credentials: 'same-origin',
  })

  if (!response.ok) {
    throw new Error(`下载失败，HTTP 状态码：${response.status}`)
  }

  const contentType = response.headers.get('content-type') || ''
  if (contentType.toLowerCase().includes('text/html')) {
    throw new Error('下载地址返回了页面，请检查下载签名或反向代理配置')
  }

  const blob = await response.blob()
  triggerDownloadByBlob(blob, fileName, options)
}
