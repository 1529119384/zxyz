<template>
  <section class="permission-page">
    <header class="permission-header">
      <div>
        <h1>权限管理</h1>
        <p>系统权限和团队权限统一在这里查看与配置。</p>
        <p v-if="readonlyModeText" class="permission-tip permission-tip--header">
          {{ readonlyModeText }}
        </p>
      </div>
      <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
    </header>

    <el-tabs v-model="activeTab" class="permission-tabs">
      <el-tab-pane label="系统权限" name="system">
        <div class="permission-stack">
          <el-empty
            v-if="!showSystemPermissionTab"
            class="team-empty"
            description="当前账号没有系统权限中心访问权限"
          />

          <div v-show="showSystemPermissionTab" class="permission-stack__content">
            <RoleManagementPanel
              title="系统角色"
              role-type-label="系统角色"
              create-label="新增角色"
              subtitle="管理系统级角色的授权范围，内置角色不可删除。"
              :roles="safeSystemRoles"
              :permissions="safeSystemPermissions"
              :can-read="canReadSystemPermissionCenter"
              :can-manage="canManageSystemRoles"
              no-access-text="你没有系统权限中心查看权限，当前分区不可用。"
              readonly-text="当前为只读模式，系统角色不可编辑。"
              empty-text="暂无系统角色"
              :is-builtin-role="isBuiltinSystemRole"
              :format-permission="formatSystemPermission"
              :save-role="saveSystemRoleFromPanel"
              :delete-role="deleteSystemRoleFromPanel"
            />

            <div class="auxiliary-grid">
              <section class="panel panel--assignment">
                <div class="panel-title">
                  <h3>系统角色任命</h3>
                  <p>为指定用户分配系统角色，不能给当前登录账号赋权。</p>
                </div>
                <p v-if="!canManageSystemRoles" class="permission-tip">
                  缺少 `system:role:manage` 权限，当前表单只读。
                </p>
                <div class="inline-form">
                  <el-select
                    v-model="userRoleForm.userId"
                    filterable
                    remote
                    clearable
                    reserve-keyword
                    placeholder="搜索用户 ID、用户名或邮箱"
                    :remote-method="searchSystemUsers"
                    :loading="systemUserSearching"
                    :disabled="!canManageSystemRoles"
                  >
                    <el-option
                      v-for="item in filteredSystemUserOptions"
                      :key="resolveUserId(item)"
                      :label="formatUserOption(item)"
                      :value="resolveUserId(item)"
                    />
                  </el-select>
                  <el-select
                    v-model="userRoleForm.roleCode"
                    placeholder="选择角色"
                    :disabled="!canManageSystemRoles"
                  >
                    <el-option
                      v-for="item in safeSystemRoles"
                      :key="item.roleCode"
                      :label="item.roleName"
                      :value="item.roleCode"
                    />
                  </el-select>
                  <el-button
                    type="primary"
                    :disabled="!canManageSystemRoles"
                    @click="submitUserRoleAssign"
                    >保存</el-button
                  >
                </div>
              </section>

              <section class="panel">
                <div class="panel-title">
                  <h3>系统权限字典</h3>
                  <p>权限编码由后端维护，角色编辑时按编码前缀分组选择。</p>
                </div>
                <p v-if="!canReadSystemPermissionCenter" class="permission-tip">
                  你没有系统权限中心查看权限，当前分区不可用。
                </p>
                <el-table
                  :data="canReadSystemPermissionCenter ? safeSystemPermissions : []"
                  height="260"
                  :empty-text="canReadSystemPermissionCenter ? '暂无系统权限' : '无查看权限'"
                >
                  <el-table-column label="权限" min-width="220">
                    <template #default="{ row }">{{ formatPermissionLabel(row) }}</template>
                  </el-table-column>
                  <el-table-column
                    prop="permissionCode"
                    label="编码"
                    min-width="180"
                    show-overflow-tooltip
                  />
                </el-table>
              </section>

              <section class="panel panel--wide">
                <div class="panel-title">
                  <h3>系统审计</h3>
                  <p>记录系统权限中心的角色和授权变更。</p>
                </div>
                <p v-if="!canReadSystemAudit" class="permission-tip">
                  你没有系统审计查看权限，当前分区不可用。
                </p>
                <el-table
                  :data="canReadSystemAudit ? safeSystemAudit : []"
                  height="240"
                  :empty-text="canReadSystemAudit ? '暂无系统审计' : '无查看权限'"
                >
                  <el-table-column prop="operationType" label="操作" min-width="160" />
                  <el-table-column prop="targetType" label="目标" min-width="120" />
                  <el-table-column
                    prop="afterValue"
                    label="变更后"
                    min-width="220"
                    show-overflow-tooltip
                  />
                  <el-table-column prop="operationTime" label="时间" min-width="180" />
                </el-table>
              </section>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="团队权限" name="team">
        <div class="permission-stack">
          <el-empty v-if="!selectedTeamId" class="team-empty" description="请先选择团队" />
          <el-empty
            v-else-if="!showTeamPermissionTab"
            class="team-empty"
            description="当前团队下没有权限中心访问权限"
          />

          <div v-show="selectedTeamId && showTeamPermissionTab" class="permission-stack__content">
            <RoleManagementPanel
              title="团队角色"
              role-type-label="团队角色"
              create-label="新增团队角色"
              subtitle="管理当前团队内的角色权限，内置角色不可删除。"
              :roles="safeTeamRoles"
              :permissions="safeTeamPermissions"
              :can-read="canReadTeamPermissionCenter"
              :can-manage="canManageTeamRoles"
              no-access-text="你没有团队权限中心查看权限，当前分区不可用。"
              readonly-text="当前为只读模式，团队角色不可编辑。"
              empty-text="暂无团队角色"
              :is-builtin-role="isBuiltinTeamRole"
              :format-permission="formatTeamPermission"
              :save-role="saveTeamRoleFromPanel"
              :delete-role="deleteTeamRoleFromPanel"
            />

            <div class="auxiliary-grid">
              <section class="panel panel--assignment">
                <div class="panel-title">
                  <h3>团队角色任命</h3>
                  <p>给当前团队成员分配团队角色，不能调整当前登录账号。</p>
                </div>
                <p v-if="!canReadTeamPermissionCenter" class="permission-tip">
                  你没有团队权限中心查看权限，当前分区不可用。
                </p>
                <p v-else-if="!canAssignTeamMemberRole" class="permission-tip">
                  缺少 `team:member:assign-role` 权限，当前表单只读。
                </p>
                <div class="inline-form">
                  <el-select
                    v-model="memberRoleForm.userId"
                    placeholder="选择成员"
                    :disabled="!canAssignTeamMemberRole"
                  >
                    <el-option
                      v-for="item in assignableTeamMembers"
                      :key="item.userId"
                      :label="displayTeamMemberName(item)"
                      :value="item.userId"
                    />
                  </el-select>
                  <el-select
                    v-model="memberRoleForm.roleCode"
                    placeholder="选择角色"
                    :disabled="!canAssignTeamMemberRole"
                  >
                    <el-option
                      v-for="item in safeTeamRoles"
                      :key="item.roleCode"
                      :label="item.roleName"
                      :value="item.roleCode"
                    />
                  </el-select>
                  <el-button
                    type="primary"
                    :disabled="!canAssignTeamMemberRole"
                    @click="submitMemberRoleAssign"
                    >保存</el-button
                  >
                </div>
              </section>

              <section class="panel">
                <div class="panel-title">
                  <h3>团队权限字典</h3>
                  <p>当前团队可用权限，角色编辑时按编码前缀分组选择。</p>
                </div>
                <p v-if="!canReadTeamPermissionCenter" class="permission-tip">
                  你没有团队权限中心查看权限，当前分区不可用。
                </p>
                <el-table
                  :data="canReadTeamPermissionCenter ? safeTeamPermissions : []"
                  height="260"
                  :empty-text="canReadTeamPermissionCenter ? '暂无团队权限' : '无查看权限'"
                >
                  <el-table-column label="权限" min-width="220">
                    <template #default="{ row }">{{ formatPermissionLabel(row) }}</template>
                  </el-table-column>
                  <el-table-column
                    prop="permissionCode"
                    label="编码"
                    min-width="180"
                    show-overflow-tooltip
                  />
                </el-table>
              </section>

              <section class="panel panel--wide">
                <div class="panel-title">
                  <h3>团队审计</h3>
                  <p>记录当前团队角色、授权和成员角色变更。</p>
                </div>
                <p v-if="!canReadTeamAudit" class="permission-tip">
                  你没有团队审计查看权限，当前分区不可用。
                </p>
                <el-table
                  :data="canReadTeamAudit ? safeTeamPermissionAudit : []"
                  height="240"
                  :empty-text="canReadTeamAudit ? '暂无团队审计' : '无查看权限'"
                >
                  <el-table-column prop="operationType" label="操作" min-width="160" />
                  <el-table-column prop="targetType" label="目标" min-width="120" />
                  <el-table-column
                    prop="afterValue"
                    label="变更后"
                    min-width="220"
                    show-overflow-tooltip
                  />
                  <el-table-column prop="operationTime" label="时间" min-width="180" />
                </el-table>
              </section>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, unref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'

