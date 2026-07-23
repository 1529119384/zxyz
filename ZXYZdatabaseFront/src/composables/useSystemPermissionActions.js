import { ElMessage, ElMessageBox } from 'element-plus'

import {
  createSystemRole,
  updateSystemRole,
  deleteSystemRole,
  assignSystemRolePermissions,
  assignUserRole,
} from '@/api/permission'
import { handleBusinessError } from '@/utils/error'

/**
 * 系统权限 CRUD 操作 composable。
 * 从 permission/index.vue 中提取，减少主组件复杂度。
 */
export function useSystemPermissionActions({ canManage, refreshAll, createAutoRoleCode }) {
  async function saveSystemRole(draft) {
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
        roleCode: draft.roleId ? draft.roleCode : createAutoRoleCode('system'),
        description: draft.description.trim(),
      }
      const response = draft.roleId
        ? await updateSystemRole(draft.roleId, payload)
        : await createSystemRole(payload)
      const roleId = response?.data?.id || draft.roleId
      if (roleId) {
        await assignSystemRolePermissions(roleId, { permissionCodes: draft.permissionCodes })
      }
      await refreshAll()
      ElMessage.success('系统角色已保存')
      return true
    } catch (error) {
      handleBusinessError(error, '保存系统角色失败')
      return false
    }
  }

  async function deleteSystemRole(row) {
    if (!canManage.value || row?.builtin) {
      return false
    }
    try {
      await ElMessageBox.confirm(
        `确认删除系统角色"${row.roleName || row.roleCode}"？删除后不可恢复。`,
        '删除系统角色',
        { type: 'warning' },
      )
      await deleteSystemRole(row.id)
      await refreshAll()
      ElMessage.success('系统角色已删除')
      return true
    } catch (error) {
      if (error === 'cancel' || error === 'close') {
        return false
      }
      handleBusinessError(error, '删除系统角色失败')
      return false
    }
  }

  async function submitUserRoleAssign(form) {
    if (!canManage.value) {
      return
    }
    const userId = Number(form.userId)
    if (!Number.isSafeInteger(userId) || userId <= 0) {
      ElMessage.warning('请先搜索并选择用户')
      return
    }
    if (!form.roleCode) {
      ElMessage.warning('请选择角色')
      return
    }
    try {
      await assignUserRole(userId, { roleCode: form.roleCode })
      form.userId = ''
      form.roleCode = ''
      await refreshAll()
      ElMessage.success('系统角色任命已更新')
    } catch (error) {
      handleBusinessError(error, '分配系统角色失败')
    }
  }

  return {
    saveSystemRole,
    deleteSystemRole,
    submitUserRoleAssign,
  }
}
