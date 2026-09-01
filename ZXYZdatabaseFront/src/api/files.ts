import { mapRecycleFileEntries, mapSearchFileEntries, mapSpaceFileEntries } from '@/models/file'
import rawRequest, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'
import type { AxiosRequestConfig } from 'axios'

// axios 实例的响应拦截器会把成功响应解包为后端信封 { code, msg, data }（见
// src/utils/createApiClient.js：code === 1 时返回整个 payload），与原生 AxiosResponse
// 类型不符。下面用带泛型的精确签名收口：每个方法返回 Promise<ApiResult<T>>，
// T 由各 API 函数调用时显式传入（如 request.get<UploadSignResult>(...)），从而保留 data 的类型。
type ApiRequest = {
  // get：拉取资源；T 为响应 data 的精确类型
  get: <T = unknown>(url: string, config?: AxiosRequestConfig) => Promise<ApiResult<T>>
  // post：新建资源；data 为请求体，config 透传 axios 配置（timeout/headers 等）
  post: <T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) => Promise<ApiResult<T>>
  // patch：部分更新
  patch: <T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) => Promise<ApiResult<T>>
  // delete：删除；axios 把请求体放在 config.data 中
  delete: <T = unknown>(url: string, config?: AxiosRequestConfig) => Promise<ApiResult<T>>
}

// rawRequest 本质为 axios 实例，单次断言到带泛型的 ApiRequest（响应已被拦截器解包为信封）
const request = rawRequest as ApiRequest

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

/** getUploadSign 的 data：预签名上传所需签名信息（upload.js 读取 uploadUrl/objectKey/contentType/contentDisposition/directUpload）。 */
export interface UploadSignResult {
  uploadUrl?: string
  objectKey?: string
  contentType?: string
  contentDisposition?: string
  directUpload?: boolean
}

/** directUpload 的 data：后端直传结果。当前调用方未消费返回值，端点精确结构未知，留空以收敛 unknown。 */
export interface DirectUploadResult {}

/** confirmUpload 的 data：批量确认上传结果。模型 @/models/upload 读取 items[0] 各字段，兼容旧接口单对象返回。 */
export interface ConfirmUploadResultItem {
  status?: string
  code?: number
  msg?: string
  originalName?: string
  clientOriginalName?: string
  finalName?: string
  fileType?: number
  fileSize?: number
  fileId?: string | number
  id?: string | number
  parentId?: string | number
  fileUrl?: string
  clientRequestId?: string
}

export interface ConfirmUploadResult {
  items?: ConfirmUploadResultItem[]
  // 兼容旧接口直接返回单对象的情况（normalizeUploadConfirmResult 会按字段兜底）
  originalName?: string
  finalName?: string
  fileType?: number
  fileSize?: number
  id?: string | number
  parentId?: string | number
  fileUrl?: string
  clientRequestId?: string
}

/** fetchStorageUsage 的 data：存储用量（useStorageUsage 读取 usedStorage/storageLimit/unlimited）。 */
export interface StorageUsageResult {
  usedStorage?: number
  storageLimit?: number
  unlimited?: boolean
}

/** createFolder 的 data：新建文件夹结果（normalizeFolderCreateResult 读取 originalName/finalName/fileType/id/parentId）。 */
export interface CreateFolderResult {
  originalName?: string
  finalName?: string
  fileType?: number
  id?: string | number
  parentId?: string | number
}

/** renameFile 的 data：重命名结果。调用方未消费返回值，结构未知，留空收敛 unknown。 */
export interface RenameFileResult {}

/** logicalDeleteFiles 的 data：逻辑删除结果。调用方未消费返回值，结构未知，留空收敛 unknown。 */
export interface LogicalDeleteResult {}

/** restoreFiles 的 data：从回收站恢复结果。调用方未消费返回值，结构未知，留空收敛 unknown。 */
export interface RestoreFilesResult {}

/** deleteFilesForever 的 data：彻底删除结果。调用方未消费返回值，结构未知，留空收敛 unknown。 */
export interface DeleteForeverResult {}

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
  const response = await request.get<Record<string, unknown>>('/api/files', {
    params: {
      ...buildFileListParams(parentId, restSortOptions),
      ...(page ? { page } : {}),
      ...(pageSize ? { pageSize } : {}),
    },
    signal,
  })

  return {
    ...response,
    // 后端可能返回 { list: [...] } 或直接的列表，原始 data 用宽松类型承载 map* 映射
    data: mapSpaceFileEntries((response?.data?.list ?? response?.data) as unknown[]) as ApiFileItem[],
  }
}

export const searchFiles = async (
  keyword: string,
  page = 1,
  pageSize = 20,
  options: SearchOptions = {},
): Promise<ApiResult<PagedFileResult>> => {
  const { signal, ...paramsOptions } = options
  const response = await request.get<Record<string, unknown>>('/api/files/search', {
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
  return request.get<DownloadUrlResult>(`/api/files/${fileId}/download-url`)
}

export const getUploadSign = (originalName: string): Promise<ApiResult<UploadSignResult>> => {
  return request.post<UploadSignResult>('/api/files/uploads', null, {
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
): Promise<ApiResult<DirectUploadResult>> => {
  const formData = new FormData()
  formData.append('file', file)
  if (parentId != null) formData.append('parentId', String(parentId))
  if (teamId != null) formData.append('teamId', String(teamId))
  if (spaceType != null) formData.append('spaceType', String(spaceType))
  if (projectId != null) formData.append('projectId', String(projectId))

  return request.post<DirectUploadResult>('/api/files/uploads/direct', formData, {
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
}): Promise<ApiResult<ConfirmUploadResult>> => {
  return request.post<ConfirmUploadResult>(
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
} = {}): Promise<ApiResult<StorageUsageResult>> => {
  return request.get<StorageUsageResult>('/api/storage/usage', {
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
}): Promise<ApiResult<CreateFolderResult>> => {
  return request.post<CreateFolderResult>('/api/folders', {
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
}): Promise<ApiResult<RenameFileResult>> => {
  return request.patch<RenameFileResult>(`/api/files/${fileId}`, {
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
  const response = await request.patch<MoveCopyResult>('/api/files', {
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
  const response = await request.post<MoveCopyResult>('/api/files/copies', {
    fileIds,
    targetParentId,
    ...(teamId ? { teamId } : {}),
    ...(spaceType ? { spaceType } : {}),
    ...(projectId ? { projectId } : {}),
  })

  return response
}

export const logicalDeleteFiles = (fileIds: Array<string | number>): Promise<ApiResult<LogicalDeleteResult>> => {
  return request.patch<LogicalDeleteResult>('/api/files/trash', { fileIds })
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
    data: mapRecycleFileEntries(response.data as unknown[]) as RecycleFileItem[],
  }
}

export const restoreFiles = (fileIds: Array<string | number>): Promise<ApiResult<RestoreFilesResult>> => {
  return request.delete<RestoreFilesResult>('/api/files/trash', { data: { fileIds } })
}

export const deleteFilesForever = (fileIds: Array<string | number>): Promise<ApiResult<DeleteForeverResult>> => {
  return request.delete<DeleteForeverResult>('/api/files', { data: { fileIds } })
}
