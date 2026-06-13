import { ElMessage } from 'element-plus'
import { ref, unref } from 'vue'

import { updateProjectQuota } from '@/api/project'
import {
  resolveProjectStorageLimit,
  useCreateProjectAction,
} from '@/composables/useCreateProjectAction'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

const DEFAULT_STORAGE_LIMIT_GB = 10
const GB = 1024 * 1024 * 1024

function resolveOptionValue(value) {
  return typeof value === 'function' ? value() : unref(value)
}

function createDefaultSettingsForm() {
  return {
    projectId: null,
    unlimited: false,
    storageLimitGb: DEFAULT_STORAGE_LIMIT_GB,
    usedStorage: 0,
  }
}

/**
 * @typedef {Object} UseProjectManagementOptions
 * @property {Object} [teamStore] - 团队 Store 实例。
 * @property {Object} [currentUserStore] - 当前用户 Store 实例。
 * @property {Object} [spaceContext] - 当前空间上下文对象。
 * @property {Function} [getTeamId] - 获取当前团队 ID 的函数。
 * @property {Function} [canManageProjects] - 判断当前用户是否有项目管理权限的函数。
 * @property {Function} [onCreated] - 项目创建成功后的回调函数。
 * @property {Function} [onSettingsSaved] - 项目配置保存成功后的回调函数。
 */

/**
 * 项目管理组合函数，整合项目创建与项目配置（存储限额）管理。
 *
 * @param {UseProjectManagementOptions} [options={}] - 配置选项。
 * @returns {{ createProjectDialogVisible: import('vue').Ref<boolean>, creatingProject: import('vue').Ref<boolean>, projectForm: import('vue').Ref<Object>, projectSettingsDialogVisible: import('vue').Ref<boolean>, savingProjectSettings: import('vue').Ref<boolean>, projectSettingsForm: import('vue').Ref<Object>, handleCreateProjectVisibleChange: Function, handleProjectSettingsVisibleChange: Function, openCreateProjectDialog: Function, submitCreateProject: Function, openProjectSettings: Function, submitProjectSettings: Function }} 项目管理状态与操作方法。
 */
export function useProjectManagement({
  teamStore = useTeamStore(),
  currentUserStore = null,
  spaceContext = null,
  getTeamId,
  canManageProjects,
  onCreated,
  onSettingsSaved,
} = {}) {
  const teamManagement = teamStore
  const projectSettingsDialogVisible = ref(false)
  const savingProjectSettings = ref(false)
  const projectSettingsForm = ref(createDefaultSettingsForm())

  function resolveTeamId() {
    return normalizePositiveId(
      resolveOptionValue(spaceContext?.teamId) ?? resolveOptionValue(getTeamId),
    )
  }

  function resolveCanManageProjects() {
    if (canManageProjects === undefined) {
      return Boolean(resolveOptionValue(spaceContext?.canManageProjects))
    }

    return Boolean(resolveOptionValue(canManageProjects))
  }

  const createProjectAction = useCreateProjectAction({
    getTeamId: resolveTeamId,
    getCurrentUserId: () => currentUserStore?.profile?.id ?? null,
    canManageProjects: resolveCanManageProjects,
    loadTeamMembers: (teamId) => teamManagement?.loadTeamMembers?.(teamId),
    onSuccess: onCreated,
  })
  const {
    createProjectDialogVisible,
    creatingProject,
    projectForm,
    openCreateProjectDialog,
    closeCreateProjectDialog,
    submitCreateProject,
  } = createProjectAction

  function handleCreateProjectVisibleChange(visible) {
    if (visible) {
      createProjectDialogVisible.value = visible
      return
    }

    closeCreateProjectDialog()
  }

  function handleProjectSettingsVisibleChange(visible) {
    if (!savingProjectSettings.value) {
      projectSettingsDialogVisible.value = visible
    }
  }

  function openProjectSettings(row = {}) {
    if (!row?.manageable) {
      ElMessage.warning('缺少项目配置权限')
      return false
    }

    const projectId = normalizePositiveId(row.projectId ?? row.id ?? row.project?.id)
    if (!projectId) {
      ElMessage.warning('项目组数据异常')
      return false
    }

    const storageLimit = row.storageLimit ?? row.project?.storageLimit ?? null
    projectSettingsForm.value = {
      projectId,
      unlimited: storageLimit == null,
      storageLimitGb: storageLimit ? Math.ceil(storageLimit / GB) : DEFAULT_STORAGE_LIMIT_GB,
      usedStorage: row.usedStorage ?? row.project?.usedStorage ?? 0,
    }
    projectSettingsDialogVisible.value = true
    return true
  }

  async function submitProjectSettings() {
    const projectId = normalizePositiveId(projectSettingsForm.value.projectId)
    if (!projectId) {
      ElMessage.warning('项目组数据异常')
      return false
    }

    savingProjectSettings.value = true
    try {
      await updateProjectQuota(projectId, {
        storageLimit: resolveProjectStorageLimit(projectSettingsForm.value),
      })
      projectSettingsDialogVisible.value = false
      await onSettingsSaved?.()
      ElMessage.success('项目配置已保存')
      return true
    } catch (error) {
      handleBusinessError(error, '保存项目配置失败')
      return false
    } finally {
      savingProjectSettings.value = false
    }
  }

  return {
    createProjectDialogVisible,
    creatingProject,
    projectForm,
    projectSettingsDialogVisible,
    savingProjectSettings,
    projectSettingsForm,
    handleCreateProjectVisibleChange,
    handleProjectSettingsVisibleChange,
    openCreateProjectDialog,
    submitCreateProject,
    openProjectSettings,
    submitProjectSettings,
  }
}
