import { ElMessage, ElMessageBox } from 'element-plus'

import {
  createTeamRole,
  updateTeamRole,
  deleteTeamRole,
  assignTeamRolePermissions,
  assignMemberRole,
} from '@/api/permission'
import { handleBusinessError } from '@/utils/error'

/**
 * 团队权限 CRUD 操作 composable。
 * 从 permission/index.vue 中提取，减少主组件复杂度。
 */
export function useTeamPermissionActions({ canManage, refreshAll, createAutoRoleCode }) {
  async function saveTeamRole(draft) {
    if (!canManage.value) {
      return false
    }
    if (!draft.roleName.trim()) {
      ElMessage.warning('请输入角色名称')
      return false
    }
    try {
      const payload = {
        roleName: draft.roleName.trim(),
        roleCode: draft.roleId ? draft.roleCode : createAutoRoleCode('team'),
        description: draft.description.trim(),
      }
      const response = draft.roleId
        ? await updateTeamRole(draft.roleId, payload)
        : await createTeamRole(payload)
      const roleId = response?.data?.id || draft.roleId
      if (roleId) {
        await assignTeamRolePermissions(roleId, { permissionCodes: draft.permissionCodes })
      }
      await refreshAll()
      ElMessage.success('团队角色已保存')
      return true
    } catch (error) {
      handleBusinessError(error, '保存团队角色失败')
      return false
    }
  }

  async function deleteTeamRole(row) {
    if (!canManage.value || row?.builtin) {
      return false
    }
    try {
      await ElMessageBox.confirm(
        `确认删除团队角色"${row.roleName || row.roleCode}"？删除后不可恢复。`,
        '删除团队角色',
        { type: 'warning' },
      )
      await deleteTeamRole(row.id)
      await refreshAll()
      ElMessage.success('团队角色已删除')
      return true
    } catch (error) {
      if (error === 'cancel' || error === 'close') {
        return false
      }
      handleBusinessError(error, '删除团队角色失败')
      return false
    }
  }

  async function submitMemberRoleAssign(form, teamId) {
    if (!canManage.value) {
      return
    }
    const userId = Number(form.userId)
    if (!Number.isSafeInteger(userId) || userId <= 0) {
      ElMessage.warning('请先搜索并选择成员')
      return
    }
    if (!form.roleCode) {
      ElMessage.warning('请选择角色')
      return
    }
    try {
      await assignMemberRole(teamId, userId, { roleCode: form.roleCode })
      form.userId = ''
      form.roleCode = ''
      await refreshAll()
      ElMessage.success('成员角色任命已更新')
    } catch (error) {
      handleBusinessError(error, '分配成员角色失败')
    }
  }

  return {
    saveTeamRole,
    deleteTeamRole,
    submitMemberRoleAssign,
  }
}
