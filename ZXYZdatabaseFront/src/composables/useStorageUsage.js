import { computed, ref } from 'vue'

import { fetchStorageUsage } from '@/api/files'
import { getSpaceUsageTitle } from '@/models/space'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'

function resolveValue(getter) {
  return typeof getter === 'function' ? getter() : null
}

/**
 * @typedef {Object} UseStorageUsageOptions
 * @property {Object} [spaceContext] - 当前空间上下文对象。
 * @property {Function} [getSpaceType] - 获取空间类型的函数。
 * @property {Function} [getTeamId] - 获取团队 ID 的函数。
 * @property {Function} [getProjectId] - 获取项目 ID 的函数。
 * @property {Function} [getCurrentFolderId] - 获取当前文件夹 ID 的函数。
 */

/**
 * 存储用量组合函数，获取并展示当前空间的存储使用情况。
 *
 * @param {UseStorageUsageOptions} [options={}] - 配置选项。
 * @returns {{ storageUsage: import('vue').Ref<Object|null>, spaceUsageTitle: import('vue').ComputedRef<string>, showStorageUsage: import('vue').ComputedRef<boolean>, storageUsagePercentage: import('vue').ComputedRef<number>, refreshStorageUsage: Function }} 存储用量状态与操作方法。
 */
export function useStorageUsage({
  spaceContext,
  getSpaceType,
  getTeamId,
  getProjectId,
  getCurrentFolderId,
} = {}) {
  const storageUsage = ref(null)

  const spaceUsageTitle = computed(() => {
    return getSpaceUsageTitle(
      resolveSpaceRequestParams(spaceContext, {
        spaceType: resolveValue(getSpaceType),
        teamId: resolveValue(getTeamId),
        projectId: resolveValue(getProjectId),
      }).spaceType,
    )
  })

  const showStorageUsage = computed(
    () => storageUsage.value && Number(resolveValue(getCurrentFolderId)) === -1,
  )

  const storageUsagePercentage = computed(() => {
    if (!storageUsage.value || storageUsage.value.unlimited || !storageUsage.value.storageLimit) {
      return 0
    }

    return Math.min(
      100,
      Math.round(
        (Number(storageUsage.value.usedStorage || 0) / Number(storageUsage.value.storageLimit)) *
          100,
      ),
    )
  })

  async function refreshStorageUsage() {
    try {
      const response = await fetchStorageUsage(
        resolveSpaceRequestParams(spaceContext, {
          spaceType: resolveValue(getSpaceType),
          teamId: resolveValue(getTeamId),
          projectId: resolveValue(getProjectId),
        }),
      )
      storageUsage.value = response?.data || null
    } catch {
      storageUsage.value = null
    }
  }

  return {
    storageUsage,
    spaceUsageTitle,
    showStorageUsage,
    storageUsagePercentage,
    refreshStorageUsage,
  }
}