import RoleManagementPanel from '@/components/RoleManagementPanel.vue'
import {
  assignSystemRolePermissions,
  assignUserRole,
  createSystemRole,
  deleteSystemRole,
  fetchSystemPermissionAudit,
  fetchSystemPermissions,
  fetchSystemRoles,
  updateSystemRole,
} from '@/api/permission'
import { searchUsers } from '@/api/user'
import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { TEAM_PERMISSION_WORKBENCH_CODES } from '@/constants/teamPermissions'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

const BUILTIN_SYSTEM_ROLE_CODES = new Set(['system_admin', 'system_user'])

const route = useRoute()
const currentUserStore = useCurrentUserStore()
const permissionCenter = useTeamStore()
const activeTab = ref(route.query.scope === 'team' ? 'team' : 'system')
const systemPermissions = ref([])
const systemRoles = ref([])
const systemAudit = ref([])
const systemUserOptions = ref([])
const systemUserSearching = ref(false)
const userRoleForm = reactive({ userId: '', roleCode: '' })
const memberRoleForm = reactive({ userId: null, roleCode: '' })
const safeSystemPermissions = computed(() => readArray(systemPermissions))
const safeSystemRoles = computed(() => readArray(systemRoles))
const safeSystemAudit = computed(() => readArray(systemAudit))
const safeTeamPermissions = computed(() => readArray(permissionCenter.teamPermissions))
const safeTeamRoles = computed(() => readArray(permissionCenter.teamRoles))
const safeTeamPermissionAudit = computed(() => readArray(permissionCenter.teamPermissionAudit))
const safeTeamMembers = computed(() => readArray(permissionCenter.teamMembers))

