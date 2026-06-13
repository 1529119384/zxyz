import { defineStore } from 'pinia'
import { ref, readonly } from 'vue'

import { logger } from '@/utils/logger'

const PATH_TO_ID_MAP_KEY = 'pathToIdMap'
const MAX_PATH_ENTRIES = 500

function readPathToIdMap() {
  if (typeof window === 'undefined') {
    return {}
  }

  try {
    return JSON.parse(window.sessionStorage.getItem(PATH_TO_ID_MAP_KEY) || '{}')
  } catch (error) {
    logger.warn('读取路径映射失败，已回退为空映射:', error)
    return {}
  }
}

export const useCurrentIdStore = defineStore('currentId', () => {
  const currentId = ref(-1)
  // 统一维护路径到目录 ID 的缓存，避免组件各自读写 sessionStorage。
  const pathToIdMap = ref(readPathToIdMap())

  function persistPathToIdMap() {
    if (typeof window === 'undefined') {
      return
    }

    window.sessionStorage.setItem(PATH_TO_ID_MAP_KEY, JSON.stringify(pathToIdMap.value))
  }

  function setCurrentId(id) {
    currentId.value = id
  }

  function evictOldestEntries() {
    const keys = Object.keys(pathToIdMap.value)
    if (keys.length <= MAX_PATH_ENTRIES) {
      return
    }

    const removeCount = keys.length - MAX_PATH_ENTRIES
    for (let i = 0; i < removeCount; i++) {
      delete pathToIdMap.value[keys[i]]
    }
  }

  function rememberPathId(path, id) {
    if (!path) {
      return
    }

    pathToIdMap.value[path] = id
    evictOldestEntries()
    persistPathToIdMap()
  }

  function getPathId(path) {
    if (!path) {
      return undefined
    }

    return pathToIdMap.value[path]
  }

  function listRememberedPaths() {
    return Object.keys(pathToIdMap.value)
  }

  function findNearestPathId(path) {
    if (!path) {
      return {
        matchedPath: '',
        matchedId: undefined,
        remainingSegments: [],
      }
    }

    const segments = path.split('/').filter(Boolean)

    // 按目录层级从长到短回退，找到最近的已缓存父路径。
    for (let end = segments.length - 1; end > 0; end -= 1) {
      const matchedPath = `/${segments.slice(0, end).join('/')}`
      const matchedId = pathToIdMap.value[matchedPath]

      if (matchedId !== undefined) {
        return {
          matchedPath,
          matchedId,
          remainingSegments: segments.slice(end),
        }
      }
    }

    return {
      matchedPath: '',
      matchedId: undefined,
      remainingSegments: segments,
    }
  }

  function forgetPathId(path) {
    if (!path || pathToIdMap.value[path] === undefined) {
      return
    }

    delete pathToIdMap.value[path]
    persistPathToIdMap()
  }

  return {
    currentId,
    pathToIdMap: readonly(pathToIdMap),
    setCurrentId,
    rememberPathId,
    getPathId,
    listRememberedPaths,
    findNearestPathId,
    forgetPathId,
  }
})
