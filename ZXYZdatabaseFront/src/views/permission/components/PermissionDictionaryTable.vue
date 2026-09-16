<!--
  权限字典表。系统权限与团队权限此前是两个逐字重复的 `<el-table>` 块，
  合并为同一组件；`formatPermission` 作为 prop 注入，便于单测替换。
-->
<template>
  <PermissionPanel :title="title" :subtitle="subtitle" :tip="canRead ? '' : tip">
    <el-table
      :data="canRead ? permissions : []"
      height="260"
      :empty-text="canRead ? emptyText : '无查看权限'"
    >
      <el-table-column label="权限" min-width="220">
        <template #default="{ row }">{{ formatPermission(row) }}</template>
      </el-table-column>
      <el-table-column prop="permissionCode" label="编码" min-width="180" show-overflow-tooltip />
    </el-table>
  </PermissionPanel>
</template>

<script setup>
import PermissionPanel from './PermissionPanel.vue'

defineProps({
  /** 权限字典行数据；无权限时强制置空。 */
  permissions: { type: Array, default: () => [] },
  canRead: { type: Boolean, default: false },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  tip: { type: String, default: '' },
  emptyText: { type: String, default: '暂无数据' },
  /** 行渲染回调，签名 `(row) => string`。 */
  formatPermission: { type: Function, required: true },
})
</script>
