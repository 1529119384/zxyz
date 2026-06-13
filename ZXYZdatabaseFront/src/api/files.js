import { mapRecycleFileEntries, mapSearchFileEntries, mapSpaceFileEntries } from '@/models/file'
import request, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

const buildFileListParams = (parentId, sortOptions = {}) => {
  const params = { parentId }
  const { sortField, sortOrder, teamId, spaceType, projectId } = sortOptions

  if (teamId) {
    params.teamId = teamId
  }
  if (spaceType) {
    params.spaceType = spaceType
  }
  if (projectId) {
    params.projectId = projectId
  }

  if (sortField) {
    params.sortField = sortField
  }

  if (sortOrder) {
    params.sortOrder = sortOrder
  }

  return params
}

export const fetchFileList = async (parentId, sortOptions = {}) => {
  const { signal, ...restSortOptions } = sortOptions
  const response = await request.get('/api/files', {
    params: buildFileListParams(parentId, restSortOptions),
    signal,
  })

  return {
    ...response,
    data: mapSpaceFileEntries(response?.data),
  }
}

export const searchFiles = async (keyword, page = 1, pageSize = 20, options = {}) => {
  const { signal, ...paramsOptions } = options
  const response = await request.get('/api/files/search', {
    params: {
      keyword,
      page,
      pageSize,
      ...(paramsOptions.teamId ? { teamId: paramsOptions.teamId } : {}),
      ...(paramsOptions.spaceType ? { spaceType: paramsOptions.spaceType } : {}),
      ...(paramsOptions.projectId ? { projectId: paramsOptions.projectId } : {}),
    },
    signal,
  })

  return {
    ...response,
    data: mapSearchFileEntries(response?.data),
  }
}

export const getFileDownloadUrl = (fileId) => {
  return request.get(`/api/files/${fileId}/download-url`)
}

export const getUploadSign = (originalName) => {
  return request.post('/api/files/uploads', null, {
    params: { originalName },
    // 上传签名和确认上传可能依赖外部存储或批量入库，允许更长等待时间。
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })
}

export const confirmUpload = ({
  objectKey,
  originalName,
  fileSize,
  parentId,
  teamId,
  spaceType,
  projectId,
  batchId,
  clientRequestId,
}) => {
  return request.post(
    '/api/files/uploads/confirmations',
    {
      ...(teamId ? { teamId } : {}),
      ...(spaceType ? { spaceType } : {}),
      ...(projectId ? { projectId } : {}),
      files: [
        {
          objectKey,
          originalName,
          fileSize,
          parentId,
          ...(batchId ? { batchId } : {}),
          ...(clientRequestId ? { clientRequestId } : {}),
        },
      ],
    },
    {
      timeout: UPLOAD_REQUEST_TIMEOUT,
    },
  )
}

export const fetchStorageUsage = ({ spaceType, teamId, projectId } = {}) => {
  return request.get('/api/storage/usage', {
    params: {
      ...(spaceType ? { spaceType } : {}),
      ...(teamId ? { teamId } : {}),
      ...(projectId ? { projectId } : {}),
    },
  })
}

export const createFolder = ({ folderName, parentId, teamId, spaceType, projectId }) => {
  return request.post('/api/folders', {
    folderName,
    parentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })
}

export const renameFile = ({ fileId, newName }) => {
  return request.patch(`/api/files/${fileId}`, {
    fileId,
    newName,
  })
}

export const moveFiles = async ({ fileIds, targetParentId, teamId, spaceType, projectId }) => {
  const response = await request.patch('/api/files', {
    fileIds,
    targetParentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })

  return response?.data
}

export const copyFiles = async ({ fileIds, targetParentId, teamId, spaceType, projectId }) => {
  const response = await request.post('/api/files/copies', {
    fileIds,
    targetParentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })

  return response?.data
}

export const logicalDeleteFiles = (fileIds) => {
  return request.patch('/api/files/trash', { fileIds })
}

export const fetchRecycleList = async (options = {}) => {
  const response = await request.get('/api/trash/files', {
    params: {
      ...(options.teamId ? { teamId: options.teamId } : {}),
      ...(options.spaceType ? { spaceType: options.spaceType } : {}),
      ...(options.projectId ? { projectId: options.projectId } : {}),
    },
  })

  return {
    ...response,
    data: mapRecycleFileEntries(response?.data),
  }
}

export const restoreFiles = (fileIds) => {
  return request.delete('/api/files/trash', { data: { fileIds } })
}

export const deleteFilesForever = (fileIds) => {
  return request.delete('/api/files', { data: { fileIds } })
}
