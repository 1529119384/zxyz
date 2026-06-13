import { computed, ref } from 'vue'

import { buildCrumbPath, joinPath, normalizePath, parseCrumbs } from '@/utils/pathUtils'

export const ROOT_PATH = ''
export const ROOT_ID = -1

function createStateRef(externalRef, initialValue) {
  return externalRef ?? ref(initialValue)
}

/**
 * @typedef {Object} UseCorePathNavigationOptions
 * @property {string} [initialPath] - 初始路径，默认为根路径。
 * @property {number} [initialParentId] - 初始父级 ID，默认为根 ID。
 * @property {Object} [initialPathToIdMap] - 初始路径-ID 映射。
 * @property {import('vue').Ref<string>} [currentPath] - 外部传入的当前路径响应式引用。
 * @property {import('vue').Ref<number>} [currentParentId] - 外部传入的当前父级 ID 响应式引用。
 * @property {import('vue').Ref<Object>} [pathToIdMap] - 外部传入的路径-ID 映射响应式引用。
 * @property {Function} [setCurrentPath] - 自定义设置当前路径的函数。
 * @property {Function} [setCurrentParentId] - 自定义设置当前父级 ID 的函数。
 * @property {Function} [replacePathToIdMap] - 自定义替换路径-ID 映射的函数。
 * @property {Function} [onRememberPathId] - 记录路径-ID 映射时的回调。
 * @property {Function} [onForgetPathId] - 删除路径-ID 映射时的回调。
 * @property {boolean} [decode=false] - 是否对路径进行解码。
 */

/**
 * 文件资源管理器核心路径导航，管理当前路径、父级 ID 和路径-ID 映射。
 *
 * @param {UseCorePathNavigationOptions} [options={}] - 配置项。
 * @returns {{ currentPath: import('vue').Ref<string>, currentParentId: import('vue').Ref<number>, pathToIdMap: import('vue').Ref<Object>, crumbArr: import('vue').ComputedRef, crumbPath: Function, buildFolderPath: Function, setPath: Function, setParentId: Function, replacePathToIdMap: Function, rememberPathId: Function, forgetPathId: Function, resetNavigation: Function, enterFolder: Function, goToPath: Function }} 路径导航状态与操作方法。
 */
