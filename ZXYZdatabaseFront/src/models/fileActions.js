export const FILE_CONTEXT_ACTIONS = Object.freeze({
  ARCHIVE_DOWNLOAD: 'archiveDownload',
  BATCH_DOWNLOAD: 'batchDownload',
  COPY: 'copy',
  COPY_DOWNLOAD_LINK: 'copyDownloadLink',
  COPY_FILE_NAME: 'copyFileName',
  CREATE_FOLDER: 'createFolder',
  CREATE_PROJECT_GROUP: 'createProjectGroup',
  DELETE: 'delete',
  DELETE_FOREVER: 'deleteForever',
  DELETE_FOREVER_SELECTED: 'deleteForeverSelected',
  DOWNLOAD: 'download',
  GET_DIRECT_AND_SHORT_LINK: 'getDirectAndShortLink',
  GET_DIRECT_LINK: 'getDirectLink',
  MOVE: 'move',
  OPEN: 'open',
  OPEN_IN_NEW_TAB: 'openInNewTab',
  PREVIEW: 'preview',
  PROJECT_SETTINGS: 'projectSettings',
  REFRESH: 'refresh',
  RENAME: 'rename',
  RESTORE: 'restore',
  RESTORE_SELECTED: 'restoreSelected',
  SEND_TO_CONVERSATION: 'sendToConversation',
  SHARE_FILE: 'shareFile',
  UPLOAD_FILE: 'uploadFile',
  UPLOAD_FOLDER: 'uploadFolder',
})

export const FILE_ROW_ACTIONS = Object.freeze({
  DELETE_FOREVER: 'deleteForever',
  OPEN_PROJECT: 'openProject',
  RESTORE: 'restore',
})

/**
 * FileExplorer 向页面层派发的文件操作事件。
 *
 * @typedef {Object} FileActionPayload
 * @property {string} action 取值来自 FILE_CONTEXT_ACTIONS。
 * @property {string} [contextType] 菜单上下文：blank、file、folder、multi 等。
 * @property {Array<Object>} [selectedItems] 当前选中的文件或文件夹。
 * @property {Object|null} [targetItem] 触发菜单的目标项。
 * @property {string|number|null} [anchorId] 选择锚点 ID。
 * @property {string} [currentPath] 当前目录路径。
 * @property {string} [targetPath] 动作目标目录路径。
 */
