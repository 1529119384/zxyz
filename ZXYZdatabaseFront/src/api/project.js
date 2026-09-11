// @ts-check
import request from '@/utils/request'

/**
 * @param {string|number} teamId - 团队 ID
 * @param {{signal?: AbortSignal}} [options] - 可选中止信号
 */
export const fetchTeamProjects = (teamId, { signal } = {}) =>
  request.get(`/api/project-catalog/teams/${teamId}/projects`, { signal })

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 项目创建请求体
 */
export const createTeamProject = (teamId, payload) =>
  request.post(`/api/project-catalog/teams/${teamId}/projects`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 * @param {Record<string, unknown>} payload - 申请请求体
 */
export const submitProjectCreateRequest = (teamId, payload) =>
  request.post(`/api/project-create-requests/teams/${teamId}`, payload)

/**
 * @param {string|number} teamId - 团队 ID
 */
export const fetchPendingProjectCreateRequests = (teamId) =>
  request.get(`/api/project-create-requests/teams/${teamId}/pending`)

/**
 * @param {string|number} applicationId - 申请 ID
 * @param {Record<string, unknown>} [payload] - 审批请求体
 */
export const approveProjectCreateRequest = (applicationId, payload = {}) =>
  request.post(`/api/project-create-requests/${applicationId}/approve`, payload)

/**
 * @param {string|number} applicationId - 申请 ID
 * @param {Record<string, unknown>} [payload] - 驳回请求体
 */
export const rejectProjectCreateRequest = (applicationId, payload = {}) =>
  request.post(`/api/project-create-requests/${applicationId}/reject`, payload)

/**
 * @param {string|number} projectId - 项目 ID
 */
export const fetchProjectMembers = (projectId) =>
  request.get(`/api/project-members/projects/${projectId}/members`)

/**
 * @param {string|number} projectId - 项目 ID
 * @param {Record<string, unknown>} payload - 成员添加请求体
 */
export const addProjectMember = (projectId, payload) =>
  request.post(`/api/project-members/projects/${projectId}/members`, payload)

/**
 * @param {string|number} projectId - 项目 ID
 * @param {Record<string, unknown>} payload - 移交请求体
 */
export const transferProjectLeader = (projectId, payload) =>
  request.patch(`/api/project-members/projects/${projectId}/leader`, payload)

/**
 * @param {string|number} projectId - 项目 ID
 * @param {Record<string, unknown>} payload - 配额请求体
 */
export const updateProjectQuota = (projectId, payload) =>
  request.patch(`/api/project-quotas/projects/${projectId}`, payload)

/**
 * @param {string|number} projectId - 项目 ID
 */
export const archiveProject = (projectId) =>
  request.patch(`/api/project-lifecycle/projects/${projectId}/archive`)
