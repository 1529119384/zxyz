import request from '@/utils/request'

export const fetchTeamProjects = (teamId, { signal } = {}) =>
  request.get(`/api/project-catalog/teams/${teamId}/projects`, { signal })

export const createTeamProject = (teamId, payload) =>
  request.post(`/api/project-catalog/teams/${teamId}/projects`, payload)

export const submitProjectCreateRequest = (teamId, payload) =>
  request.post(`/api/project-create-requests/teams/${teamId}`, payload)

export const fetchPendingProjectCreateRequests = (teamId) =>
  request.get(`/api/project-create-requests/teams/${teamId}/pending`)

export const approveProjectCreateRequest = (applicationId, payload = {}) =>
  request.post(`/api/project-create-requests/${applicationId}/approve`, payload)

export const rejectProjectCreateRequest = (applicationId, payload = {}) =>
  request.post(`/api/project-create-requests/${applicationId}/reject`, payload)

export const fetchProjectMembers = (projectId) =>
  request.get(`/api/project-members/projects/${projectId}/members`)

export const addProjectMember = (projectId, payload) =>
  request.post(`/api/project-members/projects/${projectId}/members`, payload)

export const transferProjectLeader = (projectId, payload) =>
  request.patch(`/api/project-members/projects/${projectId}/leader`, payload)

export const updateProjectQuota = (projectId, payload) =>
  request.patch(`/api/project-quotas/projects/${projectId}`, payload)

export const archiveProject = (projectId) =>
  request.patch(`/api/project-lifecycle/projects/${projectId}/archive`)
