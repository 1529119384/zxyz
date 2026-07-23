import { confirmUpload, createFolder, getUploadSign, directUpload } from '@/api/files'
import {
  createUploadFailResult,
  normalizeFolderCreateResult,
  normalizeUploadConfirmResult,
} from '@/models/upload'
import { getErrorDetail, logUploadError } from '@/utils/error'
import { uploadToOss, uploadToBackend } from '@/utils/oss'
import { validateFiles } from '@/utils/fileValidation'

export async function uploadFileWithPresign(file, parentId, onProgress, options = {}) {
  const {
    batchId = '',
    clientRequestId = '',
    teamId = null,
    spaceType = null,
    projectId = null,
  } = options
  let uploadUrl = ''
  let objectKey = ''
  let contentType = ''
  let contentDisposition = ''
  let directUpload = false

  try {
    const signRes = await getUploadSign(file.name)
    uploadUrl = signRes.data.uploadUrl
    objectKey = signRes.data.objectKey
    contentType = signRes.data.contentType || ''
    contentDisposition = signRes.data.contentDisposition || ''
    directUpload = signRes.data.directUpload || false

    if (!contentType && !directUpload) {
      throw new Error('Missing contentType from getUploadSign response')
    }
    if (!contentDisposition && !directUpload) {
      throw new Error('Missing contentDisposition from getUploadSign response')
    }
  } catch (error) {
    logUploadError('get upload sign failed', file, error)
    throw new Error(`获取上传签名失败：${getErrorDetail(error)}`)
  }

  try {
    if (directUpload) {
      // 后端直传：直接 POST 到后端 API
      await uploadToBackend(file, parentId, teamId, spaceType, projectId)
    } else {
      // OSS 预签名直传
      await uploadToOss(uploadUrl, file, {
        onUploadProgress: onProgress,
        contentType,
        contentDisposition,
      })
    }
  } catch (error) {
    logUploadError('upload failed', file, error, {
      uploadUrl,
      objectKey,
      contentType,
      contentDisposition,
      directUpload,
    })
    throw new Error(`上传失败：${getErrorDetail(error)}`)
  }

  // 直传已完成（无需确认），预签名上传需要确认
  if (directUpload) {
    return {
      originalName: file.name,
      fileSize: file.size,
      parentId,
      status: 'success',
      clientRequestId,
    }
  }

  try {
    const response = await confirmUpload({
      objectKey,
      originalName: file.name,
      fileSize: file.size,
      parentId,
      teamId,
      spaceType,
      projectId,
      batchId,
      clientRequestId,
    })

    return normalizeUploadConfirmResult(response?.data, {
      originalName: file.name,
      fileSize: file.size,
      parentId,
      clientRequestId,
    })
  } catch (error) {
    logUploadError('confirm upload failed', file, error, { objectKey, parentId })
    throw new Error(`确认上传失败：${getErrorDetail(error)}`)
  }
}

export async function uploadFolderTree(nodes, parentId, options = {}) {
  const {
    fileMap = new Map(),
    onFileStart,
    onFileProgress,
    onFileSuccess,
    onFileError,
    batchId = '',
    teamId = null,
    spaceType = null,
    projectId = null,
  } = options

  const successList = []
  const failList = []

  // 目录遍历和接口调用集中在服务层，避免组件承接上传编排细节。
  async function visit(currentNodes, currentParentId, pathPrefix = '') {
    for (const node of currentNodes) {
      if (node.isLeaf) {
        const file = fileMap.get(node.id)
        if (!file) {
          continue
        }

        const { rejected } = validateFiles([file])
        if (rejected.length) {
          const failResult = createUploadFailResult({
            originalName: file.name,
            type: 1,
            size: file.size,
            message: rejected[0],
          })
          failList.push(failResult)
          onFileError?.(file, new Error(rejected[0]), failResult)
          continue
        }

        onFileStart?.(file)

        try {
          const result = await uploadFileWithPresign(
            file,
            currentParentId,
            (event) => {
              onFileProgress?.(file, event)
            },
            {
              batchId,
              teamId,
              spaceType,
              projectId,
              clientRequestId: `${pathPrefix}${file.name}`,
            },
          )
          successList.push(result)
          onFileSuccess?.(file, result)
        } catch (error) {
          const failResult = createUploadFailResult({
            originalName: file.name,
            type: 1,
            size: file.size,
            message: getErrorDetail(error),
          })
          failList.push(failResult)
          onFileError?.(file, error, failResult)
        }
        continue
      }

      let nextParentId = currentParentId

      try {
        const response = await createFolder({
          folderName: node.name,
          parentId: currentParentId,
          teamId,
          spaceType,
          projectId,
        })
        const folderResult = normalizeFolderCreateResult(response?.data, node.name, currentParentId)
        successList.push(folderResult)
        nextParentId = folderResult.id ?? currentParentId

        if (node.children?.length) {
          await visit(node.children, nextParentId, `${pathPrefix}${folderResult.finalName}/`)
        }
      } catch (error) {
        logUploadError('创建文件夹失败', { name: node.name, size: 0 }, error, {
          parentId: currentParentId,
        })
        failList.push(
          createUploadFailResult({
            originalName: node.name,
            type: 0,
            size: 0,
            message: `创建文件夹失败：${getErrorDetail(error)}`,
          }),
        )
      }
    }
  }

  await visit(nodes, parentId)
  return { successList, failList }
}
