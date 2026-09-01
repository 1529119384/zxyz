<template>
  <div ref="filePageRef" class="file-page" @contextmenu="handlePageContextMenu">
    <el-breadcrumb v-if="!isSearchMode" :separator-icon="ArrowRight" class="breadcrumb">
      <el-breadcrumb-item :to="routeTarget('')"> 首页 </el-breadcrumb-item>
      <el-breadcrumb-item
        v-for="(name, idx) in crumbArr"
        :key="idx"
        :to="routeTarget(crumbPath(idx))"
      >
        {{ name }}
      </el-breadcrumb-item>
    </el-breadcrumb>

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
        @row-dblclick="handleRowDblClick"
        @select="handleCheckboxSelect"
        @select-all="handleCheckboxSelectAll"
      >
        <el-table-column type="selection" width="55" />

        <el-table-column min-width="220">
          <template #header>
            <button
              :class="['sort-header', { 'sort-header--active': isColumnSorted('fileName') }]"
              :aria-sort="getAriaSort('fileName')"
              type="button"
              @click="toggleSort('fileName')"
            >
              <span>{{ getSortLabel('fileName') }}</span>
              <span v-if="!isSearchMode" class="sort-header__icon">{{
                getSortIndicator('fileName')
              }}</span>
            </button>
          </template>
          <template #default="{ row }">
            <div class="name-cell">
              <svg class="icon">
                <use :xlink:href="getFileIcon(row)" />
              </svg>
              <span class="file-name">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column width="180">
          <template #header>
            <button
              :class="['sort-header', { 'sort-header--active': isColumnSorted('modifyTime') }]"
              :aria-sort="getAriaSort('modifyTime')"
              type="button"
              @click="toggleSort('modifyTime')"
            >
              <span>{{ getSortLabel('modifyTime') }}</span>
              <span v-if="!isSearchMode" class="sort-header__icon">{{
                getSortIndicator('modifyTime')
              }}</span>
            </button>
          </template>
          <template #default="{ row }">
            {{ fmtTime(row.modifyTime) }}
          </template>
        </el-table-column>

        <el-table-column width="120">
          <template #header>
            <button
              :class="['sort-header', { 'sort-header--active': isColumnSorted('fileSize') }]"
              :aria-sort="getAriaSort('fileSize')"
              type="button"
              @click="toggleSort('fileSize')"
            >
              <span>{{ getSortLabel('fileSize') }}</span>
              <span v-if="!isSearchMode" class="sort-header__icon">{{
                getSortIndicator('fileSize')
              }}</span>
            </button>
          </template>
          <template #default="{ row }">
            {{ formatSize(row.fileSize) }}
          </template>
        </el-table-column>
      </el-table>

      <div v-if="isSearchMode" class="search-result-info">共找到 {{ searchTotal }} 个结果</div>

      <el-pagination
        v-if="isSearchMode"
        v-model:current-page="fileSearch.page"
        v-model:page-size="fileSearch.pageSize"
        :page-sizes="[20, 50, 100, 200]"
        :total="searchTotal"
        layout="total, sizes, prev, pager, next"
        class="pagination-bar"
        @current-change="fileSearch.refresh"
        @size-change="fileSearch.refresh"
      />
      <el-pagination
        v-else
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[20, 50, 100, 200]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        class="pagination-bar"
        @current-change="spaceFileList.refresh"
        @size-change="spaceFileList.refresh"
      />
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
import { ArrowRight } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import FileContextMenu from '@/components/FileContextMenu.vue'
import { useProvidedSpaceContext } from '@/composables/useCurrentSpaceContext'
import { useExplorerTableInteractions } from '@/composables/useExplorerTableInteractions'
import { useFileNavigation } from '@/composables/useFileNavigation'
import { useFileSearch } from '@/composables/useFileSearch'
import { getFileIcon } from '@/models/file'
import { useSpaceFileList } from '@/composables/useSpaceFileList'
import { useSortState } from '@/composables/useSortState'
import { useCurrentIdStore } from '@/store/currentId'
import { isHandledByGlobalError } from '@/utils/error'
import { fmtTime, formatSize } from '@/utils/format'
import { isProjectFolderEntry, isProjectRootId } from '@/utils/projectVirtualFolder'

const props = defineProps({
  searchText: {
    type: String,
    default: '',
  },
  canWrite: {
    type: [Boolean, null],
    default: null,
  },
  canManageProjects: {
    type: [Boolean, null],
    default: null,
  },
})

const emit = defineEmits(['selection-change', 'context-action', 'open-folder', 'row-action'])

const isSpaceExplorer = computed(() => true)
const route = useRoute()
const currentIdStore = useCurrentIdStore()
const spaceContext = useProvidedSpaceContext()
const explorerCanWrite = computed(() => props.canWrite ?? spaceContext.canWriteInExplorer.value)
const explorerCanManageProjects = computed(
  () => props.canManageProjects ?? spaceContext.canManageProjects.value,
)
const isProjectVirtualDirectory = computed(() => isProjectRootId(currentIdStore.currentId))
const virtualDirectoryType = computed(() => (isProjectVirtualDirectory.value ? 'projectRoot' : ''))

const tableRef = ref(null)
const tableWrapperRef = ref(null)
const filePageRef = ref(null)
const { sortState, getSortLabel, isColumnSorted, getSortIndicator, getAriaSort, toggleSort } =
  useSortState({
    canSort: () => !isSearchMode.value,
  })
const currentParentId = computed(() => currentIdStore.currentId)

