function createUploadSuccessResult({
  originalName,
  finalName,
  type = 1,
  size = 0,
  id = null,
  parentId = null,
  fileUrl = '',
  extra = {},
}) {
  return {
    status: 'SUCCESS',
    originalName,
    finalName: finalName || originalName,
    renamed: Boolean(finalName && finalName !== originalName),
    type,
    size,
    id,
    parentId,
    fileUrl,
    message: '',
    ...extra,
  }
}

export function createUploadFailResult({
  originalName,
  finalName = '',
  type = 1,
  size = 0,
  message = '',
  extra = {},
}) {
  return {
    status: 'FAILED',
    originalName,
    finalName: finalName || originalName,
    renamed: Boolean(finalName && finalName !== originalName),
    type,
    size,
    id: null,
    parentId: null,
    fileUrl: '',
    message,
    ...extra,
  }
}

export function normalizeFolderCreateResult(responseData, requestedName, parentId) {
  if (responseData && typeof responseData === 'object' && !Array.isArray(responseData)) {
    return createUploadSuccessResult({
      originalName: responseData.originalName || requestedName,
      finalName: responseData.finalName || responseData.originalName || requestedName,
      type: responseData.fileType ?? 0,
      size: 0,
      id: responseData.id ?? null,
      parentId: responseData.parentId ?? parentId,
    })
  }

  return createUploadSuccessResult({
    originalName: requestedName,
    finalName: requestedName,
    type: 0,
    size: 0,
    id: responseData ?? null,
    parentId,
  })
}

export function normalizeUploadConfirmResult(
  responseData,
  { originalName, fileSize, parentId, clientRequestId },
) {
  if (responseData && typeof responseData === 'object' && !Array.isArray(responseData)) {
    const batchItem = Array.isArray(responseData.items) ? responseData.items[0] : null

    if (batchItem && typeof batchItem === 'object') {
      const itemStatus = String(batchItem.status || '').toLowerCase()
      const itemCode = batchItem.code

      if (itemStatus && itemStatus !== 'success') {
        throw new Error(batchItem.msg || '上传确认失败')
      }

      if (typeof itemCode === 'number' && itemCode !== 1) {
        throw new Error(batchItem.msg || '上传确认失败')
      }

      return createUploadSuccessResult({
        originalName: batchItem.clientOriginalName || batchItem.originalName || originalName,
        finalName:
          batchItem.finalName ||
          batchItem.clientOriginalName ||
          batchItem.originalName ||
          originalName,
        type: batchItem.fileType ?? 1,
        size: batchItem.fileSize ?? fileSize,
        id: batchItem.fileId ?? batchItem.id ?? null,
        parentId: batchItem.parentId ?? parentId,
        fileUrl: batchItem.fileUrl || '',
        extra: {
          clientRequestId: batchItem.clientRequestId || clientRequestId || '',
        },
      })
    }

    if (responseData.items && !batchItem) {
      throw new Error('上传确认结果为空')
    }

    // 兼容旧接口仍返回单对象的情况，避免联调环境不一致导致前端再次报错。
    return createUploadSuccessResult({
      originalName: responseData.originalName || originalName,
      finalName: responseData.finalName || responseData.originalName || originalName,
      type: responseData.fileType ?? 1,
      size: responseData.fileSize ?? fileSize,
      id: responseData.id ?? null,
      parentId: responseData.parentId ?? parentId,
      fileUrl: responseData.fileUrl || '',
      extra: {
        clientRequestId: responseData.clientRequestId || clientRequestId || '',
      },
    })
  }

  return createUploadSuccessResult({
    originalName,
    finalName: originalName,
    type: 1,
    size: fileSize,
    parentId,
    fileUrl: typeof responseData === 'string' ? responseData : '',
    extra: {
      clientRequestId: clientRequestId || '',
    },
  })
}

export { createUploadSuccessResult }
