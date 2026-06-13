import { computed, ref } from 'vue'

import { buildFolderTree, getFolderTreeStats } from '@/utils/folderTree'
import {
  calculateUploadPercentage,
  getUploadTrackingKey,
  sumUploadedBytes,
} from '@/utils/uploadProgress'

/**
 * 文件夹上传 composable，管理文件夹选择、目录树构建、上传进度追踪。
 *
 * @returns {{ folderInput: import('vue').Ref<Object|null>, folderUploadDialog: import('vue').Ref<boolean>, folderTree: import('vue').Ref<Array>, expandedFolderKeys: import('vue').Ref<Array>, uploadLoading: import('vue').Ref<boolean>, uploadedFolderFileCount: import('vue').Ref<number>, currentFolderUploadName: import('vue').Ref<string>, folderStats: import('vue').ComputedRef<Object>, folderUploadProgress: import('vue').ComputedRef<number>, uploadedFolderBytes: import('vue').ComputedRef<number>, clearFolderInput: Function, resetFolderUploadState: Function, setUploadLoading: Function, resetUploadProgress: Function, markFileStart: Function, markFileProgress: Function, markFileSuccess: Function, completeUpload: Function, onFolderSelected: Function, handleCancelFolderUpload: Function, getUploadContext: Function, openFolderUpload: Function }} 文件夹上传状态与操作方法。
 */
export function useFolderUpload() {
  const folderInput = ref(null)
  const folderUploadDialog = ref(false)
  const folderTree = ref([])
  const expandedFolderKeys = ref([])
  const uploadLoading = ref(false)
  const uploadedFolderFileCount = ref(0)
  const currentFolderUploadName = ref('')
  const fileMap = ref(new Map())
  const uploadProgressMap = ref({})
  const uploadCompleted = ref(false)

  const folderStats = computed(() => getFolderTreeStats(folderTree.value))
  const uploadedFolderBytes = computed(() => sumUploadedBytes(uploadProgressMap.value))

  const folderUploadProgress = computed(() => {
    return calculateUploadPercentage(uploadedFolderBytes.value, folderStats.value.totalSize, {
      allowComplete: uploadCompleted.value,
    })
  })

  function clearFolderInput() {
    if (folderInput.value) {
      folderInput.value.value = ''
    }
  }

  function resetFolderUploadState() {
    folderTree.value = []
    expandedFolderKeys.value = []
    uploadLoading.value = false
    uploadedFolderFileCount.value = 0
    currentFolderUploadName.value = ''
    fileMap.value = new Map()
    uploadProgressMap.value = {}
    uploadCompleted.value = false
    clearFolderInput()
  }

  function setUploadLoading(loading) {
    uploadLoading.value = loading
  }

  function resetUploadProgress() {
    uploadedFolderFileCount.value = 0
    currentFolderUploadName.value = ''
    uploadProgressMap.value = {}
    uploadCompleted.value = false
  }

  function markFileStart(file) {
    currentFolderUploadName.value = file?.name || ''
  }

  function markFileProgress(file, event) {
    const fileKey = getUploadTrackingKey(file)
    const nextLoadedBytes = Math.min(event?.loaded || 0, file?.size || 0)

    uploadProgressMap.value = {
      ...uploadProgressMap.value,
      [fileKey]: nextLoadedBytes,
    }
  }

  function markFileSuccess(file) {
    uploadedFolderFileCount.value += 1
    markFileProgress(file, { loaded: file?.size || 0 })
  }

  function completeUpload() {
    uploadCompleted.value = true
  }

  function onFolderSelected(event) {
    const files = Array.from(event.target.files || [])
    if (!files.length) {
      clearFolderInput()
      return
    }

    const nextTree = buildFolderTree(files)
    folderTree.value = nextTree.tree
    expandedFolderKeys.value = nextTree.expandedKeys
    fileMap.value = nextTree.fileMap
    resetUploadProgress()
    folderUploadDialog.value = true
    clearFolderInput()
  }

  function handleCancelFolderUpload() {
    if (uploadLoading.value) {
      return
    }

    folderUploadDialog.value = false
  }

  function getUploadContext() {
    return {
      tree: folderTree.value,
      fileMap: fileMap.value,
      stats: folderStats.value,
    }
  }

  function openFolderUpload() {
    resetFolderUploadState()
    folderInput.value?.click()
  }

  return {
    folderInput,
    folderUploadDialog,
    folderTree,
    expandedFolderKeys,
    uploadLoading,
    uploadedFolderFileCount,
    currentFolderUploadName,
    folderStats,
    folderUploadProgress,
    uploadedFolderBytes,
    clearFolderInput,
    resetFolderUploadState,
    setUploadLoading,
    resetUploadProgress,
    markFileStart,
    markFileProgress,
    markFileSuccess,
    completeUpload,
    onFolderSelected,
    handleCancelFolderUpload,
    getUploadContext,
    openFolderUpload,
  }
}