const routeTeamId = computed(() => {
  const rawValue = Number(route.query.teamId)
  return Number.isSafeInteger(rawValue) && rawValue > 0 ? rawValue : null
})
const selectedTeamId = computed(
  () =>
    routeTeamId.value || permissionCenter.selectedTeamId || permissionCenter.teams[0]?.id || null,
)
const {
  currentUserId,
  displayName: displayTeamMemberName,
  hasTeamPermission,
  hasAnyTeamPermission,
  loadTeamMembersSafe,
} = useTeamManagement({ teamStore: permissionCenter, currentUserStore, teamId: selectedTeamId })
const canReadSystemPermissionCenter = computed(() =>
  currentUserStore.hasAnySystemPermission([
    'system:role:manage',
    'system:permission:read',
    'system:audit:read',
  ]),
)
const canManageSystemRoles = computed(() =>
  currentUserStore.hasSystemPermission('system:role:manage'),
)
const canReadSystemAudit = computed(() =>
  currentUserStore.hasAnySystemPermission(['system:role:manage', 'system:audit:read']),
)
const filteredSystemUserOptions = computed(() =>
  readArray(systemUserOptions).filter(
    (item) => Number(resolveUserId(item)) !== Number(currentUserId.value),
  ),
)
const assignableTeamMembers = computed(() =>
  safeTeamMembers.value.filter((item) => Number(item.userId) !== Number(currentUserId.value)),
)
const canManageTeamRoles = computed(() => hasTeamPermission('team:role:manage'))
const canAssignTeamMemberRole = computed(() => hasTeamPermission('team:member:assign-role'))
const canReadTeamPermissionCenter = computed(() =>
  hasAnyTeamPermission(TEAM_PERMISSION_WORKBENCH_CODES),
)
const canReadTeamAudit = computed(
  () => hasTeamPermission('team:audit:read') || canManageTeamRoles.value,
)
const showSystemPermissionTab = computed(
  () => canReadSystemPermissionCenter.value || canReadSystemAudit.value,
)
const showTeamPermissionTab = computed(
  () => canReadTeamPermissionCenter.value || canReadTeamAudit.value,
)
const readonlyModeText = computed(() => {
  if (activeTab.value === 'system') {
    if (!canReadSystemPermissionCenter.value && !canReadSystemAudit.value) {
      return '当前账号没有系统权限中心访问权限。'
    }
    if (!canManageSystemRoles.value) {
      return '当前为系统只读模式，可以查看权限信息，但不能执行角色任命或编辑。'
    }
    return ''
  }
  if (!selectedTeamId.value) {
    return '请先选择团队后再查看团队权限。'
  }
  if (!canReadTeamPermissionCenter.value && !canReadTeamAudit.value) {
    return '当前团队下没有权限中心访问权限。'
  }
  if (!canManageTeamRoles.value && !canAssignTeamMemberRole.value) {
    return '当前为团队只读模式，可以查看权限信息，但不能执行角色任命或编辑。'
  }
  return ''
})

