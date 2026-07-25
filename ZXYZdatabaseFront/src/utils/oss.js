// OSS 直传使用原生 XHR，和应用自身后端 axios 请求隔离，避免拦截器干扰直传。
const DEFAULT_UPLOAD_TIMEOUT = 5 * 60 * 1000

export function uploadToOss(uploadUrl, file, options = {}) {
  const { contentType, contentDisposition, onUploadProgress } = options

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()

    xhr.open('PUT', uploadUrl, true)
    xhr.timeout = DEFAULT_UPLOAD_TIMEOUT

    if (onUploadProgress) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) {
          onUploadProgress({
            loaded: event.loaded,
            total: event.total,
            percent: Math.round((event.loaded / event.total) * 100),
          })
        }
      }
    }

    if (contentType) {
      xhr.setRequestHeader('Content-Type', contentType)
    }
    if (contentDisposition) {
      xhr.setRequestHeader('Content-Disposition', contentDisposition)
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }

      const error = new Error(`OSS upload failed: status ${xhr.status}`)
      error.response = { status: xhr.status, data: xhr.responseText }
      reject(error)
    }

    xhr.onerror = () => {
      const error = new Error('OSS upload failed: network error')
      error.response = { status: xhr.status || 0, data: xhr.responseText }
      reject(error)
    }

    xhr.ontimeout = () => {
      const error = new Error('OSS upload failed: timeout')
      error.response = { status: 0, data: null }
      reject(error)
    }

    xhr.send(file)
  })
}

/**
 * 后端直传上传（用于本地存储等非预签名存储提供者）。
 * 将文件直接 POST 到后端上传接口。
 */
export function uploadToBackend(file, parentId, teamId, spaceType, projectId) {
  const formData = new FormData()
  formData.append('file', file)
  if (parentId != null) formData.append('parentId', String(parentId))
  if (teamId != null) formData.append('teamId', String(teamId))
  if (spaceType != null) formData.append('spaceType', String(spaceType))
  if (projectId != null) formData.append('projectId', String(projectId))

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()

    xhr.open('POST', '/api/files/uploads/direct', true)
    xhr.timeout = DEFAULT_UPLOAD_TIMEOUT

    // 让浏览器自动设置 Content-Type（含 boundary）
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText))
        } catch (e) {
          resolve(xhr.responseText)
        }
        return
      }

      const error = new Error(`Backend upload failed: status ${xhr.status}`)
      error.response = {
        status: xhr.status,
        data: xhr.responseText,
      }
      reject(error)
    }

    xhr.onerror = () => {
      const error = new Error('Backend upload failed: network error')
      error.response = {
        status: xhr.status || 0,
        data: xhr.responseText,
      }
      reject(error)
    }

    xhr.ontimeout = () => {
      const error = new Error('Backend upload failed: timeout')
      error.response = {
        status: 0,
        data: null,
      }
      reject(error)
    }

    xhr.send(formData)
  })
}