export function useCorePathNavigation(options = {}) {
  const {
    initialPath = ROOT_PATH,
    initialParentId = ROOT_ID,
    initialPathToIdMap = {},
    currentPath: externalCurrentPath,
    currentParentId: externalCurrentParentId,
    pathToIdMap: externalPathToIdMap,
    setCurrentPath,
    setCurrentParentId,
    replacePathToIdMap: customReplacePathToIdMap,
    onRememberPathId,
    onForgetPathId,
    decode = false,
  } = options

  const currentPath = createStateRef(externalCurrentPath, initialPath)
  const currentParentId = createStateRef(externalCurrentParentId, initialParentId)
  const pathToIdMap = createStateRef(externalPathToIdMap, { ...initialPathToIdMap })

  const crumbArr = computed(() => parseCrumbs(currentPath.value, { decode }))

  function updateCurrentPath(path) {
    const normalizedPath = normalizePath(path, { decode: false })

    if (typeof setCurrentPath === 'function') {
      return setCurrentPath(normalizedPath)
    }

    currentPath.value = normalizedPath
    return normalizedPath
  }

  function updateCurrentParentId(parentId) {
    if (typeof setCurrentParentId === 'function') {
      return setCurrentParentId(parentId)
    }

    currentParentId.value = parentId
    return parentId
  }

  function replacePathToIdMap(nextMap = {}) {
    if (typeof customReplacePathToIdMap === 'function') {
      return customReplacePathToIdMap(nextMap)
    }

    pathToIdMap.value = { ...nextMap }
    return pathToIdMap.value
  }

  function crumbPath(index) {
    return buildCrumbPath(crumbArr.value, index, { decode: false })
  }

  function buildFolderPath(row) {
    if (!row) {
      return currentPath.value
    }

    return joinPath(currentPath.value, row.fileName, { leadingSlash: true, decode: false })
  }

  function rememberPathId(path, id) {
    const normalizedPath = normalizePath(path, { decode: false })
    if (!normalizedPath) {
      return
    }

    if (typeof onRememberPathId === 'function') {
      return onRememberPathId(normalizedPath, id)
    }

    pathToIdMap.value[normalizedPath] = id
    return id
  }

  function forgetPathId(path) {
    const normalizedPath = normalizePath(path, { decode: false })
    if (!normalizedPath || pathToIdMap.value[normalizedPath] === undefined) {
      return
    }

    if (typeof onForgetPathId === 'function') {
      return onForgetPathId(normalizedPath)
    }

    delete pathToIdMap.value[normalizedPath]
    return normalizedPath
  }

  /**
   * 重置导航状态到根路径。
   *
   * @param {Object} [options={}] - 配置项。
   * @param {Function} [options.loadFolder] - 加载文件夹内容的函数。
   */
  async function resetNavigation(options = {}) {
    const { loadFolder } = options

    replacePathToIdMap()
    await updateCurrentPath(ROOT_PATH)
    await updateCurrentParentId(ROOT_ID)

    if (typeof loadFolder === 'function') {
      await loadFolder(ROOT_ID)
    }
  }

  /**
   * 进入指定文件夹。
   *
   * @param {Object} row - 文件夹行数据。
   * @param {Object} [options={}] - 配置项。
   * @param {Function} [options.onBeforeEnter] - 进入前的回调。
   * @param {Function} [options.onAfterEnter] - 进入后的回调。
   * @param {Function} [options.loadFolder] - 加载文件夹内容的函数。
   * @param {boolean} [options.rememberPath=true] - 是否记录路径映射。
   */
  async function enterFolder(row, options = {}) {
    const { onBeforeEnter, onAfterEnter, loadFolder, rememberPath = true } = options

    if (!row || row.type !== 0) {
      return null
    }

    const nextPath = buildFolderPath(row)
    const context = {
      row,
      nextPath,
      nextParentId: row.id,
    }

    await onBeforeEnter?.(context)
    if (rememberPath) {
      rememberPathId(nextPath, row.id)
    }
    await updateCurrentPath(nextPath)
    await updateCurrentParentId(row.id)

    if (typeof loadFolder === 'function') {
      await loadFolder(row.id)
    }

    await onAfterEnter?.(context)
    return context
  }

  /**
   * 导航到指定路径。
   *
   * @param {string} path - 目标路径。
   * @param {Object} [options={}] - 配置项。
   * @param {Object} [options.externalPathToIdMap] - 外部路径-ID 映射，用于补充解析。
   * @param {Function} [options.resolvePathId] - 异步解析路径对应 ID 的函数。
   * @param {Function} [options.loadFolder] - 加载文件夹内容的函数。
   * @param {Function} [options.onAfterGoToPath] - 导航完成后的回调。
   */
  async function goToPath(path, options = {}) {
    const { externalPathToIdMap = {}, resolvePathId, loadFolder, onAfterGoToPath } = options

    const normalizedPath = normalizePath(path, { decode: false })

    if (!normalizedPath) {
      await resetNavigation({ loadFolder })
      return {
        resolvedPath: ROOT_PATH,
        resolvedId: ROOT_ID,
      }
    }

    let resolvedId = pathToIdMap.value[normalizedPath]

    if (resolvedId === undefined) {
      resolvedId = externalPathToIdMap[normalizedPath]
    }

    if (resolvedId === undefined && typeof resolvePathId === 'function') {
      resolvedId = await resolvePathId(normalizedPath)
    }

    await updateCurrentPath(normalizedPath)
    await updateCurrentParentId(resolvedId ?? ROOT_ID)

    if (resolvedId !== undefined) {
      rememberPathId(normalizedPath, resolvedId)
    }

    if (typeof loadFolder === 'function') {
      await loadFolder(resolvedId ?? ROOT_ID)
    }

    const context = {
      resolvedPath: normalizedPath,
      resolvedId: resolvedId ?? ROOT_ID,
    }

    await onAfterGoToPath?.(context)
    return context
  }

  return {
    currentPath,
    currentParentId,
    pathToIdMap,
    crumbArr,
    crumbPath,
    buildFolderPath,
    setPath: updateCurrentPath,
    setParentId: updateCurrentParentId,
    replacePathToIdMap,
    rememberPathId,
    forgetPathId,
    resetNavigation,
    enterFolder,
    goToPath,
  }
}
