import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import {
  applyNavigationStateChange,
  createNavigationChangeSet,
  rememberPathChange,
} from '@/utils/navigationState'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { ROOT_ID, ROOT_PATH, useCorePathNavigation } from '@/composables/useCorePathNavigation'
import { resolvePathWithFetch } from '@/services/filePathResolver'
import { useCurrentIdStore } from '@/store/currentId'
import { logger } from '@/utils/logger'
import { normalizePath } from '@/utils/pathUtils'

/**
 * @typedef {Object} UseFileNavigationOptions
 * @property {import('vue').Ref<boolean>} isSpaceMode - 是否处于空间模式。
 * @property {Function} [onOpenFolder] - 进入文件夹后的回调。
 * @property {import('vue').Ref<Object>} [sortState] - 排序状态。
 * @property {Object} spaceContext - 空间上下文。
 * @property {string} teamId - 团队 ID。
 * @property {number} spaceType - 空间类型。
 * @property {string} [projectId] - 项目 ID。
 */

/**
 * 文件导航组合函数，管理目录路径解析、面包屑和文件夹进入逻辑。
 *
 * @param {UseFileNavigationOptions} options - 配置项。
 * @returns {{ currentPath: import('vue').Ref<string>, crumbArr: import('vue').ComputedRef, crumbPath: Function, buildFolderPath: Function, enterFolder: Function, navigationReady: import('vue').Ref<boolean>, latestResolvedNavigation: import('vue').Ref<Object|null> }} 文件导航状态与操作方法。
 */
export function useFileNavigation(options) {
  const { isSpaceMode, onOpenFolder, sortState, spaceContext, teamId, spaceType, projectId } =
    options

  const route = useRoute()
  const router = useRouter()
  const currentIdStore = useCurrentIdStore()
  const navigationReady = ref(false)
  const latestResolvedNavigation = ref(null)
  const spaceParams = computed(() =>
    resolveSpaceRequestParams(spaceContext, {
      teamId,
      spaceType,
      projectId,
    }),
  )
  let latestNavigationToken = 0
  let activeAbortController = null

  onBeforeUnmount(() => {
    if (activeAbortController) {
      activeAbortController.abort()
      activeAbortController = null
    }
  })

  const navigation = useCorePathNavigation({
    currentPath: computed(() => route.query.path || ''),
    currentParentId: computed(() => currentIdStore.currentId),
    pathToIdMap: computed(() => currentIdStore.pathToIdMap),
    setCurrentPath: (path) =>
      router.push({
        name: route.name || 'index',
        params: route.params,
        query: buildPathQuery(path),
      }),
    setCurrentParentId: (id) => currentIdStore.setCurrentId(id),
    onRememberPathId: (path, id) => currentIdStore.rememberPathId(path, id),
    onForgetPathId: (path) => currentIdStore.forgetPathId(path),
    decode: true,
  })

  function resolvePath(path) {
    if (activeAbortController) {
      activeAbortController.abort()
    }
    activeAbortController = new AbortController()
    const currentSpaceParams = spaceParams.value
    return resolvePathWithFetch(path, {
      rootId: ROOT_ID,
      rootPath: ROOT_PATH,
      getPathId: (targetPath) => currentIdStore.getPathId(targetPath),
      findNearestPathId: (targetPath) => currentIdStore.findNearestPathId(targetPath),
      pathToIdMap: currentIdStore.pathToIdMap,
      sortOptions: sortState?.value || {},
      signal: activeAbortController.signal,
      ...currentSpaceParams,
    })
  }

  function enterFolder(row) {
    return navigation.enterFolder(row, {
      rememberPath: false,
      onBeforeEnter: ({ nextPath, nextParentId }) => {
        const changeSet = createNavigationChangeSet()
        changeSet.nextCurrentId = nextParentId
        rememberPathChange(changeSet, nextPath, nextParentId)
        applyNavigationStateChange(currentIdStore, changeSet)
      },
      onAfterEnter: ({ row: folderRow, nextPath }) => {
        onOpenFolder?.({ row: folderRow, path: nextPath })
      },
    })
  }

  watch(
    [navigation.currentPath, isSpaceMode, spaceParams],
    async ([path, spaceMode]) => {
      if (!spaceMode) {
        return
      }

      const navigationToken = ++latestNavigationToken
      // 只有路径解析完成后，外层才能基于 currentId 安全刷新列表。
      navigationReady.value = false

      try {
        const normalizedPath = normalizePath(path)
        const result = await resolvePath(path)

        // 只接受最近一次导航结果，避免慢请求覆盖当前目录状态。
        if (navigationToken !== latestNavigationToken) {
          return
        }

        if (result.resolvedPath !== normalizedPath) {
          applyNavigationStateChange(currentIdStore, result.changeSet)
          ElMessage.warning('目标目录不存在，已回退到最近可访问目录')
          await router.replace({
            name: route.name || 'index',
            params: route.params,
            query: buildPathQuery(result.resolvedPath),
          })
          return
        }

        applyNavigationStateChange(currentIdStore, result.changeSet)
        latestResolvedNavigation.value = result
        navigationReady.value = true
      } catch (error) {
        if (navigationToken !== latestNavigationToken) {
          return
        }

        // 请求被主动中止（如组件卸载或新的导航开始），不视为错误。
        if (error?.code === 'ERR_CANCELED') {
          return
        }

        logger.error('解析目录路径失败:', error)
      }
    },
    { immediate: true },
  )

  function buildPathQuery(path) {
    const query = { ...route.query }
    if (path) {
      query.path = path
    } else {
      delete query.path
    }

    return query
  }

  return {
    currentPath: navigation.currentPath,
    crumbArr: navigation.crumbArr,
    crumbPath: navigation.crumbPath,
    buildFolderPath: navigation.buildFolderPath,
    enterFolder,
    navigationReady,
    latestResolvedNavigation,
  }
}
