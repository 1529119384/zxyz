import { fetchFileList } from '@/api/files'
import { fetchTeamProjects } from '@/api/project'
import {
  collectForgottenPathBranch,
  createNavigationChangeSet,
  rememberPathChange,
} from '@/utils/navigationState'
import { normalizePath, parseCrumbs } from '@/utils/pathUtils'
import {
  createProjectFolderEntry,
  PROJECT_ROOT_ID,
  PROJECT_ROOT_PATH,
} from '@/utils/projectVirtualFolder'

function listFromResponse(response) {
  return Array.isArray(response?.data) ? response.data : []
}

function buildFileListOptions(options) {
  const { sortOptions = {}, teamId = null, spaceType = null, projectId = null, signal } = options

  return {
    ...(sortOptions || {}),
    teamId,
    spaceType,
    projectId,
    signal,
  }
}

export async function resolvePathWithFetch(path, options = {}) {
  const {
    rootId,
    rootPath,
    getPathId,
    findNearestPathId,
    pathToIdMap,
    sortOptions,
    teamId,
    spaceType,
    projectId,
    signal,
  } = options
  const normalizedPath = normalizePath(path)
  const changeSet = createNavigationChangeSet()

  if (!normalizedPath) {
    changeSet.nextCurrentId = rootId
    return {
      resolvedId: rootId,
      resolvedPath: rootPath,
      exactMatched: true,
      prefetchedList: null,
      changeSet,
    }
  }

  if (Number(spaceType) === 2 && normalizedPath === PROJECT_ROOT_PATH) {
    const response = await fetchTeamProjects(teamId, { signal })
    changeSet.nextCurrentId = PROJECT_ROOT_ID
    rememberPathChange(changeSet, PROJECT_ROOT_PATH, PROJECT_ROOT_ID)
    return {
      resolvedId: PROJECT_ROOT_ID,
      resolvedPath: PROJECT_ROOT_PATH,
      exactMatched: true,
      prefetchedList: listFromResponse(response).map(createProjectFolderEntry),
      changeSet,
    }
  }

  const cachedId = getPathId?.(normalizedPath)
  if (cachedId !== undefined) {
    changeSet.nextCurrentId = cachedId
    return {
      resolvedId: cachedId,
      resolvedPath: normalizedPath,
      exactMatched: true,
      prefetchedList: null,
      changeSet,
    }
  }

  const nearestPathMatch = findNearestPathId?.(normalizedPath) || {
    matchedPath: rootPath,
    matchedId: undefined,
    remainingSegments: parseCrumbs(normalizedPath, { decode: false }),
  }
  // 命中最近的已知父路径后，只继续解析剩余层级，避免每次都从根目录开始请求。
  const resolvedSegments = parseCrumbs(nearestPathMatch.matchedPath, { decode: false })
  const segments = nearestPathMatch.remainingSegments || []
  const fileListOptions = buildFileListOptions({
    sortOptions,
    teamId,
    spaceType,
    projectId,
    signal,
  })
  let parentId = nearestPathMatch.matchedId ?? rootId

  for (const segment of segments) {
    const response = await fetchFileList(parentId, fileListOptions)
    const currentLevel = listFromResponse(response)
    const matchedFolder = currentLevel.find((item) => item.type === 0 && item.fileName === segment)

    if (!matchedFolder) {
      const invalidPath = `/${[...resolvedSegments, segment].join('/')}`
      const resolvedPath = resolvedSegments.length ? `/${resolvedSegments.join('/')}` : rootPath

      // 无效路径下的整棵缓存分支都要失效，避免继续命中过期映射。
      changeSet.forgottenPaths.push(...collectForgottenPathBranch(pathToIdMap, invalidPath))
      changeSet.nextCurrentId = parentId
      if (resolvedPath) {
        rememberPathChange(changeSet, resolvedPath, parentId)
      }

      return {
        resolvedId: parentId,
        resolvedPath,
        exactMatched: false,
        prefetchedList: null,
        changeSet,
      }
    }

    resolvedSegments.push(segment)
    parentId = matchedFolder.id
    rememberPathChange(changeSet, `/${resolvedSegments.join('/')}`, matchedFolder.id)
  }

  // 逐级校验结束后预取目标目录列表，供外层直接复用，避免重复请求。
  const fileList = await fetchFileList(parentId, fileListOptions)
  changeSet.nextCurrentId = parentId
  return {
    resolvedId: parentId,
    resolvedPath: normalizedPath,
    exactMatched: true,
    prefetchedList: listFromResponse(fileList),
    changeSet,
  }
}
