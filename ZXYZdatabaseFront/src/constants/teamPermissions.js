export const TEAM_PERMISSION_CODES = Object.freeze({
  updateTeam: 'team:update',
  createMember: 'team:member:create',
  inviteMember: 'team:member:invite',
  assignRole: 'team:member:assign-role',
  removeMember: 'team:member:remove',
  publishAnnouncement: 'team:announcement:publish',
  manageMute: 'team:mute:manage',
  manageInviteLink: 'team:invite-link:manage',
  reviewJoinRequest: 'team:join-request:review',
  readPermission: 'team:permission:read',
  manageRole: 'team:role:manage',
  readAudit: 'team:audit:read',
  allocateStorage: 'team:storage:allocate',
})

export const TEAM_PERMISSION_WORKBENCH_CODES = Object.freeze([
  TEAM_PERMISSION_CODES.readPermission,
  TEAM_PERMISSION_CODES.manageRole,
  TEAM_PERMISSION_CODES.assignRole,
])

export const TEAM_PERMISSION_CENTER_CODES = Object.freeze([
  ...TEAM_PERMISSION_WORKBENCH_CODES,
  TEAM_PERMISSION_CODES.readAudit,
])

export const TEAM_MANAGEMENT_PERMISSION_CODES = Object.freeze([
  TEAM_PERMISSION_CODES.updateTeam,
  TEAM_PERMISSION_CODES.createMember,
  TEAM_PERMISSION_CODES.inviteMember,
  TEAM_PERMISSION_CODES.assignRole,
  TEAM_PERMISSION_CODES.removeMember,
  TEAM_PERMISSION_CODES.publishAnnouncement,
  TEAM_PERMISSION_CODES.manageMute,
  TEAM_PERMISSION_CODES.manageInviteLink,
  TEAM_PERMISSION_CODES.reviewJoinRequest,
  ...TEAM_PERMISSION_WORKBENCH_CODES,
])
