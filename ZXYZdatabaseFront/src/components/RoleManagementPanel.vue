<template>
  <section class="role-management-panel">
    <div class="role-panel-header">
      <div>
        <h3>{{ labels.title }}</h3>
        <p>{{ labels.subtitle }}</p>
      </div>
      <el-button type="primary" :icon="Plus" :disabled="!canManage" @click="openCreateDialog">
        {{ labels.createLabel }}
      </el-button>
    </div>

    <p v-if="!canRead && labels.noAccessText" class="permission-tip">
      {{ labels.noAccessText }}
    </p>
    <p v-else-if="!canManage && labels.readonlyText" class="permission-tip">
      {{ labels.readonlyText }}
    </p>

    <el-table
      class="role-table"
      :data="canRead ? safeRoles : []"
      row-key="id"
      border
      :empty-text="canRead ? labels.emptyText : '无查看权限'"
    >
      <el-table-column prop="roleName" label="角色名" min-width="160" />
      <el-table-column prop="roleCode" label="编码" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag v-if="isBuiltin(row)" size="small" type="info">内置</el-tag>
          <el-tag v-else size="small" type="success" effect="plain">自定义</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限" min-width="360">
        <template #default="{ row }">
          <div class="permission-tags">
            <template v-if="normalizePermissionCodes(row).length">
              <el-tag
                v-for="code in visiblePermissionCodes(row)"
                :key="code"
                size="small"
                class="permission-tag"
              >
                {{ actions.formatPermission(code) }}
              </el-tag>
              <el-button
                v-if="normalizePermissionCodes(row).length > visiblePermissionLimit"
                link
                type="primary"
                size="small"
                class="expand-button"
                @click="toggleExpanded(row)"
              >
                {{
                  isExpanded(row)
                    ? '收起'
                    : `+${normalizePermissionCodes(row).length - visiblePermissionLimit} 更多`
                }}
              </el-button>
            </template>
            <span v-else class="empty-inline">未配置权限</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :icon="Edit"
            :disabled="!canManage"
            @click="openEditDialog(row)"
          >
            编辑
          </el-button>
          <el-button
            link
            type="danger"
            :icon="Delete"
            :disabled="!canManage || isBuiltin(row)"
            :loading="deletingRoleKey === roleKey(row)"
            @click="removeRole(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="720px" @closed="resetForm">
      <el-form class="role-form" :model="form" label-width="88px">
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" placeholder="请输入角色名称" :disabled="!canManage" />
        </el-form-item>
        <el-form-item label="角色编码">
          <el-alert
            v-if="!editingRoleId"
            type="info"
            show-icon
            :closable="false"
            title="角色编码将在保存时自动生成，无需手动填写。"
          />
          <el-input v-else v-model="form.roleCode" disabled />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="补充角色用途或适用范围"
            :disabled="!canManage"
          />
        </el-form-item>
        <el-form-item label="权限配置">
          <div class="permission-tree-box">
            <div class="tree-toolbar">
              <span>已选 {{ form.permissionCodes.length }} / {{ permissionCount }}</span>
              <div v-if="canManage" class="tree-actions">
                <el-button link type="primary" @click="checkAllPermissions">全选</el-button>
                <el-button link @click="clearPermissions">清空</el-button>
              </div>
            </div>
            <el-tree
              v-if="permissionTree.length"
              ref="permissionTreeRef"
              :data="permissionTree"
              show-checkbox
              node-key="key"
              default-expand-all
              :props="treeProps"
              :default-checked-keys="checkedNodeKeys"
              @check="handlePermissionCheck"
            />
            <el-empty v-else description="暂无可配置权限" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canManage" @click="submitRole">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { buildPermissionTree, permissionNodeKey } from '@/models/permission'