watch(
  routeTeamId,
  (teamId) => {
    if (teamId) {
      permissionCenter.setSelectedTeam(teamId)
    }
  },
  { immediate: true },
)

watch(
  () => route.query.scope,
  (scope) => {
    activeTab.value = scope === 'team' ? 'team' : 'system'
  },
  { immediate: true },
)

watch(activeTab, (tab) => {
  if (tab !== 'system' && tab !== 'team') {
    activeTab.value = 'system'
  }
})

watch(
  selectedTeamId,
  async (teamId) => {
    if (!teamId) {
      return
    }
    try {
      await loadTeamPermissionData(teamId)
    } catch (error) {
      handleBusinessError(error, '加载团队权限数据失败')
    }
  },
  { immediate: true },
)

async function refreshAll() {
  try {
    await Promise.all([
      showSystemPermissionTab.value ? loadSystemPermissionData() : Promise.resolve(),
      selectedTeamId.value ? loadTeamPermissionData(selectedTeamId.value) : Promise.resolve(),
    ])
  } catch (error) {
    handleBusinessError(error, '加载权限管理页失败')
  }
}

async function loadSystemPermissionData() {
  if (!canReadSystemPermissionCenter.value && !canReadSystemAudit.value) {
    systemPermissions.value = []
    systemRoles.value = []
    systemAudit.value = []
    return
  }
  const [permissionsResult, rolesResult, auditResult] = await Promise.allSettled([
    canReadSystemPermissionCenter.value ? fetchSystemPermissions() : Promise.resolve({ data: [] }),
    canReadSystemPermissionCenter.value ? fetchSystemRoles() : Promise.resolve({ data: [] }),
    canReadSystemAudit.value ? fetchSystemPermissionAudit() : Promise.resolve({ data: [] }),
  ])
  systemPermissions.value =
    permissionsResult.status === 'fulfilled' && Array.isArray(permissionsResult.value?.data)
      ? permissionsResult.value.data
      : []
  systemRoles.value =
    rolesResult.status === 'fulfilled' && Array.isArray(rolesResult.value?.data)
      ? rolesResult.value.data
      : []
  systemAudit.value =
    auditResult.status === 'fulfilled' && Array.isArray(auditResult.value?.data)
      ? auditResult.value.data
      : []
}

