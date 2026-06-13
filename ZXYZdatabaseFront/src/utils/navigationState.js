import { normalizePath } from '@/utils/pathUtils'

export function createNavigationChangeSet() {
  return {
    nextCurrentId: undefined,
    rememberedPaths: [],
    forgottenPaths: [],
  }
}

export function rememberPathChange(changeSet, path, id) {
  const normalizedPath = normalizePath(path, { decode: false })
  if (!changeSet || !normalizedPath) {
    return
  }

  changeSet.rememberedPaths.push({ path: normalizedPath, id })
}

export function collectForgottenPathBranch(pathMap, path) {
  const normalizedPath = normalizePath(path, { decode: false })
  if (!normalizedPath) {
    return []
  }

  return Object.keys(pathMap || {}).filter(
    (cachedPath) => cachedPath === normalizedPath || cachedPath.startsWith(`${normalizedPath}/`),
  )
}

export function applyNavigationStateChange(currentIdStore, changeSet) {
  if (!currentIdStore || !changeSet) {
    return
  }

  if (changeSet.nextCurrentId !== undefined) {
    currentIdStore.setCurrentId(changeSet.nextCurrentId)
  }

  changeSet.forgottenPaths.forEach((path) => {
    currentIdStore.forgetPathId(path)
  })

  changeSet.rememberedPaths.forEach(({ path, id }) => {
    currentIdStore.rememberPathId(path, id)
  })
}
