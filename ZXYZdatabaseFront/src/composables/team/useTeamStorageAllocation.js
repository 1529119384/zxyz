import { computed, ref, unref } from 'vue'
import { ElMessage } from 'element-plus'

import { fetchTeamMembersStorage, updateMemberStorageLimit } from '@/api/team'
import { TEAM_PERMISSION_CODES } from '@/constants/teamPermissions'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

/**
 * @typedef {Object} UseTeamStorageAllocationOptions
 * @property {Object} [teamStore] - 团队 Store 实例。
 * @property {import('vue').Ref<number>|Function|number} [teamId] - 团队 ID，支持 Ref、函数或原始值。
 */

/**
 * 团队存储分配组合函数，管理成员存储用量查看与限额设置。
 *
 * @param {UseTeamStorageAllocationOptions} [options={}] - 配置选项。
 * @returns {{ memberStorageList: import('vue').Ref<Array>, loadingMembers: import('vue').Ref<boolean>, savingLimit: import('vue').Ref<boolean>, canAllocateStorage: import('vue').ComputedRef<boolean>, selectedTeamId: import('vue').ComputedRef<number|null>, loadMemberStorage: Function, saveMemberLimit: Function }} 团队存储分配状态与操作方法。
 */
export function useTeamStorageAllocation({ teamStore = useTeamStore(), teamId = null } = {}) {
  const memberStorageList = ref([])
  const loadingMembers = ref(false)
  const savingLimit = ref(false)

  const selectedTeamId = computed(() => {
    const explicitId = typeof teamId === 'function' ? teamId() : unref(teamId)
    return normalizePositiveId(explicitId) || normalizePositiveId(teamStore.selectedTeamId)
  })

  const canAllocateStorage = computed(() => {
    const tid = selectedTeamId.value
    return tid
      ? teamStore.hasTeamPermission({
          teamId: tid,
          code: TEAM_PERMISSION_CODES.allocateStorage,
        })
      : false
  })

  async function loadMemberStorage() {
    const tid = selectedTeamId.value
    if (!tid) {
      memberStorageList.value = []
      return []
    }
    loadingMembers.value = true
    try {
      const response = await fetchTeamMembersStorage(tid)
      memberStorageList.value = Array.isArray(response?.data) ? response.data : []
      return memberStorageList.value
    } catch (error) {
      handleBusinessError(error, '加载成员存储用量失败')
      memberStorageList.value = []
      return []
    } finally {
      loadingMembers.value = false
    }
  }

  async function saveMemberLimit(userId, personalStorageLimit) {
    const tid = selectedTeamId.value
    if (!tid) return false
    savingLimit.value = true
    try {
      await updateMemberStorageLimit(tid, userId, {
        personalStorageLimit: personalStorageLimit ?? null,
      })
      const member = memberStorageList.value.find((m) => m.userId === userId)
      if (member) {
        member.personalStorageLimit = personalStorageLimit
      }
      ElMessage.success('个人存储限额已保存')
      return true
    } catch (error) {
      handleBusinessError(error, '保存存储限额失败')
      return false
    } finally {
      savingLimit.value = false
    }
  }

  return {
    memberStorageList,
    loadingMembers,
    savingLimit,
    canAllocateStorage,
    selectedTeamId,
    loadMemberStorage,
    saveMemberLimit,
  }
}
