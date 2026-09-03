import { computed, ref } from 'vue'

import { useDragSelection } from '@/composables/useDragSelection'
import { useFileContextMenu } from '@/composables/useFileContextMenu'
import { useFileExplorerHotkeys } from '@/composables/useFileExplorerHotkeys'
import { useSelectionManager } from '@/composables/useSelectionManager'

/**
 * 文件 / 回收站 Explorer 表格通用交互组合函数。
 *
 * 收敛两个 Explorer 组件逐行重复的右键菜单与选择逻辑：
 * 选择态（selectedRows / selectionAnchorId）、复选框修饰键缓存、
 * 拖拽框选（useDragSelection）、右键菜单（useFileContextMenu）、
 * 快捷键（useFileExplorerHotkeys）、以及 context-action 的 payload 构造。
 *
 * 组件只需通过参数注入差异项（列表数据源、菜单属性、额外 payload 字段），
 * 即可复用全部交互行为，并保持父组件 emit 契约（selection-change / context-action）不变。
 *
 * @param {Object} options - 配置项
 * @param {import('vue').Ref<HTMLElement|null>} options.tableRef - 表格组件 DOM 引用
 * @param {import('vue').Ref<HTMLElement|null>} options.tableWrapperRef - 表格包裹 DOM 引用
 * @param {import('vue').Ref<HTMLElement|null>} options.filePageRef - 页面根 DOM 引用（框选容器）
 * @param {import('vue').Ref<Array>} options.filteredList - 当前过滤后的列表数据（ref / computed）
 * @param {boolean} options.isRecycleBin - 是否回收站场景（决定菜单 mode 与路径语义）
 * @param {Function} options.emit - 组件 emit 函数，用于派发 selection-change / context-action
 * @param {() => Object} [options.getContextMenuProps] - 返回传给 FileContextMenu 的额外属性
 *        （如 canWrite / virtualDirectory / canManageProjects），默认返回 {}
 * @param {(ctx: { targetItem: Object|null, selectedRows: Array, selectionAnchorId: string|number|null }) => Object} [options.buildContextExtra]
 *        - 构造 context-action 时附加的额外字段（如 currentPath / targetPath），默认返回 {}
 * @returns 见下方解构成员注释
 */
export function useExplorerTableInteractions({
  tableRef,
  tableWrapperRef,
  filePageRef,
  filteredList,
  isRecycleBin,
  emit,
  getContextMenuProps = () => ({}),
  buildContextExtra = () => ({}),
}) {
  // 判断点击目标是否为复选框（两个组件实现完全一致）。
  function isCheckboxClick(target) {
    return Boolean(target.closest('.el-checkbox'))
  }

  // 复选框组件事件拿不到原始鼠标修饰键，先在捕获阶段缓存下来，供选择管理器消费。
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

  // 先声明占位 closeContextMenu，避免下方组合函数闭包在初始化阶段引用到未定义变量；
  // 待 useFileContextMenu 返回后再回写为真正的关闭实现，保证选择管理器 / 框选在调用时拿到最新实现。
  let closeContextMenu = () => {}

  // 选择管理器：单选 / 多选 / Shift 范围选 / Ctrl 切换选，并对外派发 selection-change。
  const {
    selectedIds,
    selectedRows,
    selectionAnchorId,
    setSelectedIds,
    clearSelection,
    selectAll,
    handleRowClick: handleSelectionRowClick,
    handleCheckboxSelect,
    handleCheckboxSelectAll,
    pruneSelection,
    debouncedPruneSelection,
  } = useSelectionManager({
    list: filteredList,
    filteredList,
    tableRef,
    isCheckboxClick,
    getSelectionModifiers,
    onSelectionChange: (payload) => emit('selection-change', payload),
    onBeforeSelect: () => closeContextMenu(),
  })

  // 框选：拖拽选择 + 选择框样式 + 行解析。
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

  // 右键菜单状态机（contextMenu / handlePageContextMenu / closeContextMenu）。
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

  // 快捷键：Ctrl+A 全选、Escape 取消选择与关闭菜单。
  useFileExplorerHotkeys({
    selectAll,
    clearSelection,
    closeContextMenu: () => closeContextMenu(),
  })

  // 行点击：拖拽抑制状态下短路，否则交由选择管理器处理。
  function handleRowClick(row, column, event) {
    if (shouldSuppressRowClick()) {
      return
    }

    handleSelectionRowClick(row, column, event)
  }

  // 捕获阶段缓存复选框点击的修饰键，供选择管理器消费。
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

  // 右键菜单动作的统一种子 payload：selectedItems / targetItem / anchorId + 组件注入的额外字段。
  function handleContextAction(payload) {
    const targetItem = payload.targetItem || selectedRows.value[0] || null
    emit('context-action', {
      ...payload,
      selectedItems: selectedRows.value,
      targetItem,
      anchorId: selectionAnchorId.value,
      ...buildContextExtra({
        targetItem,
        selectedRows: selectedRows.value,
        selectionAnchorId: selectionAnchorId.value,
      }),
    })
  }

  // 传给 FileContextMenu 的属性（mode 由 isRecycleBin 推导，其余由组件注入）。
  const contextMenuBindings = computed(() => ({
    mode: isRecycleBin ? 'recycle' : 'space',
    ...getContextMenuProps(),
  }))

  return {
    selectedRows, // 当前选中行数据（Ref<Array>），模板绑定 :selected-items 用
    selectionAnchorId, // 选择锚点 id（Ref），用于 payload.anchorId
    contextMenu, // 右键菜单状态（Ref<{ visible, position, contextType, targetItem }>）
    handlePageContextMenu, // 页面根 @contextmenu 处理函数
    closeContextMenu, // 关闭菜单回调
    handleContextAction, // FileContextMenu @action 处理函数
    dragState, // 框选状态（Ref）
    selectionBoxStyle, // 选择框样式（ComputedRef）
    getRowFromTarget, // 从 DOM 解析行数据
    shouldSuppressRowClick, // 是否抑制行点击
    handleRowClick, // 表格 @row-click
    handleCheckboxSelect, // 表格 @select
    handleCheckboxSelectAll, // 表格 @select-all
    captureSelectionPointerState, // 表格包裹 @mousedown.capture
    selectAll, // 全选（供快捷键 / 父组件）
    clearSelection, // 清除选择（defineExpose 复用）
    pruneSelection, // 列表变化时的选区裁剪（即时，供测试/显式调用）
    debouncedPruneSelection, // 列表变化时的选区裁剪（防抖，供组件 watch 调用，统一时序）
    contextMenuBindings, // v-bind 到 FileContextMenu 的额外属性（mode / canWrite / ...）
  }
}
