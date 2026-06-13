import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { createUploadFailResult } from '@/models/upload'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { uploadFileWithPresign } from '@/services/upload'
import {
  calculateUploadPercentage,
  getUploadTrackingKey,
  sumUploadedBytes,
} from '@/utils/uploadProgress'
import { buildBatchPredictedNames, FILE_TYPE } from '@/utils/nameConflict'
import { createClientId } from '@/utils/id'
import { validateFiles, MAX_FILE_SIZE, DANGEROUS_EXTENSIONS } from '@/utils/fileValidation'

/**
 * @typedef {Object} UseFileUploadOptions
 * @property {Function} [onSuccess] - 全部上传成功后的回调，参数为成功结果列表。
 * @property {Function} [getSiblingEntries] - 获取当前目录已有条目列表，用于冲突名称预测。
 * @property {Object} [spaceContext] - 空间上下文，用于解析上传请求的空间参数。
 * @property {Function} [getTeamId] - 获取当前团队 ID。
 * @property {Function} [getSpaceType] - 获取当前空间类型。
 * @property {Function} [getProjectId] - 获取当前项目 ID。
 */

/**
 * 文件上传 composable，管理上传对话框、文件列表、进度追踪及批量上传逻辑。
 *
 * @param {import('vue').Ref<number>} currentId - 当前目录 ID，必须传入 Ref，不要直接传已解包后的普通数值。
 * @param {UseFileUploadOptions} [options={}] - 配置项。
 * @returns {{ fileUploadDialog: import('vue').Ref<boolean>, fileInput: import('vue').Ref<Object|null>, fileList: import('vue').Ref<Array>, uploading: import('vue').Ref<boolean>, progress: import('vue').Ref<number>, totalFileSize: import('vue').ComputedRef<number>, uploadedBytes: import('vue').ComputedRef<number>, predictionMap: import('vue').ComputedRef<Object>, triggerSelect: Function, handleDragOver: Function, getFileKey: Function, getPredictedName: Function, isPredictedRenamed: Function, clearFileInput: Function, resetFileUploadState: Function, appendFiles: Function, handleSelect: Function, handleDrop: Function, removeFile: Function, handleCancelFileUpload: Function, uploadFilesWithResult: Function, doUpload: Function, openFileUpload: Function, MAX_FILE_SIZE: number, DANGEROUS_EXTENSIONS: Set<string> }} 文件上传状态与操作方法。
 */
