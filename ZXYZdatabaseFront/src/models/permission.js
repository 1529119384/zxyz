const PERMISSION_GROUP_ALIASES = Object.freeze({
  folder: 'file',
  trash: 'file',
})

const PERMISSION_GROUP_LABELS = Object.freeze({
  system: '系统管理',
  team: '团队管理',
  file: '文件管理',
  share: '分享管理',
  im: 'IM 协作',
  project: '项目管理',
  other: '其他权限',
})

const PERMISSION_GROUP_ORDER = Object.freeze([
  'system',
  'team',
  'file',
  'share',
  'im',
  'project',
  'other',
])

export function permissionNodeKey(permissionCode) {
  return `permission:${permissionCode}`
}

function resolvePermissionCode(permission) {
  return permission?.permissionCode || permission?.code || ''
}

function resolvePermissionGroup(permissionCode) {
  const prefix = String(permissionCode || '').split(':')[0] || 'other'
  return PERMISSION_GROUP_ALIASES[prefix] || prefix
}

function formatPermissionNodeLabel(permission) {
  const permissionCode = resolvePermissionCode(permission)
  const permissionName = permission?.permissionName || permission?.name || ''
  return permissionName && permissionCode
    ? `${permissionName} (${permissionCode})`
    : permissionName || permissionCode
}

export function buildPermissionTree(permissions = []) {
  const groups = new Map()
  const safePermissions = Array.isArray(permissions) ? permissions : []

  safePermissions.forEach((permission) => {
    const permissionCode = resolvePermissionCode(permission)
    if (!permissionCode) {
      return
    }
    const groupKey = resolvePermissionGroup(permissionCode)
    if (!groups.has(groupKey)) {
      groups.set(groupKey, [])
    }
    groups.get(groupKey).push({
      key: permissionNodeKey(permissionCode),
      label: formatPermissionNodeLabel(permission),
      permissionCode,
      isPermission: true,
    })
  })

  return Array.from(groups.entries())
    .sort(([leftKey], [rightKey]) => {
      const leftIndex = PERMISSION_GROUP_ORDER.indexOf(leftKey)
      const rightIndex = PERMISSION_GROUP_ORDER.indexOf(rightKey)
      const safeLeftIndex = leftIndex === -1 ? PERMISSION_GROUP_ORDER.length : leftIndex
      const safeRightIndex = rightIndex === -1 ? PERMISSION_GROUP_ORDER.length : rightIndex
      return safeLeftIndex === safeRightIndex
        ? leftKey.localeCompare(rightKey)
        : safeLeftIndex - safeRightIndex
    })
    .map(([groupKey, children]) => ({
      key: `group:${groupKey}`,
      label: PERMISSION_GROUP_LABELS[groupKey] || groupKey,
      children: children.sort((left, right) =>
        left.permissionCode.localeCompare(right.permissionCode),
      ),
    }))
}