/**
 * 文案组 + 行为组。
 *
 * 07-P1-7：此前是 15 个并列 prop —— 其中 title / subtitle / roleTypeLabel / createLabel /
 * noAccessText / readonlyText / emptyText 七个全是「这个面板叫什么、提示什么」的同一类信息，
 * 调用点 `views/permission/index.vue` 的两处各写了十七行属性。聚合后调用点只留 3 行、
 * 文案预设提成常量（SYSTEM_ROLE_PANEL_LABELS / TEAM_ROLE_PANEL_LABELS）。
 *
 * `actions` 里的 `saveRole` / `deleteRole` 缺了整个面板就不可用 ⇒ 对象本身 required；
 * 另两个有默认实现（纯展示，不影响功能），缺省即「不判内置」「原样显示权限码」。
 */
const props = defineProps({
  labels: { type: Object, default: () => ({}) },
  roles: { type: Array, default: () => [] },
  permissions: { type: Array, default: () => [] },
  canRead: { type: Boolean, default: true },
  canManage: { type: Boolean, default: false },
  actions: { type: Object, required: true },
})

/**
 * 文案默认值。
 *
 * 它们原本是七个并列 String prop 各自的 `default`；07-P1-7 聚合成 `labels` 后保留同样的回落，
 * 所以调用方「只覆盖个别键」的行为与聚合前一致。
 *
 * ⚠️ 声明在 defineProps **之后**是必须的：`vue/define-macros-order` 要求 defineProps 紧跟 import；
 * 而 `labels` 的 getter 惰性求值（computed 首次读取才执行），放这里不会触发 TDZ。
 */
const DEFAULT_PANEL_LABELS = Object.freeze({
  title: '',
  subtitle: '集中查看角色授权范围，并在弹窗中调整权限。',
  roleTypeLabel: '角色',
  createLabel: '新增角色',
  noAccessText: '',
  readonlyText: '',
  emptyText: '暂无角色',
})

/** 文案 = 默认值 ← 调用方覆盖。 */
const labels = computed(() => ({ ...DEFAULT_PANEL_LABELS, ...props.labels }))

/** 行为 = 默认实现 ← 调用方覆盖。 */
const actions = computed(() => ({
  isBuiltinRole: () => false,
  formatPermission: (code) => code,
  ...props.actions,
}))
const visiblePermissionLimit = 4
const treeProps = Object.freeze({ children: 'children', label: 'label', disabled: 'disabled' })

const dialogVisible = ref(false)
const permissionTreeRef = ref(null)
const editingRoleId = ref(null)
const saving = ref(false)
const deletingRoleKey = ref(null)
const expandedRoleKeys = ref(new Set())
const form = reactive({
  roleName: '',
  roleCode: '',
  description: '',
  permissionCodes: [],
})

const safeRoles = computed(() => (Array.isArray(props.roles) ? props.roles : []))
const safePermissions = computed(() => (Array.isArray(props.permissions) ? props.permissions : []))
const permissionCount = computed(() => safePermissions.value.length)
const permissionTree = computed(() =>
  markTreeDisabled(buildPermissionTree(safePermissions.value), !props.canManage),
)
const checkedNodeKeys = computed(() => form.permissionCodes.map((code) => permissionNodeKey(code)))
const dialogTitle = computed(
  () => `${editingRoleId.value ? '编辑' : '新增'}${labels.value.roleTypeLabel}`,
)

watch(dialogVisible, (visible) => {
  if (visible) {
    syncTreeChecks()
  }
})

watch(safePermissions, () => {
  if (dialogVisible.value) {
    syncTreeChecks()
  }
})

function markTreeDisabled(nodes, disabled) {
  if (!disabled) {
    return nodes
  }
  return nodes.map((node) => ({
    ...node,
    disabled: true,
    children: Array.isArray(node.children) ? markTreeDisabled(node.children, disabled) : [],
  }))
}

function normalizePermissionCodes(row) {
  return Array.isArray(row?.permissionCodes) ? row.permissionCodes : []
}

function roleKey(row) {
  return String(row?.id ?? row?.roleCode ?? row?.roleName ?? '')
}

