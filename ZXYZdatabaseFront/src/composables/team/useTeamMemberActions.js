import { ElMessage } from 'element-plus'

import { createTeamMember } from '@/api/team'
import { handleBusinessError } from '@/utils/error'
import { normalizePositiveId } from '@/utils/id'

/**
 * @typedef {Object} UseTeamMemberActionsOptions
 * @property {Object} permissionState - `useTeamPermissionState` 的返回值（唯一来源，勿重复推导）。
 * @property {Object} teamStore - 团队 Store 实例。
 * @property {Object} chatStore - 聊天 Store 实例。
 * @property {Object} workspace - IM 工作区实例。
 * @property {Object} [router] - Vue Router 实例。
 * @property {Function} [close] - 关闭当前面板/对话框的函数。
 */

/**
 * 团队成员/邀请/审核类**写操作**（`useTeamManagement` 的第 2/3 片）。
 *
 * 统一约定：返回 `true / false`（而不是抛异常），失败一律交给 `handleBusinessError`
 * 渲染提示——调用方（抽屉、表格按钮）只关心成败，不重复写 try/catch。
 *
 * @param {UseTeamMemberActionsOptions} options - 配置选项。
 * @returns {{ loadTeamMembersSafe: Function, muteMember: Function, unmuteMember: Function, approveJoinRequest: Function, rejectJoinRequest: Function, removeMember: Function, createMemberAccount: Function, searchUsers: Function, inviteUser: Function, startDirectChat: Function }} 成员相关操作。
 */
export function useTeamMemberActions({
  permissionState,
  teamStore,
  chatStore,
  workspace,
  router = null,
  close = null,
}) {
  const teamManagement = teamStore
  const { selectedTeamId, currentTeamId } = permissionState

  async function loadTeamMembersSafe(teamId = selectedTeamId.value) {
    const normalizedTeamId = normalizePositiveId(teamId)
    return teamManagement.loadTeamMembersSafe(normalizedTeamId)
  }

  async function muteMember(muteForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.muteMember(teamId, muteForm)
      muteForm.userId = null
      muteForm.reason = ''
      ElMessage.success('成员已禁言')
      return true
    } catch (error) {
      handleBusinessError(error, '禁言成员失败')
      return false
    }
  }

  async function unmuteMember(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.unmuteMember(teamId, userId)
      ElMessage.success('已解除禁言')
      return true
    } catch (error) {
      handleBusinessError(error, '解除禁言失败')
      return false
    }
  }

  async function approveJoinRequest(requestId) {
    try {
      await teamManagement.approveJoinRequest(requestId)
      await chatStore.loadConversations()
      ElMessage.success('已通过申请')
      return true
    } catch (error) {
      handleBusinessError(error, '审核申请失败')
      return false
    }
  }

  async function rejectJoinRequest(requestId) {
    try {
      await teamManagement.rejectJoinRequest(requestId)
      ElMessage.success('已拒绝申请')
      return true
    } catch (error) {
      handleBusinessError(error, '审核申请失败')
      return false
    }
  }

  async function removeMember(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.removeMember(teamId, userId)
      await chatStore.loadConversations(teamId)
      ElMessage.success('已移除成员')
      return true
    } catch (error) {
      handleBusinessError(error, '移除成员失败')
      return false
    }
  }

  async function createMemberAccount(memberForm) {
    const teamId = currentTeamId()
    if (!teamId) return false
    const username = memberForm.username.trim()
    const password = memberForm.password.trim()
    if (!username || !password) {
      ElMessage.warning('请填写成员用户名和初始密码')
      return false
    }
    if (password.length < 8) {
      // 与后端对齐：team-service 的 app.team.min-password-length 已由 6 提到 8，
      // user-service 的 InternalCreateTeamUserRequest 也要求 ≥8。前端这条只是「提前提示」，
      // 漏改的表现是「本地放行 → 提交后被后端拒」，错误落进通用提示里很难定位。
      ElMessage.warning('初始密码不能少于 8 位')
      return false
    }
    try {
      await createTeamMember(teamId, {
        username,
        password,
        name: memberForm.name.trim() || null,
        roleCode: memberForm.roleCode,
      })
      memberForm.username = ''
      memberForm.password = ''
      memberForm.name = ''
      memberForm.roleCode = 'team_member'
      // 成员创建会影响成员列表、团队会话和左侧团队摘要，需要统一刷新。
      await workspace.refreshTeamMemberContext(teamId)
      ElMessage.success('成员账号已创建')
      return true
    } catch (error) {
      handleBusinessError(error, '创建成员账号失败')
      return false
    }
  }

  async function searchUsers(keyword) {
    try {
      await teamManagement.searchUsers(keyword)
      return true
    } catch (error) {
      handleBusinessError(error, '搜索用户失败')
      return false
    }
  }

  async function inviteUser(userId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await teamManagement.inviteUser(teamId, userId)
      ElMessage.success('邀请已发送')
      return true
    } catch (error) {
      handleBusinessError(error, '发送邀请失败')
      return false
    }
  }

  async function startDirectChat(targetUserId) {
    const teamId = currentTeamId()
    if (!teamId) return false
    try {
      await chatStore.createDirectConversationAndOpen(teamId, targetUserId)
      if (typeof close === 'function') {
        close()
      }
      if (router) {
        router.push({ name: 'chatHome' })
      }
      return true
    } catch (error) {
      handleBusinessError(error, '创建私聊失败')
      return false
    }
  }

  return {
    loadTeamMembersSafe,
    muteMember,
    unmuteMember,
    approveJoinRequest,
    rejectJoinRequest,
    removeMember,
    createMemberAccount,
    searchUsers,
    inviteUser,
    startDirectChat,
  }
}
