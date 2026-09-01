import { mapRecycleFileEntries, mapSearchFileEntries, mapSpaceFileEntries } from '@/models/file'
import rawRequest, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

// axios 实例的响应拦截器会把成功响应解包为后端信封 { code, msg, data }（见
// src/utils/createApiClient.js：code === 1 时返回整个 payload），与原生 AxiosResponse
// 类型不符。这里统一断言为返回 Promise<any> 的请求方法，由各 API 函数标注精确的信封类型。
type ApiRequest = {
  get: (...args: any[]) => Promise<any>
  post: (...args: any[]) => Promise<any>
  patch: (...args: any[]) => Promise<any>
  delete: (...args: any[]) => Promise<any>
}

const request = rawRequest as unknown as ApiRequest

/** 后端统一 Result 信封：code === 1 表示成功，业务数据在 data 中。 */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
}

/** 空间/搜索列表展示用文件条目（与 src/models/file.js 的 mapFullFileEntry 输出对应）。 */
export interface ApiFileItem {
  id?: string | number
  fileName?: string
  type?: string
  category?: string | null
  fileSize?: number
  parentId?: string | number | null
  teamId?: string | number | null
  storePath?: string
  createTime?: string | null
  modifyTime?: string | null
  virtualType?: string | null
  projectId?: string | number | null
  conversationId?: string | number | null
}

/** 回收站条目（在文件条目基础上带删除时间，对应 mapRecycleFileEntry 输出）。 */
export interface RecycleFileItem extends ApiFileItem {
  deleteTime?: string | null
}

/** 分页搜索结果（对应 mapSearchFileEntries 输出）。 */
export interface PagedFileResult {
  total: number
  list: ApiFileItem[]
}

/** 移动/复制等批量操作结果（对应 showFeedback 读取的字段）。 */
export interface MoveCopyResultItem {
  status?: string
  renamed?: boolean
  finalName?: string
  fileName?: string
  originalName?: string
}

export interface MoveCopyResult {
  successCount?: number
  renamedCount?: number
  failedCount?: number
  details?: MoveCopyResultItem[]
}

/** 文件下载链接信息（getFileDownloadUrl 的 data）。 */
export interface DownloadUrlResult {
  downloadUrl?: string
  directDownload?: boolean
  fileName?: string
}

/** fetchFileList 的排序与空间参数。 */
export interface FileListSortOptions {
  sortField?: string
  sortOrder?: string
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
  page?: number
  pageSize?: number
  signal?: AbortSignal
}

/** searchFiles 的可选参数。 */
export interface SearchOptions {
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
  signal?: AbortSignal
}

const buildFileListParams = (
  parentId: string | number,
  sortOptions: Pick<FileListSortOptions, 'sortField' | 'sortOrder' | 'teamId' | 'spaceType' | 'projectId'> = {},
) => {
  const params: Record<string, string | number> = { parentId }
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

export const fetchFileList = async (
  parentId: string | number,
  sortOptions: FileListSortOptions = {},
): Promise<ApiResult<ApiFileItem[]>> => {
  const { page, pageSize, signal, ...restSortOptions } = sortOptions
  const response = await request.get('/api/files', {
    params: {
      ...buildFileListParams(parentId, restSortOptions),
      ...(page ? { page } : {}),
      ...(pageSize ? { pageSize } : {}),
    },
    signal,
  })

  return {
    ...response,
    data: mapSpaceFileEntries(response?.data?.list ?? response?.data) as ApiFileItem[],
  }
}

export const searchFiles = async (
  keyword: string,
  page = 1,
  pageSize = 20,
  options: SearchOptions = {},
): Promise<ApiResult<PagedFileResult>> => {
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
    data: mapSearchFileEntries(response?.data) as PagedFileResult,
  }
}

export const getFileDownloadUrl = (fileId: string | number): Promise<ApiResult<DownloadUrlResult>> => {
  return request.get(`/api/files/${fileId}/download-url`)
}

