// 系统级角色与权限码常量。团队权限码见 ./teamPermissions.js。
// 本文件作为系统权限码的唯一权威来源；需保持与后端角色/权限码契约一致。

export const SYSTEM_ADMIN_ROLE = 'system_admin'

export const SYSTEM_PERMISSIONS = Object.freeze({
  fileWrite: 'file:write',
  fileDelete: 'file:delete',
  trashRead: 'trash:read',
  systemRoleManage: 'system:role:manage',
  systemPermissionRead: 'system:permission:read',
  systemAuditRead: 'system:audit:read',
})