export function useFileUpload(currentId, options = {}) {
  const { onSuccess, getSiblingEntries, spaceContext, getTeamId, getSpaceType, getProjectId } =
    options

  const fileUploadDialog = ref(false)
  const fileInput = ref(null)
  const fileList = ref([])
  const uploading = ref(false)
  const progress = ref(0)
  const uploadProgressMap = ref({})

  const predictionMap = computed(() => {
    const siblingEntries = getSiblingEntries?.() || []
    const predictedList = buildBatchPredictedNames(fileList.value, siblingEntries, FILE_TYPE.FILE)

    return predictedList.reduce((result, item, index) => {
      const file = fileList.value[index]
      if (!file) {
        return result
      }

      result[getUploadTrackingKey(file)] = item
      return result
    }, {})
  })

  const totalFileSize = computed(() =>
    fileList.value.reduce((total, file) => total + (file.size || 0), 0),
  )
  const uploadedBytes = computed(() => sumUploadedBytes(uploadProgressMap.value))

  const triggerSelect = () => fileInput.value?.click()

  function handleDragOver(event) {
    event.preventDefault()
  }

  function getFileKey(file) {
    return getUploadTrackingKey(file)
  }

  function getPredictedName(file) {
    return predictionMap.value[getFileKey(file)]?.predictedName || file?.name || ''
  }

  function isPredictedRenamed(file) {
    return Boolean(predictionMap.value[getFileKey(file)]?.renamed)
  }

  function clearFileInput() {
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }

  function resetFileUploadState() {
    fileList.value = []
    uploading.value = false
    progress.value = 0
    uploadProgressMap.value = {}
    clearFileInput()
  }

  function setFileUploadedBytes(file, loadedBytes) {
    const fileKey = getFileKey(file)
    const nextLoadedBytes = Math.min(loadedBytes || 0, file.size || 0)

    uploadProgressMap.value = {
      ...uploadProgressMap.value,
      [fileKey]: nextLoadedBytes,
    }
  }

  function syncProgress(totalBytes, allowComplete = false) {
    progress.value = calculateUploadPercentage(uploadedBytes.value, totalBytes, { allowComplete })
  }

  function appendFiles(files) {
    const { valid, rejected } = validateFiles(files)

    if (rejected.length) {
      const preview = rejected.slice(0, 3).join('、')
      const suffix = rejected.length > 3 ? ` 等 ${rejected.length} 个文件` : ''
      ElMessage.warning(`已跳过：${preview}${suffix}`)
    }

    if (!valid.length) {
      return
    }

    const existingKeys = new Set(fileList.value.map(getFileKey))
    const uniqueFiles = valid.filter((file) => {
      const fileKey = getFileKey(file)
      if (existingKeys.has(fileKey)) {
        return false
      }

      existingKeys.add(fileKey)
      return true
    })

    if (!uniqueFiles.length) {
      ElMessage.warning('所选文件已在上传列表中')
      return
    }

    fileList.value.push(...uniqueFiles)
  }

  function handleSelect(event) {
    appendFiles(Array.from(event.target.files || []))
    clearFileInput()
  }

  function handleDrop(event) {
    appendFiles(Array.from(event.dataTransfer.files || []))
  }

  function removeFile(index) {
    if (uploading.value) {
      return
    }

    fileList.value.splice(index, 1)
  }

  function handleCancelFileUpload() {
    if (uploading.value) {
      return
    }

    fileUploadDialog.value = false
  }

  async function uploadSingleFile(file, onProgress, options = {}) {
    // 上传时实时读取当前目录 ID，避免把普通数值误当成响应式引用传入。
    return uploadFileWithPresign(file, currentId.value, onProgress, options)
  }

  async function uploadFilesWithResult(filesToUpload) {
    const successList = []
    const failList = []
    const totalBytes = filesToUpload.reduce((total, file) => total + (file.size || 0), 0)
    const batchId = createClientId('batch')

    uploadProgressMap.value = Object.fromEntries(filesToUpload.map((file) => [getFileKey(file), 0]))

    for (const file of filesToUpload) {
      try {
        const spaceParams = resolveSpaceRequestParams(spaceContext, {
          teamId: getTeamId?.() || null,
          spaceType: getSpaceType?.() || null,
          projectId: getProjectId?.() || null,
        })
        await uploadSingleFile(
          file,
          (event) => {
            if (!event.total) {
              return
            }

            setFileUploadedBytes(file, event.loaded)
            syncProgress(totalBytes)
          },
          {
            batchId,
            ...spaceParams,
            clientRequestId: getFileKey(file),
          },
        ).then((result) => {
          setFileUploadedBytes(file, file.size || 0)
          syncProgress(totalBytes)
          successList.push(result)
        })
      } catch (error) {
        failList.push(
          createUploadFailResult({
            originalName: file.name,
            size: file.size,
            type: FILE_TYPE.FILE,
            finalName: getPredictedName(file),
            message: error?.message || '上传失败，请稍后重试',
          }),
        )
      }
    }

    return { successList, failList, totalBytes }
  }

  async function doUpload() {
    if (!fileList.value.length) {
      return {
        successList: [],
        failList: [],
        filesToUpload: [],
      }
    }

    uploading.value = true
    progress.value = 0
    uploadProgressMap.value = {}

    const filesToUpload = [...fileList.value]

    try {
      const { successList, failList, totalBytes } = await uploadFilesWithResult(filesToUpload)

      if (successList.length === filesToUpload.length) {
        syncProgress(totalBytes, true)
      }

      if (successList.length) {
        onSuccess?.(successList)
      }

      return {
        successList,
        failList,
        filesToUpload,
      }
    } finally {
      uploading.value = false
    }
  }

  function openFileUpload() {
    resetFileUploadState()
    fileUploadDialog.value = true
  }

  return {
    fileUploadDialog,
    fileInput,
    fileList,
    uploading,
    progress,
    totalFileSize,
    uploadedBytes,
    predictionMap,
    triggerSelect,
    handleDragOver,
    getFileKey,
    getPredictedName,
    isPredictedRenamed,
    clearFileInput,
    resetFileUploadState,
    appendFiles,
    handleSelect,
    handleDrop,
    removeFile,
    handleCancelFileUpload,
    uploadFilesWithResult,
    doUpload,
    openFileUpload,
    MAX_FILE_SIZE,
    DANGEROUS_EXTENSIONS,
  }
}