function isBuiltin(row) {
  return Boolean(actions.value.isBuiltinRole(row))
}

function isExpanded(row) {
  return expandedRoleKeys.value.has(roleKey(row))
}

function visiblePermissionCodes(row) {
  const codes = normalizePermissionCodes(row)
  return isExpanded(row) ? codes : codes.slice(0, visiblePermissionLimit)
}

function toggleExpanded(row) {
  const key = roleKey(row)
  const nextKeys = new Set(expandedRoleKeys.value)
  if (nextKeys.has(key)) {
    nextKeys.delete(key)
  } else {
    nextKeys.add(key)
  }
  expandedRoleKeys.value = nextKeys
}

function resetForm() {
  editingRoleId.value = null
  form.roleName = ''
  form.roleCode = ''
  form.description = ''
  form.permissionCodes = []
}

function openCreateDialog() {
  if (!props.canManage) {
    return
  }
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row) {
  if (!props.canManage) {
    return
  }
  editingRoleId.value = row.id
  form.roleName = row.roleName || ''
  form.roleCode = row.roleCode || ''
  form.description = row.description || ''
  form.permissionCodes = [...normalizePermissionCodes(row)]
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

function syncTreeChecks() {
  nextTick(() => {
    permissionTreeRef.value?.setCheckedKeys(checkedNodeKeys.value)
  })
}

function handlePermissionCheck(_, checkedState) {
  form.permissionCodes = checkedState.checkedNodes
    .filter((node) => node.isPermission)
    .map((node) => node.permissionCode)
}

function checkAllPermissions() {
  form.permissionCodes = safePermissions.value
    .map((item) => item.permissionCode || item.code)
    .filter(Boolean)
  syncTreeChecks()
}

function clearPermissions() {
  form.permissionCodes = []
  syncTreeChecks()
}

async function submitRole() {
  if (!props.canManage || saving.value) {
    return
  }
  if (!form.roleName.trim()) {
    ElMessage.warning('请输入角色名称')
    return
  }
  saving.value = true
  try {
    const saved = await actions.value.saveRole({
      roleId: editingRoleId.value,
      roleName: form.roleName.trim(),
      roleCode: form.roleCode,
      description: form.description.trim(),
      permissionCodes: [...form.permissionCodes],
    })
    if (saved !== false) {
      closeDialog()
    }
  } finally {
    saving.value = false
  }
}

async function removeRole(row) {
  if (!props.canManage || isBuiltin(row)) {
    return
  }
  const key = roleKey(row)
  deletingRoleKey.value = key
  try {
    await actions.value.deleteRole(row)
  } finally {
    deletingRoleKey.value = null
  }
}
</script>

<style scoped>
.role-management-panel {
  display: grid;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--zxyz-color-border);
  border-radius: 8px;
  background: var(--zxyz-color-bg-surface);
}

.role-panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.role-panel-header h3 {
  margin: 0;
  color: var(--zxyz-color-text-primary);
  font-size: 18px;
  font-weight: 700;
}

.role-panel-header p,
.permission-tip {
  margin: 6px 0 0;
  color: var(--zxyz-color-text-secondary);
  font-size: 13px;
}

.role-table {
  width: 100%;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 26px;
}

.permission-tag {
  max-width: 220px;
}

.expand-button {
  padding: 0 4px;
}

.empty-inline {
  color: var(--zxyz-color-text-tertiary);
  font-size: 13px;
}

.role-form {
  padding-top: 4px;
}

.permission-tree-box {
  width: 100%;
  max-height: 360px;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--zxyz-color-border);
  border-radius: 8px;
  background: #f9fafb;
}

.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--zxyz-color-text-secondary);
  font-size: 13px;
}

.tree-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.el-dialog__body) {
  padding-top: 12px;
}

:deep(.el-table__cell) {
  vertical-align: top;
}

@media (max-width: 760px) {
  .role-panel-header {
    display: grid;
  }

  .role-panel-header .el-button {
    width: 100%;
  }
}
</style>
