export const PROJECT_ROOT_ID = '__project_root__'
export const PROJECT_ROOT_NAME = '项目组'
export const PROJECT_ROOT_PATH = `/${PROJECT_ROOT_NAME}`

export function isProjectRootId(value) {
  return String(value) === PROJECT_ROOT_ID
}

export function isProjectRootEntry(entry) {
  return entry?.virtualType === 'projectRoot'
}

export function isProjectFolderEntry(entry) {
  return entry?.virtualType === 'projectFolder'
}

export function isProjectVirtualEntry(entry) {
  return isProjectRootEntry(entry) || isProjectFolderEntry(entry)
}

export function createProjectRootEntry(teamId) {
  return {
    id: PROJECT_ROOT_ID,
    fileName: PROJECT_ROOT_NAME,
    type: 0,
    fileType: 0,
    category: 'folder',
    fileSize: 0,
    parentId: -1,
    teamId: teamId ?? null,
    storePath: PROJECT_ROOT_PATH,
    createTime: '',
    modifyTime: '',
    virtualType: 'projectRoot',
  }
}

export function createProjectFolderEntry(project = {}) {
  return {
    id: `project-${project.id}`,
    fileName: project.name || `项目组 ${project.id}`,
    type: 0,
    fileType: 0,
    category: 'folder',
    fileSize: 0,
    parentId: PROJECT_ROOT_ID,
    teamId: project.teamId ?? null,
    storePath: `${PROJECT_ROOT_PATH}/${project.name || project.id}`,
    createTime: project.createTime || '',
    modifyTime: project.updateTime || project.createTime || '',
    virtualType: 'projectFolder',
    projectId: project.id,
    conversationId: project.conversationId,
    accessible: project.accessible !== false,
    manageable: Boolean(project.manageable),
    storageLimit: project.storageLimit ?? null,
    usedStorage: project.usedStorage ?? 0,
    project,
  }
}
