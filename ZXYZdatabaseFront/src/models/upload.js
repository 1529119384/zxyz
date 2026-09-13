// @ts-check
/**
 * 构造"上传/新建成功"的统一结果对象。
 * @param {object} options
 * @param {string} [options.originalName] 用户可见的原始文件名。
 * @param {string} [options.finalName] 服务端重命名后的文件名；为空则回退 originalName。
 * @param {number} [options.type] 1=文件，0=文件夹。
 * @param {number} [options.size]
 * @param {string|number|null} [options.id]
 * @param {string|number|null} [options.parentId]
 * @param {string} [options.fileUrl]
 * @param {Record<string, any>} [options.extra] 额外字段，原样展开进结果。
 * @returns {Record<string, any>}
 */
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

/**
 * 构造"上传失败"的统一结果对象（字段与成功结果保持同形，便于列表直接消费）。
 * @param {object} options
 * @param {string} [options.originalName]
 * @param {string} [options.finalName]
 * @param {number} [options.type]
 * @param {number} [options.size]
 * @param {string} [options.message]
 * @param {Record<string, any>} [options.extra]
 * @returns {Record<string, any>}
 */
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

/**
 * 把"新建文件夹"的响应归一化为统一结果：新接口返回对象，旧接口可能直接返回 id。
 * @param {any} responseData
 * @param {string} requestedName
 * @param {string|number|null} [parentId]
 * @returns {Record<string, any>}
 */
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

/**
 * 把"上传确认"的响应归一化为统一结果，兼容三种形态：批量信封（items[0]）、
 * 旧版单对象、以及仅返回 fileUrl 字符串的极简响应。
 * @param {any} responseData
 * @param {object} uploadMeta
 * @param {string} [uploadMeta.originalName]
 * @param {number} [uploadMeta.fileSize]
 * @param {string|number|null} [uploadMeta.parentId]
 * @param {string} [uploadMeta.clientRequestId]
 * @returns {Record<string, any>}
 */
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
