<!-- 权限管理页：系统权限 + 团队权限。
     页面本身只负责「页头 + 页签 + 装配」：
     - 数据与权限判定 → usePermissionCenterData()
     - 权限字典表 / 审计表 / 面板壳 → components/ 下的三个共用组件
     - 角色 CRUD → useSystemPermissionActions / useTeamPermissionActions -->
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
              :labels="SYSTEM_ROLE_PANEL_LABELS"
              :actions="systemRolePanelActions"
              :roles="safeSystemRoles"
              :permissions="safeSystemPermissions"
              :can-read="canReadSystemPermissionCenter"
              :can-manage="canManageSystemRoles"
            />

            <div class="auxiliary-grid">
              <PermissionPanel
                variant="assignment"
                title="系统角色任命"
                subtitle="为指定用户分配系统角色，不能给当前登录账号赋权。"
                :tip="canManageSystemRoles ? '' : '缺少 `system:role:manage` 权限，当前表单只读。'"
              >
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
                    @click="systemActions.submitUserRoleAssign(userRoleForm)"
                    >保存</el-button
                  >
                </div>
              </PermissionPanel>

              <PermissionDictionaryTable
                title="系统权限字典"
                subtitle="权限编码由后端维护，角色编辑时按编码前缀分组选择。"
                tip="你没有系统权限中心查看权限，当前分区不可用。"
                empty-text="暂无系统权限"
                :permissions="safeSystemPermissions"
                :can-read="canReadSystemPermissionCenter"
                :format-permission="formatPermissionLabel"
              />

              <PermissionAuditTable
                title="系统审计"
                subtitle="记录系统权限中心的角色和授权变更。"
                tip="你没有系统审计查看权限，当前分区不可用。"
                empty-text="暂无系统审计"
                :rows="safeSystemAudit"
                :can-read="canReadSystemAudit"
              />
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
              :labels="TEAM_ROLE_PANEL_LABELS"
              :actions="teamRolePanelActions"
              :roles="safeTeamRoles"
              :permissions="safeTeamPermissions"
              :can-read="canReadTeamPermissionCenter"
              :can-manage="canManageTeamRoles"
            />

            <div class="auxiliary-grid">
              <PermissionPanel
                variant="assignment"
                title="团队角色任命"
                subtitle="给当前团队成员分配团队角色，不能调整当前登录账号。"
                :tip="
                  !canReadTeamPermissionCenter
                    ? '你没有团队权限中心查看权限，当前分区不可用。'
                    : !canAssignTeamMemberRole
                      ? '缺少 `team:member:assign-role` 权限，当前表单只读。'
                      : ''
                "
              >
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
                    @click="teamActions.submitMemberRoleAssign(memberRoleForm)"
                    >保存</el-button
                  >
                </div>
              </PermissionPanel>

              <PermissionDictionaryTable
                title="团队权限字典"
                subtitle="当前团队可用权限，角色编辑时按编码前缀分组选择。"
                tip="你没有团队权限中心查看权限，当前分区不可用。"
                empty-text="暂无团队权限"
                :permissions="safeTeamPermissions"
                :can-read="canReadTeamPermissionCenter"
                :format-permission="formatPermissionLabel"
              />

              <PermissionAuditTable
                title="团队审计"
                subtitle="记录当前团队角色、授权和成员角色变更。"
                tip="你没有团队审计查看权限，当前分区不可用。"
                empty-text="暂无团队审计"
                :rows="safeTeamPermissionAudit"
                :can-read="canReadTeamAudit"
              />
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import RoleManagementPanel from '@/components/RoleManagementPanel.vue'

import PermissionAuditTable from './components/PermissionAuditTable.vue'
import PermissionDictionaryTable from './components/PermissionDictionaryTable.vue'
import PermissionPanel from './components/PermissionPanel.vue'
import { usePermissionCenterData } from './usePermissionCenterData'

