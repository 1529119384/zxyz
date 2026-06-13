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
      :visible="contextMenu.visible"
      :position="contextMenu.position"
      mode="recycle"
      :context-type="contextMenu.contextType"
      :selected-items="contextMenu.contextType === 'blank' ? [] : selectedRows"
      :target-item="contextMenu.targetItem"
      :can-write="canWrite"
      @action="handleContextAction"
      @close="closeContextMenu"
    />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

import FileContextMenu from '@/components/FileContextMenu.vue'
import { useDragSelection } from '@/composables/useDragSelection'
import { useFileContextMenu } from '@/composables/useFileContextMenu'
import { useFileExplorerHotkeys } from '@/composables/useFileExplorerHotkeys'
import { useRecycleBinList } from '@/composables/useRecycleBinList'
import { useSelectionManager } from '@/composables/useSelectionManager'
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
let closeContextMenu = () => {}

const currentTeamId = computed(() => props.teamId)
const currentSpaceType = computed(() => props.spaceType)
const currentProjectId = computed(() => props.projectId)

function isCheckboxClick(target) {
  return Boolean(target.closest('.el-checkbox'))
}

const selectionPointerState = ref({
  shiftKey: false,
  ctrlKey: false,
  metaKey: false,
  anchorId: null,
})

function getSelectionModifiers() {
  const currentState = selectionPointerState.value
  selectionPointerState.value = {
    shiftKey: false,
    ctrlKey: false,
    metaKey: false,
    anchorId: null,
  }
  return currentState
}

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

const {
  selectedIds,
  selectedRows,
  selectionAnchorId,
  setSelectedIds,
  clearSelection: clearSelectionState,
  selectAll,
  handleRowClick: handleSelectionRowClick,
  handleCheckboxSelect,
  handleCheckboxSelectAll,
  pruneSelection,
} = useSelectionManager({
  list: filteredList,
  filteredList,
  tableRef,
  isCheckboxClick,
  getSelectionModifiers,
  onSelectionChange: (payload) => emit('selection-change', payload),
  onBeforeSelect: () => closeContextMenu(),
})

const { dragState, selectionBoxStyle, getRowFromTarget, shouldSuppressRowClick } = useDragSelection(
  {
    dragContainerRef: filePageRef,
    tableWrapperRef,
    filteredList,
    isCheckboxClick,
    selectedIds,
    setSelectedIds,
    closeContextMenu: () => closeContextMenu(),
  },
)

const fileContextMenu = useFileContextMenu({
  containerRef: filePageRef,
  selectedRows,
  selectedIds,
  setSelectedIds,
  getRowFromTarget,
  shouldSuppressRowClick,
})
const contextMenu = fileContextMenu.contextMenu
const handlePageContextMenu = fileContextMenu.handlePageContextMenu
closeContextMenu = fileContextMenu.closeContextMenu

useFileExplorerHotkeys({
  selectAll,
  clearSelection: clearSelectionState,
  closeContextMenu: () => closeContextMenu(),
})

function handleRowClick(row, column, event) {
  if (shouldSuppressRowClick()) {
    return
  }

  handleSelectionRowClick(row, column, event)
}

function captureSelectionPointerState(event) {
  if (!isCheckboxClick(event.target)) {
    return
  }

  // 复选框组件事件拿不到原始鼠标修饰键，先在捕获阶段缓存下来供选择管理器消费。
  selectionPointerState.value = {
    shiftKey: event.shiftKey,
    ctrlKey: event.ctrlKey,
    metaKey: event.metaKey,
    anchorId: selectionAnchorId.value,
  }
}

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

watch(filteredList, async (rows) => {
  await pruneSelection(rows)
})

function handleContextAction(payload) {
  const targetItem = payload.targetItem || selectedRows.value[0] || null
  emit('context-action', {
    ...payload,
    selectedItems: selectedRows.value,
    targetItem,
    anchorId: selectionAnchorId.value,
    currentPath: '',
    targetPath: '',
  })
}

defineExpose({
  refresh,
  getCurrentList() {
    return [...list.value]
  },
  getSelectedRows() {
    return selectedRows.value
  },
  clearSelection() {
    clearSelectionState()
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
