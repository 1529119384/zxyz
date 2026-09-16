<!--
  权限审计表。系统审计与团队审计此前是两个逐字重复的 `<el-table>` 块
  （仅标题文案/空态文案/数据源不同），合并为同一组件。
-->
<template>
  <PermissionPanel :title="title" :subtitle="subtitle" :tip="canRead ? '' : tip" variant="wide">
    <el-table
      :data="canRead ? rows : []"
      height="240"
      :empty-text="canRead ? emptyText : '无查看权限'"
    >
      <el-table-column prop="operationType" label="操作" min-width="160" />
      <el-table-column prop="targetType" label="目标" min-width="120" />
      <el-table-column prop="afterValue" label="变更后" min-width="220" show-overflow-tooltip />
      <el-table-column prop="operationTime" label="时间" min-width="180" />
    </el-table>
  </PermissionPanel>
</template>

<script setup>
import PermissionPanel from './PermissionPanel.vue'

defineProps({
  /** 审计行数据；无权限时父组件传什么都无所谓，本组件会强制置空。 */
  rows: { type: Array, default: () => [] },
  /** 是否有审计查看权限。为 false 时表格数据强制清空并改空态文案。 */
  canRead: { type: Boolean, default: false },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  /** 无权限时显示的提示文案。 */
  tip: { type: String, default: '' },
  /** 有权限但无数据时的空态文案。 */
  emptyText: { type: String, default: '暂无数据' },
})
</script>
