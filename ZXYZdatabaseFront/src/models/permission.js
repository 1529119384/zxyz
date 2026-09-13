// @ts-check
/** @type {Readonly<Record<string, string>>} */
const PERMISSION_GROUP_ALIASES = Object.freeze({
  folder: 'file',
  trash: 'file',
})

/** @type {Readonly<Record<string, string>>} */
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

/**
 * 后端权限对象的宽松描述：只描述本模块真正读取的字段，其余字段不影响判定。
 * @typedef {object} RawPermission
 * @property {string} [permissionCode]
 * @property {string} [code]
 * @property {string} [permissionName]
 * @property {string} [name]
 */

/**
 * 权限树节点（叶子为权限本身，父节点为分组）。
 * @typedef {object} PermissionTreeNode
 * @property {string} key
 * @property {string} label
 * @property {string} permissionCode
 * @property {boolean} isPermission
 */

/**
 * @param {unknown} permissionCode
 * @returns {string}
 */
export function permissionNodeKey(permissionCode) {
  return `permission:${permissionCode}`
}

/**
 * @param {RawPermission | null | undefined} permission
 * @returns {string}
 */
function resolvePermissionCode(permission) {
  return permission?.permissionCode || permission?.code || ''
}

/**
 * @param {unknown} permissionCode
 * @returns {string}
 */
function resolvePermissionGroup(permissionCode) {
  const prefix = String(permissionCode || '').split(':')[0] || 'other'
  return PERMISSION_GROUP_ALIASES[prefix] || prefix
}

/**
 * @param {RawPermission | null | undefined} permission
 * @returns {string}
 */
function formatPermissionNodeLabel(permission) {
  const permissionCode = resolvePermissionCode(permission)
  const permissionName = permission?.permissionName || permission?.name || ''
  return permissionName && permissionCode
    ? `${permissionName} (${permissionCode})`
    : permissionName || permissionCode
}

/**
 * 把后端权限列表组装成按分组排序的树；非法输入一律降级为空树而不是抛错。
 * @param {RawPermission[]} [permissions]
 * @returns {Array<{ key: string, label: string, children: PermissionTreeNode[] }>}
 */
export function buildPermissionTree(permissions = []) {
  /** @type {Map<string, PermissionTreeNode[]>} */
  const groups = new Map()
  const safePermissions = Array.isArray(permissions) ? permissions : []

  safePermissions.forEach((permission) => {
    const permissionCode = resolvePermissionCode(permission)
    if (!permissionCode) {
      return
    }
    const groupKey = resolvePermissionGroup(permissionCode)
    let bucket = groups.get(groupKey)
    if (!bucket) {
      bucket = []
      groups.set(groupKey, bucket)
    }
    bucket.push({
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
