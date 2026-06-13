import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'

import { createTeamProject, submitProjectCreateRequest } from '@/api/project'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

const DEFAULT_STORAGE_LIMIT_GB = 10
const BYTES_PER_GB = 1024 * 1024 * 1024

export function createDefaultProjectForm(leaderUserId = null) {
  return {
    name: '',
    description: '',
    leaderUserId,
    unlimited: false,
    storageLimitGb: DEFAULT_STORAGE_LIMIT_GB,
  }
}

export function resolveProjectStorageLimit(form) {
  if (form?.unlimited) {
    return null
  }

  const gb = Number(form?.storageLimitGb)
  return Number.isFinite(gb) && gb > 0 ? Math.round(gb * BYTES_PER_GB) : null
}

/**
 * @typedef {Object} UseCreateProjectActionOptions
 * @property {Function} [getTeamId] - 获取当前团队 ID 的函数。
 * @property {Function} [getCurrentUserId] - 获取当前用户 ID 的函数。
 * @property {Function} [canManageProjects] - 判断当前用户是否有项目管理权限的函数。
 * @property {Function} [loadTeamMembers] - 加载团队成员列表的函数。
 * @property {Function} [onSuccess] - 创建成功后的回调函数。
 */

/**
 * 项目组创建操作组合函数，管理创建项目组的对话框与提交逻辑。
 *
 * @param {UseCreateProjectActionOptions} [options={}] - 配置选项。
 * @returns {{ createProjectDialogVisible: import('vue').Ref<boolean>, creatingProject: import('vue').Ref<boolean>, projectForm: import('vue').Ref<Object>, canManageProjectCreation: import('vue').ComputedRef<boolean>, openCreateProjectDialog: Function, closeCreateProjectDialog: Function, submitCreateProject: Function }} 项目组创建状态与操作方法。
 */
export function useCreateProjectAction(options = {}) {
  const { getTeamId, getCurrentUserId, canManageProjects, loadTeamMembers, onSuccess } = options

  const createProjectDialogVisible = ref(false)
  const creatingProject = ref(false)
  const projectForm = ref(createDefaultProjectForm())

  const canManageProjectCreation = computed(() => Boolean(canManageProjects?.()))

  function resetProjectForm() {
    projectForm.value = createDefaultProjectForm(getCurrentUserId?.() ?? null)
  }

  function closeCreateProjectDialog() {
    if (creatingProject.value) {
      return
    }

    createProjectDialogVisible.value = false
  }

  function openCreateProjectDialog() {
    const teamId = normalizePositiveId(getTeamId?.())
    if (!teamId) {
      ElMessage.warning('请先选择团队')
      return false
    }

    resetProjectForm()
    createProjectDialogVisible.value = true
    // 成员列表只用于负责人选择，失败不应阻塞项目创建表单打开。
    loadTeamMembers?.(teamId)?.catch?.(() => {})
    return true
  }

  async function submitCreateProject() {
    const teamId = normalizePositiveId(getTeamId?.())
    const name = String(projectForm.value.name || '').trim()
    const canManage = canManageProjectCreation.value

    if (!teamId) {
      ElMessage.warning('请先选择团队')
      return false
    }
    if (!name) {
      ElMessage.warning('请输入项目名称')
      return false
    }
    if (!projectForm.value.leaderUserId) {
      ElMessage.warning('请选择项目负责人')
      return false
    }

    const payload = {
      name,
      description: String(projectForm.value.description || '').trim() || null,
      leaderUserId: projectForm.value.leaderUserId,
      storageLimit: resolveProjectStorageLimit(projectForm.value),
    }

    creatingProject.value = true
    try {
      if (canManage) {
        await createTeamProject(teamId, payload)
      } else {
        await submitProjectCreateRequest(teamId, payload)
      }
      createProjectDialogVisible.value = false
      await onSuccess?.()
      ElMessage.success(canManage ? '项目组已创建' : '项目组申请已提交')
      return true
    } catch (error) {
      handleBusinessError(error, canManage ? '创建项目组失败' : '提交项目组申请失败')
      return false
    } finally {
      creatingProject.value = false
    }
  }

  return {
    createProjectDialogVisible,
    creatingProject,
    projectForm,
    canManageProjectCreation,
    openCreateProjectDialog,
    closeCreateProjectDialog,
    submitCreateProject,
  }
}