export const getUploadSign = (originalName: string): Promise<ApiResult<unknown>> => {
  return request.post('/api/files/uploads', null, {
    params: { originalName },
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })
}

export const directUpload = (
  originalName: string,
  file: File,
  parentId?: string | number,
  teamId?: string | number,
  spaceType?: number | string,
  projectId?: string | number,
): Promise<ApiResult<unknown>> => {
  const formData = new FormData()
  formData.append('file', file)
  if (parentId != null) formData.append('parentId', String(parentId))
  if (teamId != null) formData.append('teamId', String(teamId))
  if (spaceType != null) formData.append('spaceType', String(spaceType))
  if (projectId != null) formData.append('projectId', String(projectId))

  return request.post('/api/files/uploads/direct', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
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
}: {
  objectKey: string
  originalName: string
  fileSize: number
  parentId?: string | number
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
  batchId?: string | number
  clientRequestId?: string
}): Promise<ApiResult<unknown>> => {
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

export const fetchStorageUsage = ({
  spaceType,
  teamId,
  projectId,
}: {
  spaceType?: number | string
  teamId?: string | number
  projectId?: string | number
} = {}): Promise<ApiResult<unknown>> => {
  return request.get('/api/storage/usage', {
    params: {
      ...(spaceType ? { spaceType } : {}),
      ...(teamId ? { teamId } : {}),
      ...(projectId ? { projectId } : {}),
    },
  })
}

export const createFolder = ({
  folderName,
  parentId,
  teamId,
  spaceType,
  projectId,
}: {
  folderName: string
  parentId?: string | number
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
}): Promise<ApiResult<unknown>> => {
  return request.post('/api/folders', {
    folderName,
    parentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })
}

export const renameFile = ({
  fileId,
  newName,
}: {
  fileId: string | number
  newName: string
}): Promise<ApiResult<unknown>> => {
  return request.patch(`/api/files/${fileId}`, {
    fileId,
    newName,
  })
}

export const moveFiles = async ({
  fileIds,
  targetParentId,
  teamId,
  spaceType,
  projectId,
}: {
  fileIds: Array<string | number>
  targetParentId: string | number
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
}): Promise<ApiResult<MoveCopyResult>> => {
  const response = await request.patch('/api/files', {
    fileIds,
    targetParentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })

  return response
}

export const copyFiles = async ({
  fileIds,
  targetParentId,
  teamId,
  spaceType,
  projectId,
}: {
  fileIds: Array<string | number>
  targetParentId: string | number
  teamId?: string | number
  spaceType?: number | string
  projectId?: string | number
}): Promise<ApiResult<MoveCopyResult>> => {
  const response = await request.post('/api/files/copies', {
    fileIds,
    targetParentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })

  return response
}

export const logicalDeleteFiles = (fileIds: Array<string | number>): Promise<ApiResult<unknown>> => {
  return request.patch('/api/files/trash', { fileIds })
}

export const fetchRecycleList = async (
  options: {
    teamId?: string | number
    spaceType?: number | string
    projectId?: string | number
  } = {},
): Promise<ApiResult<RecycleFileItem[]>> => {
  const response = await request.get('/api/trash/files', {
    params: {
      ...(options.teamId ? { teamId: options.teamId } : {}),
      ...(options.spaceType ? { spaceType: options.spaceType } : {}),
      ...(options.projectId ? { projectId: options.projectId } : {}),
    },
  })

  return {
    ...response,
    data: mapRecycleFileEntries(response?.data) as RecycleFileItem[],
  }
}

export const restoreFiles = (fileIds: Array<string | number>): Promise<ApiResult<unknown>> => {
  return request.delete('/api/files/trash', { data: { fileIds } })
}

export const deleteFilesForever = (fileIds: Array<string | number>): Promise<ApiResult<unknown>> => {
  return request.delete('/api/files', { data: { fileIds } })
}