const {
  // 页头与页签
  activeTab,
  readonlyModeText,
  refreshAll,
  // 系统区
  showSystemPermissionTab,
  safeSystemPermissions,
  safeSystemRoles,
  safeSystemAudit,
  canReadSystemPermissionCenter,
  canManageSystemRoles,
  canReadSystemAudit,
  isBuiltinSystemRole,
  formatPermissionLabel,
  systemActions,
  userRoleForm,
  systemUserSearching,
  filteredSystemUserOptions,
  searchSystemUsers,
  formatUserOption,
  resolveUserId,
  // 团队区
  selectedTeamId,
  showTeamPermissionTab,
  safeTeamPermissions,
  safeTeamRoles,
  safeTeamPermissionAudit,
  canReadTeamPermissionCenter,
  canManageTeamRoles,
  canAssignTeamMemberRole,
  canReadTeamAudit,
  isBuiltinTeamRole,
  teamActions,
  assignableTeamMembers,
  memberRoleForm,
  displayTeamMemberName,
} = usePermissionCenterData()

// 07-P1-7：RoleManagementPanel 把「文案」与「行为」各聚合成一个对象，两个实例只在文案与
// 回调来源上不同，于是提成模块内常量 —— 调用点从十七行属性收到三行。
const SYSTEM_ROLE_PANEL_LABELS = Object.freeze({
  title: '系统角色',
  subtitle: '管理系统级角色的授权范围，内置角色不可删除。',
  roleTypeLabel: '系统角色',
  createLabel: '新增角色',
  noAccessText: '你没有系统权限中心查看权限，当前分区不可用。',
  readonlyText: '当前为只读模式，系统角色不可编辑。',
  emptyText: '暂无系统角色',
})

const TEAM_ROLE_PANEL_LABELS = Object.freeze({
  title: '团队角色',
  subtitle: '管理当前团队内的角色权限，内置角色不可删除。',
  roleTypeLabel: '团队角色',
  createLabel: '新增团队角色',
  noAccessText: '你没有团队权限中心查看权限，当前分区不可用。',
  readonlyText: '当前为只读模式，团队角色不可编辑。',
  emptyText: '暂无团队角色',
})

// 必须用 computed 而不是对象字面量：`systemActions` / `isBuiltinSystemRole` 等都是
// usePermissionCenterData 里稳定的引用，computed 因此只求值一次、对象引用也就稳定；
// 写成 `:actions="{ ... }"` 会每轮渲染都造新对象，让面板里的 labels/actions computed 反复失效。
const systemRolePanelActions = computed(() => ({
  isBuiltinRole: isBuiltinSystemRole,
  formatPermission: formatPermissionLabel,
  saveRole: systemActions.saveSystemRole,
  deleteRole: systemActions.deleteSystemRole,
}))

const teamRolePanelActions = computed(() => ({
  isBuiltinRole: isBuiltinTeamRole,
  formatPermission: formatPermissionLabel,
  saveRole: teamActions.saveTeamRole,
  deleteRole: teamActions.deleteTeamRole,
}))
</script>

<style scoped>
.permission-page,
.permission-stack,
.permission-stack__content,
.auxiliary-grid {
  display: grid;
  gap: var(--zxyz-space-5);
}

.permission-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--zxyz-space-5);
}

.permission-header h1 {
  margin: 0;
  color: var(--zxyz-color-text-primary);
  font-size: 26px;
  font-weight: 700;
}

.permission-header p {
  margin: var(--zxyz-space-2) 0 0;
  color: var(--zxyz-color-text-secondary);
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

.inline-form {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(180px, 280px) auto;
  gap: var(--zxyz-space-4);
  align-items: center;
}

.inline-form :deep(.el-select) {
  width: 100%;
}

.team-empty {
  padding: var(--zxyz-space-8) 0;
  border: 1px dashed var(--zxyz-color-border-strong);
  border-radius: var(--zxyz-radius-md);
  background: var(--zxyz-color-bg-surface);
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