const spaceFileList = useSpaceFileList({
  currentId: currentParentId,
  sortState,
  spaceContext,
})

const { currentPage, pageSize, total, resetPage } = spaceFileList

const fileSearch = useFileSearch({
  searchText: computed(() => props.searchText),
  enabled: isSpaceExplorer,
  spaceContext,
})

const list = spaceFileList.list
const isSearchMode = fileSearch.isSearchMode
const searchTotal = fileSearch.total
const filteredList = computed(() => (isSearchMode.value ? fileSearch.list.value : list.value))
const emptyText = computed(() => {
  if (isSearchMode.value) {
    return '暂无搜索结果'
  }

  return '暂无文件'
})
const loading = computed(() => {
  if (isSearchMode.value) {
    return fileSearch.loading.value
  }

  return spaceFileList.loading.value
})

async function refresh(options = {}) {
  await spaceFileList.refresh(options)
}

async function forceRefresh() {
  await spaceFileList.refresh({ force: true })
}

const {
  currentPath,
  crumbArr,
  crumbPath: buildCrumbPath,
  buildFolderPath,
  enterFolder,
  navigationReady,
  latestResolvedNavigation,
} = useFileNavigation({
  isSpaceMode: isSpaceExplorer,
  sortState,
  spaceContext,
  onOpenFolder: (payload) => emit('open-folder', payload),
})

// 复用表格通用交互（选择 / 框选 / 右键菜单 / 快捷键 / context-action），差异通过参数注入。
const {
  selectedRows,
  contextMenu,
  handlePageContextMenu,
  closeContextMenu,
  handleContextAction,
  dragState,
  selectionBoxStyle,
  shouldSuppressRowClick,
  handleRowClick,
  handleCheckboxSelect,
  handleCheckboxSelectAll,
  captureSelectionPointerState,
  clearSelection,
  pruneSelection,
  contextMenuBindings,
} = useExplorerTableInteractions({
  tableRef,
  tableWrapperRef,
  filePageRef,
  filteredList,
  isRecycleBin: false,
  emit,
  getContextMenuProps: () => ({
    canWrite: explorerCanWrite.value,
    virtualDirectory: virtualDirectoryType.value,
    canManageProjects: explorerCanManageProjects.value,
  }),
  buildContextExtra: ({ targetItem }) => ({
    currentPath: currentPath.value,
    targetPath: targetItem?.type === 0 ? buildFolderPath(targetItem) : currentPath.value,
  }),
})

function crumbPath(idx) {
  return buildCrumbPath(idx)
}

function routeTarget(path) {
  const query = { ...route.query }
  if (path) {
    query.path = path
  } else {
    delete query.path
  }

  if (route.name === 'teamSpace') {
    return { name: 'teamSpace', query }
  }
  if (route.name === 'projectSpace') {
    return { name: 'projectSpace', params: route.params, query }
  }
  return { name: 'index', query }
}

async function runRefreshSafely(options) {
  try {
    await refresh(options)
  } catch (error) {
    // 登录失效等已由全局请求层处理的异常，不再向组件外继续抛出，避免出现未捕获 Promise 报错。
    if (isHandledByGlobalError(error)) {
      return
    }

    throw error
  }
}

let stopped = false
let pruneSelectionTimer = null

watch(currentParentId, () => {
  resetPage()
})

watch(
  [currentPath, navigationReady, spaceContext.requestParams],
  async ([_path, ready]) => {
    if (stopped || !ready) {
      return
    }

    await runRefreshSafely({
      prefetchedList: latestResolvedNavigation.value?.prefetchedList ?? null,
    })
  },
  { immediate: true },
)

watch(
  sortState,
  async () => {
    if (stopped) return
    await runRefreshSafely()
  },
  { deep: true },
)
watch(filteredList, (rows) => {
  if (stopped) return
  if (pruneSelectionTimer) {
    clearTimeout(pruneSelectionTimer)
  }
  pruneSelectionTimer = setTimeout(() => {
    pruneSelectionTimer = null
    if (!stopped) {
      pruneSelection(rows)
    }
  }, 150)
})

onBeforeUnmount(() => {
  stopped = true
  if (pruneSelectionTimer) {
    clearTimeout(pruneSelectionTimer)
    pruneSelectionTimer = null
  }
})

function handleRowDblClick(row) {
  if (shouldSuppressRowClick()) {
    return
  }

  if (isProjectFolderEntry(row)) {
    emit('row-action', { action: 'openProject', row })
    return
  }

  if (isSearchMode.value || row.type !== 0) {
    return
  }
  enterFolder(row)
}

defineExpose({
  refresh,
  forceRefresh,
  openFolder: enterFolder,
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

.breadcrumb {
  margin-bottom: 12px;
  user-select: none;
}

.table-wrapper {
  position: relative;
  flex: 1;
  min-height: 0;
}

.search-result-info {
  padding: 12px 0 0;
  color: #606266;
  font-size: 13px;
  user-select: text;
  -webkit-user-select: text;
}

.pagination-bar {
  padding: 12px 0 0;
  display: flex;
  justify-content: flex-end;
}

.sort-header {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.sort-header--active {
  color: #409eff;
}

.sort-header__icon {
  font-size: 12px;
  line-height: 1;
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
.file-page :deep(.cell),
.file-page :deep(.el-breadcrumb),
.file-page :deep(.el-breadcrumb__item),
.file-page :deep(.el-breadcrumb__inner) {
  user-select: none;
  -webkit-user-select: none;
}
</style>