async function loadTeamPermissionData(teamId) {
  if (!teamId) {
    permissionCenter.clearTeamPermissionCenter()
    return
  }
  await loadTeamMembersSafe(teamId)
  await permissionCenter.loadTeamPermissionCenter(teamId, {
    includePermissionCenter: canReadTeamPermissionCenter.value,
    includeAudit: canReadTeamAudit.value,
    throwOnFailure: false,
  })
}

function createAutoRoleCode(prefix) {
  const random = Math.random().toString(36).slice(2, 8)
  return `${prefix}_custom_${Date.now()}_${random}`
}

function formatPermissionLabel(permission) {
  if (!permission) {
    return ''
  }
  const name = permission.permissionName || permission.name || ''
  const code = permission.permissionCode || permission.code || ''
  return name && code ? `${name} (${code})` : name || code
}

function findPermissionLabel(permissions, code) {
  const permission = readArray(permissions).find((item) => item.permissionCode === code)
  return permission ? formatPermissionLabel(permission) : code
}

function formatSystemPermission(code) {
  return findPermissionLabel(safeSystemPermissions.value, code)
}

function formatTeamPermission(code) {
  return findPermissionLabel(safeTeamPermissions.value, code)
}

function isBuiltinSystemRole(row) {
  return BUILTIN_SYSTEM_ROLE_CODES.has(row?.roleCode)
}

function isBuiltinTeamRole(row) {
  return Boolean(row?.builtin)
}

function formatUserOption(user) {
  const userId = resolveUserId(user)
  const displayName = displayTeamMemberName({ ...user, userId })
  const email = user.email ? ` / ${user.email}` : ''
  return `${displayName} (${userId})${email}`
}

function resolveUserId(user) {
  return user?.userId ?? user?.id ?? null
}

async function searchSystemUsers(keyword) {
  const normalizedKeyword = typeof keyword === 'string' ? keyword.trim() : ''
  if (!normalizedKeyword) {
    systemUserOptions.value = []
    return
  }
  systemUserSearching.value = true
  try {
    const response = await searchUsers(normalizedKeyword)
    systemUserOptions.value = Array.isArray(response?.data)
      ? response.data.filter((item) => Number(resolveUserId(item)) !== Number(currentUserId.value))
      : []
  } catch (error) {
    handleBusinessError(error, '搜索用户失败')
  } finally {
    systemUserSearching.value = false
  }
}

