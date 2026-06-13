import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { joinPath } from '@/utils/pathUtils'

/**
 * 公开分享页文件导航 composable，管理分享页目录浏览的路由导航。
 *
 * @returns {{ currentPath: import('vue').ComputedRef<string>, navigateToPath: Function, openFolder: Function }} 分享页文件导航状态与操作方法。
 */
export function useShareFileNavigation() {
  const route = useRoute()
  const router = useRouter()

  const shareKey = computed(() => String(route.params.shareKey || ''))
  const currentPath = computed(() => String(route.query.path || ''))

  function updateRouteQuery(nextQuery = {}) {
    const query = { ...route.query, ...nextQuery }

    Object.keys(query).forEach((key) => {
      if (query[key] === '' || query[key] === null || query[key] === undefined) {
        delete query[key]
      }
    })

    router.replace({
      name: 'sharePublic',
      params: { shareKey: shareKey.value },
      query,
    })
  }

  function navigateToPath(path) {
    updateRouteQuery({ path })
  }

  function openFolder(row) {
    const nextPath = joinPath(currentPath.value, row.fileName, {
      leadingSlash: false,
      decode: false,
    })

    navigateToPath(nextPath)
  }

  return {
    currentPath,
    navigateToPath,
    openFolder,
  }
}
