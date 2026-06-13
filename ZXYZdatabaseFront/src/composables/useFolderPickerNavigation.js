import { ref } from 'vue'

import { fetchFileList } from '@/api/files'
import { useCorePathNavigation, ROOT_ID } from '@/composables/useCorePathNavigation'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'

/**
 * @typedef {Object} UseFolderPickerNavigationOptions
 * @property {Object} spaceContext - 空间上下文。
 * @property {Function} [getTeamId] - 获取团队 ID 的函数。
 * @property {Function} [getSpaceType] - 获取空间类型的函数。
 * @property {Function} [getProjectId] - 获取项目 ID 的函数。
 */

/**
 * 文件夹选择器导航组合函数，用于文件夹选择弹窗中的目录浏览。
 *
 * @param {UseFolderPickerNavigationOptions} [options={}] - 配置项。
 * @returns {{ currentPath: import('vue').Ref<string>, currentParentId: import('vue').Ref<number>, list: import('vue').Ref<Array>, loading: import('vue').Ref<boolean>, crumbArr: import('vue').ComputedRef, crumbPath: Function, pathToIdMap: import('vue').Ref<Object>, reset: Function, enterFolder: Function, goToPath: Function, loadFolder: Function }} 文件夹选择器导航状态与操作方法。
 */
export function useFolderPickerNavigation(options = {}) {
  const { spaceContext, getTeamId, getSpaceType, getProjectId } = options
  const list = ref([])
  const loading = ref(false)
  const navigation = useCorePathNavigation()

  function resolveSpaceParams() {
    return resolveSpaceRequestParams(spaceContext, {
      teamId: getTeamId?.() || null,
      spaceType: getSpaceType?.() || null,
      projectId: getProjectId?.() || null,
    })
  }

  async function loadFolder(parentId = ROOT_ID) {
    loading.value = true

    try {
      const response = await fetchFileList(parentId, resolveSpaceParams())
      list.value = Array.isArray(response?.data) ? response.data : []
    } finally {
      loading.value = false
    }
  }

  async function reset() {
    await navigation.resetNavigation({ loadFolder })
  }

  async function enterFolder(row) {
    await navigation.enterFolder(row, { loadFolder })
  }

  async function goToPath(path, externalPathToIdMap = {}) {
    await navigation.goToPath(path, {
      externalPathToIdMap,
      loadFolder,
    })
  }

  return {
    currentPath: navigation.currentPath,
    currentParentId: navigation.currentParentId,
    list,
    loading,
    crumbArr: navigation.crumbArr,
    crumbPath: navigation.crumbPath,
    pathToIdMap: navigation.pathToIdMap,
    reset,
    enterFolder,
    goToPath,
    loadFolder,
  }
}
