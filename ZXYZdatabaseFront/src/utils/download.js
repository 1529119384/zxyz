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

/**
 * 拦截「本该是文件、实际返回了 HTML」的响应。
 *
 * 预签名地址过期 / 反向代理配置错误时，中间层会回一个 HTML 错误页且 HTTP 状态仍是 200，
 * 只判 `response.ok` 会把它当成文件内容吞下去（单文件下载会存成坏文件、打包下载会把
 * 错误页压进 zip）。抽成共享函数是为了让「流式打包」这类不能整块 buffer 的场景也能复用
 * 同一道校验，避免两处逻辑各自漂移（审计 12-第三节低危项）。
 *
 * @param {Response} response fetch 得到的响应
 * @param {string} [message] 自定义错误文案
 * @throws {Error} 当 content-type 表明响应体是 HTML 时
 */
export function assertNotHtmlResponse(
  response,
  message = '下载地址返回了页面，请检查下载签名或反向代理配置',
) {
  const contentType = response?.headers?.get?.('content-type') || ''
  if (contentType.toLowerCase().includes('text/html')) {
    throw new Error(message)
  }
}

export async function downloadBlobByUrl(url, fileName, options = {}) {
  const response = await fetch(url, {
    credentials: 'same-origin',
  })

  if (!response.ok) {
    throw new Error(`下载失败，HTTP 状态码：${response.status}`)
  }

  assertNotHtmlResponse(response)

  const blob = await response.blob()
  triggerDownloadByBlob(blob, fileName, options)
}
