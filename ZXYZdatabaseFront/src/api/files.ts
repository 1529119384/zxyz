import { DEFAULT_PAGE_SIZE } from '@/constants/pagination'
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
  post: <T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ) => Promise<ApiResult<T>>
  // patch：部分更新
  patch: <T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ) => Promise<ApiResult<T>>
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

/**
 * 空间/搜索列表展示用文件条目（与 src/models/file.js 的 mapFullFileEntry 输出对应）。
 *
 * 注意这里是**归一化后的展示模型**而非后端原始报文：`type` 是数值枚举
 * （0=文件夹/1=文件，见 src/models/filePresentation.js 的 FILE_ENTRY_TYPES），
 * `category` 也是数值分类编号。此处若写成 `string` 会与归一化实现不符
 * ——下面的 `as ApiFileItem[]` 断言会以 TS2352 报警，正是这条锚点在防漂移。
 */
export interface ApiFileItem {
  id?: string | number
  fileName?: string
  type?: 0 | 1
  category?: number | null
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
  /** 后端实际生效的页码 / 页长；0 表示后端未回传（旧后端），调用方保持本地值。 */
  page: number
  pageSize: number
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
  sortOptions: Pick<
    FileListSortOptions,
    'sortField' | 'sortOrder' | 'teamId' | 'spaceType' | 'projectId'
  > = {},
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

/** 分页信封里需要提升到 ApiResult 同级的字段（data 被拍平成数组后就取不到它们了）。 */
export interface HoistedPaging {
  total?: number
  page?: number
  pageSize?: number
}

const toOptionalNumber = (value: unknown): number | undefined => {
  if (value == null) return undefined
  const num = Number(value)
  return Number.isFinite(num) ? num : undefined
}

/**
 * 把后端分页信封的 `list` 拍平成 `data` 数组，并把 total / page / pageSize 提升到信封同级。
 *
 * 为什么必须提升：`data` 被替换成数组后，调用方无法再从 `data` 里取到它们。
 * 其中 page / pageSize 是**后端实际生效值**（可能被 `PageResult.MAX_PAGE_SIZE` 钳过），
 * 分页器采纳它才不会出现「前端按 200 算总页数、后端按 100 分页」的错位 ——
 * 那种错位会让每翻一页跳掉一批数据，中后段永远看不到。
 *
 * 裸数组返回（旧后端）时不提升，调用方各自保持本地值（本仓既有的降级习惯）。
 */
const hoistPagedEnvelope = <T>(
  response: ApiResult<unknown>,
  mapList: (rawList: unknown[]) => T,
): ApiResult<T> & HoistedPaging => {
  const rawData = response?.data
  const rawList = Array.isArray(rawData)
    ? rawData
    : Array.isArray((rawData as { list?: unknown } | null)?.list)
      ? (rawData as { list: unknown[] }).list
      : []
  const envelope = Array.isArray(rawData) ? null : (rawData as Record<string, unknown> | null)

  const total = toOptionalNumber(envelope?.total)
  const page = toOptionalNumber(envelope?.page)
  const pageSize = toOptionalNumber(envelope?.pageSize)

  return {
    ...response,
    data: mapList(rawList),
    ...(total === undefined ? {} : { total }),
    ...(page === undefined ? {} : { page }),
    ...(pageSize === undefined ? {} : { pageSize }),
  }
}

export const fetchFileList = async (
  parentId: string | number,
  sortOptions: FileListSortOptions = {},
): Promise<ApiResult<ApiFileItem[]> & HoistedPaging> => {
  const { page, pageSize, signal, ...restSortOptions } = sortOptions
  const response = await request.get<Record<string, unknown>>('/api/files', {
    params: {
      ...buildFileListParams(parentId, restSortOptions),
      ...(page ? { page } : {}),
      ...(pageSize ? { pageSize } : {}),
    },
    signal,
  })

  return hoistPagedEnvelope(response, (rawList) =>
    mapSpaceFileEntries(rawList as import('@/models/file').RawFileRecord[]) as ApiFileItem[],
  )
}

export const searchFiles = async (
  keyword: string,
  page = 1,
  pageSize = DEFAULT_PAGE_SIZE,
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

export const getFileDownloadUrl = (
  fileId: string | number,
): Promise<ApiResult<DownloadUrlResult>> => {
  return request.get<DownloadUrlResult>(`/api/files/${fileId}/download-url`)
}

export const getUploadSign = (
  originalName: string,
  fileSize?: number,
): Promise<ApiResult<UploadSignResult>> => {
  return request.post<UploadSignResult>('/api/files/uploads', null, {
    // fileSize 让后端在发签名前就能拒掉超大文件（省掉一次注定失败的 PUT）。
    // 注意它来自客户端、不可信 —— 后端 confirm 阶段的 HEAD 才是真正的把关点。
    params: { originalName, fileSize },
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

export const logicalDeleteFiles = (
  fileIds: Array<string | number>,
): Promise<ApiResult<LogicalDeleteResult>> => {
  return request.patch<LogicalDeleteResult>('/api/files/trash', { fileIds })
}

/**
 * 回收站列表（分页）。
 *
 * 后端自 07-P0-2 起改为返回分页信封 `{ page, pageSize, total, list }`
 * （此前是无分页、无上限的裸数组）。这里沿用 `fetchFileList` 的处理方式：
 * 把 `list` 拍平成 `data` 数组、把 `total` / `page` / `pageSize` 提升到信封同级 ——
 * `data` 被替换成数组后调用方再也取不到它们（`useRecycleBinList` 的分页器需要 total，
 * 而 pageSize 是**后端生效值**，不采纳会让总页数算错）。
 *
 * 仍兼容裸数组返回：万一后端版本回退，前端不会整页空白。
 */
export const fetchRecycleList = async (
  options: {
    teamId?: string | number
    spaceType?: number | string
    projectId?: string | number
    page?: number
    pageSize?: number
  } = {},
): Promise<ApiResult<RecycleFileItem[]> & HoistedPaging> => {
  const { page, pageSize, ...spaceOptions } = options
  const response = await request.get<Record<string, unknown>>('/api/trash/files', {
    params: {
      ...(spaceOptions.teamId ? { teamId: spaceOptions.teamId } : {}),
      ...(spaceOptions.spaceType ? { spaceType: spaceOptions.spaceType } : {}),
      ...(spaceOptions.projectId ? { projectId: spaceOptions.projectId } : {}),
      ...(page ? { page } : {}),
      ...(pageSize ? { pageSize } : {}),
    },
  })

  return hoistPagedEnvelope(response, (rawList) =>
    mapRecycleFileEntries(rawList as import('@/models/file').RawFileRecord[]) as RecycleFileItem[],
  )
}

export const restoreFiles = (
  fileIds: Array<string | number>,
): Promise<ApiResult<RestoreFilesResult>> => {
  return request.delete<RestoreFilesResult>('/api/files/trash', { data: { fileIds } })
}

export const deleteFilesForever = (
  fileIds: Array<string | number>,
): Promise<ApiResult<DeleteForeverResult>> => {
  return request.delete<DeleteForeverResult>('/api/files', { data: { fileIds } })
}
