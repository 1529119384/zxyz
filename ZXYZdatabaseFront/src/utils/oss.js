// OSS 直传使用原生 XHR，和应用自身后端 axios 请求隔离，避免拦截器干扰直传。
const DEFAULT_UPLOAD_TIMEOUT = 5 * 60 * 1000

export function uploadToOss(uploadUrl, file, options = {}) {
  const { onUploadProgress, contentType, contentDisposition, timeout } = options

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()

    xhr.open('PUT', uploadUrl, true)
    xhr.timeout = timeout ?? DEFAULT_UPLOAD_TIMEOUT

    if (contentType) {
      xhr.setRequestHeader('Content-Type', contentType)
    }
    if (contentDisposition) {
      xhr.setRequestHeader('Content-Disposition', contentDisposition)
    }

    if (typeof onUploadProgress === 'function') {
      xhr.upload.onprogress = (event) => {
        onUploadProgress({
          loaded: event.loaded,
          total: event.total || file.size,
        })
      }
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.response)
        return
      }

      const error = new Error(`OSS upload failed: status ${xhr.status}`)
      error.response = {
        status: xhr.status,
        data: xhr.responseText,
      }
      reject(error)
    }

    xhr.onerror = () => {
      const error = new Error('OSS upload failed: network error')
      error.response = {
        status: xhr.status || 0,
        data: xhr.responseText,
      }
      reject(error)
    }

    xhr.ontimeout = () => {
      const error = new Error('OSS upload failed: timeout')
      error.response = {
        status: 0,
        data: null,
      }
      reject(error)
    }

    xhr.send(file)
  })
}