async function saveSystemRoleFromPanel(draft) {
  if (!canManageSystemRoles.value) {
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

async function deleteSystemRoleFromPanel(row) {
  if (!canManageSystemRoles.value || isBuiltinSystemRole(row)) {
    return false
  }
  try {
    await ElMessageBox.confirm(
      `确认删除系统角色“${row.roleName || row.roleCode}”？删除后不可恢复。`,
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

async function submitUserRoleAssign() {
  if (!canManageSystemRoles.value) {
    return
  }
  const userId = Number(userRoleForm.userId)
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    ElMessage.warning('请先搜索并选择用户')
    return
  }
  if (!userRoleForm.roleCode) {
    ElMessage.warning('请选择角色')
    return
  }
  if (userId === Number(currentUserId.value)) {
    ElMessage.warning('不能给自己赋予系统角色')
    return
  }
  try {
    await assignUserRole(userId, { roleCode: userRoleForm.roleCode })
    userRoleForm.userId = ''
    userRoleForm.roleCode = ''
    systemUserOptions.value = []
    await refreshAll()
    ElMessage.success('系统角色任命已更新')
  } catch (error) {
    handleBusinessError(error, '更新系统角色任命失败')
  }
}

async function saveTeamRoleFromPanel(draft) {
  if (!canManageTeamRoles.value) {
    return false
  }
  if (!selectedTeamId.value) {
    ElMessage.warning('请先选择团队')
    return false
  }
  if (!draft.roleName.trim()) {
    ElMessage.warning('请输入角色名称')
    return false
  }
  try {
    const payload = {
      roleName: draft.roleName.trim(),
      roleCode: draft.roleId ? draft.roleCode : createAutoRoleCode('team'),
      description: draft.description.trim(),
    }
    const response = await permissionCenter.saveTeamRole(
      selectedTeamId.value,
      payload,
      draft.roleId,
    )
    const roleId = response?.id || draft.roleId
    if (roleId) {
      await permissionCenter.updateTeamRolePermissions(
        selectedTeamId.value,
        roleId,
        draft.permissionCodes,
      )
    }
    await refreshAll()
    ElMessage.success('团队角色已保存')
    return true
  } catch (error) {
    handleBusinessError(error, '保存团队角色失败')
    return false
  }
}

async function deleteTeamRoleFromPanel(row) {
  if (!canManageTeamRoles.value || isBuiltinTeamRole(row)) {
    return false
  }
  try {
    await ElMessageBox.confirm(
      `确认删除团队角色“${row.roleName || row.roleCode}”？删除后不可恢复。`,
      '删除团队角色',
      { type: 'warning' },
    )
    await permissionCenter.removeTeamRole(selectedTeamId.value, row.id)
    await refreshAll()
    ElMessage.success('团队角色已删除')
    return true
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return false
    }
    handleBusinessError(error, '删除团队角色失败')
    return false
  }
}

async function submitMemberRoleAssign() {
  if (!canAssignTeamMemberRole.value) {
    return
  }
  if (!memberRoleForm.userId) {
    ElMessage.warning('请选择成员')
    return
  }
  if (!memberRoleForm.roleCode) {
    ElMessage.warning('请选择角色')
    return
  }
  if (Number(memberRoleForm.userId) === Number(currentUserId.value)) {
    ElMessage.warning('不能给自己调整团队角色')
    return
  }
  try {
    await permissionCenter.updateTeamMemberRole(
      selectedTeamId.value,
      memberRoleForm.userId,
      memberRoleForm.roleCode,
    )
    await refreshAll()
    ElMessage.success('团队角色任命已更新')
  } catch (error) {
    handleBusinessError(error, '更新团队角色任命失败')
  }
}

onMounted(() => {
  refreshAll()
})

function readArray(value) {
  // Element Plus 表格只接受数组，权限数据在切换团队/权限时统一兜底。
  const resolved = unref(value)
  return Array.isArray(resolved) ? resolved : []
}
</script>

<style scoped>
.permission-page,
.permission-stack,
.permission-stack__content,
.auxiliary-grid,
.panel {
  display: grid;
  gap: 16px;
}

.permission-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.permission-header h1,
.panel-title h3 {
  margin: 0;
}

.permission-header h1 {
  color: #111827;
  font-size: 26px;
  font-weight: 700;
}

.permission-header p,
.panel-title p,
.permission-tip {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
}

.permission-tip--header {
  font-weight: 500;
}

.permission-tabs {
  min-width: 0;
}

.permission-stack {
  align-items: start;
}

.auxiliary-grid {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: start;
}

.panel {
  min-width: 0;
  padding: 18px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.panel--wide {
  grid-column: 1 / -1;
}

.panel-title {
  display: grid;
  gap: 4px;
}

.panel-title h3 {
  color: #111827;
  font-size: 16px;
  font-weight: 700;
}

.inline-form {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(180px, 280px) auto;
  gap: 12px;
  align-items: center;
}

.inline-form :deep(.el-select) {
  width: 100%;
}

.team-empty {
  padding: 48px 0;
  border: 1px dashed #d0d5dd;
  border-radius: 8px;
  background: #ffffff;
}

@media (max-width: 900px) {
  .permission-header,
  .auxiliary-grid,
  .inline-form {
    grid-template-columns: 1fr;
  }

  .permission-header {
    display: grid;
  }

  .permission-header .el-button,
  .inline-form .el-button {
    width: 100%;
  }
}
</style>
