<template>
  <div ref="filePageRef" class="file-page" @contextmenu="handlePageContextMenu">
    <div
      ref="tableWrapperRef"
      v-loading="loading"
      class="table-wrapper"
      @mousedown.capture="captureSelectionPointerState"
    >
      <el-table
        ref="tableRef"
        row-key="id"
        :data="filteredList"
        style="width: 100%"
        :empty-text="emptyText"
        @row-click="handleRowClick"
        @select="handleCheckboxSelect"
        @select-all="handleCheckboxSelectAll"
      >
        <el-table-column type="selection" width="55" />

        <el-table-column label="文件名" min-width="220">
          <template #default="{ row }">
            <div class="name-cell">
              <svg class="icon">
                <use :xlink:href="getFileIcon(row)" />
              </svg>
              <span class="file-name">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">
            {{ fmtTime(row.deleteTime) }}
          </template>
        </el-table-column>

        <el-table-column label="原始路径" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="store-path-cell">{{ row.storePath || '/' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            {{ formatSize(row.fileSize) }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div
      v-if="dragState.active || dragState.visible"
      class="selection-box"
      :style="selectionBoxStyle"
    />

    <FileContextMenu
      v-bind="contextMenuBindings"
      :visible="contextMenu.visible"
      :position="contextMenu.position"
      :context-type="contextMenu.contextType"
      :selected-items="contextMenu.contextType === 'blank' ? [] : selectedRows"
      :target-item="contextMenu.targetItem"
      @action="handleContextAction"
      @close="closeContextMenu"
    />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

import FileContextMenu from '@/components/FileContextMenu.vue'
import { useExplorerTableInteractions } from '@/composables/useExplorerTableInteractions'
import { useRecycleBinList } from '@/composables/useRecycleBinList'
import { getFileIcon } from '@/models/file'
import { isHandledByGlobalError } from '@/utils/error'
import { fmtTime, formatSize } from '@/utils/format'

const props = defineProps({
  canWrite: {
    type: Boolean,
    default: false,
  },
  teamId: {
    type: [Number, String],
    default: null,
  },
  spaceType: {
    type: [Number, String],
    default: null,
  },
  projectId: {
    type: [Number, String],
    default: null,
  },
})

const emit = defineEmits(['selection-change', 'context-action', 'row-action'])

const tableRef = ref(null)
const tableWrapperRef = ref(null)
const filePageRef = ref(null)

const currentTeamId = computed(() => props.teamId)
const currentSpaceType = computed(() => props.spaceType)
const currentProjectId = computed(() => props.projectId)

const recycleBinList = useRecycleBinList({
  teamId: currentTeamId,
  spaceType: currentSpaceType,
  projectId: currentProjectId,
})

const list = recycleBinList.list
const filteredList = list
const emptyText = recycleBinList.emptyText
const loading = recycleBinList.loading

async function refresh() {
  await recycleBinList.refresh()
}

// 复用表格通用交互（选择 / 框选 / 右键菜单 / 快捷键 / context-action），差异通过参数注入。
const {
  selectedRows,
  contextMenu,
  handlePageContextMenu,
  closeContextMenu,
  handleContextAction,
  dragState,
  selectionBoxStyle,
  handleRowClick,
  handleCheckboxSelect,
  handleCheckboxSelectAll,
  captureSelectionPointerState,
  clearSelection,
  debouncedPruneSelection,
  contextMenuBindings,
} = useExplorerTableInteractions({
  tableRef,
  tableWrapperRef,
  filePageRef,
  filteredList,
  isRecycleBin: true,
  emit,
  getContextMenuProps: () => ({
    canWrite: props.canWrite,
  }),
  buildContextExtra: () => ({
    currentPath: '',
    targetPath: '',
  }),
})

async function runRefreshSafely() {
  try {
    await refresh()
  } catch (error) {
    // 登录失效等已由全局请求层处理的异常，不再向组件外继续抛出，避免出现未捕获 Promise 报错。
    if (isHandledByGlobalError(error)) {
      return
    }

    throw error
  }
}

watch(
  [() => props.teamId, () => props.spaceType, () => props.projectId],
  async () => {
    await runRefreshSafely()
  },
  { immediate: true },
)

watch(filteredList, (rows) => {
  debouncedPruneSelection(rows)
})

defineExpose({
  refresh,
  getCurrentList() {
    return [...list.value]
  },
  getSelectedRows() {
    return selectedRows.value
  },
  clearSelection() {
    clearSelection()
  },
})
</script>

<style scoped>
.file-page {
  position: relative;
  height: 100%;
  padding: 20px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  user-select: none;
  -webkit-user-select: none;
}

.file-page:focus,
.file-page:focus-visible {
  outline: none;
}

.table-wrapper {
  position: relative;
  flex: 1;
  min-height: 0;
}

.name-cell {
  display: flex;
  align-items: center;
}

.file-name {
  margin-left: 8px;
}

.store-path-cell {
  font-size: 13px;
  color: #909399;
  word-break: break-all;
}

.icon {
  width: 20px;
  height: 20px;
  fill: currentColor;
}

.selection-box {
  position: absolute;
  z-index: 20;
  border: 1px solid rgba(64, 158, 255, 0.8);
  background: rgba(64, 158, 255, 0.16);
  pointer-events: none;
}

.file-page :deep(.el-table),
.file-page :deep(.el-table__body-wrapper),
.file-page :deep(.el-table__row),
.file-page :deep(.el-table__cell),
.file-page :deep(.cell) {
  user-select: none;
  -webkit-user-select: none;
}
</style>
