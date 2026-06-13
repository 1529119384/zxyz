import { ref } from 'vue'

export function isEditableTarget(target) {
  if (!(target instanceof HTMLElement)) {
    return false
  }

  return Boolean(
    target.closest('input, textarea, [contenteditable="true"], .el-input, .el-textarea'),
  )
}

/**
 * @typedef {Object} UseFileContextMenuOptions
 * @property {import('vue').Ref<HTMLElement|null>} containerRef - 文件区域容器模板引用。
 * @property {import('vue').Ref<Array<Object>>} selectedRows - 当前选中的行数据列表。
 * @property {import('vue').Ref<Array<string|number>>} selectedIds - 当前选中的行 ID 列表。
 * @property {Function} setSelectedIds - 设置选中 ID 列表，接收 (ids, anchorId)。
 * @property {Function} getRowFromTarget - 从 DOM 目标元素解析所属行数据。
 * @property {Function} shouldSuppressRowClick - 判断是否应抑制行点击事件（如拖选中）。
 */

/**
 * 文件区域右键菜单管理，处理菜单打开/关闭、点击目标解析和选区同步。
 *
 * @param {UseFileContextMenuOptions} options - 配置项。
 * @returns {{ contextMenu: import('vue').Ref<Object>, isEditableTarget: Function, openContextMenu: Function, closeContextMenu: Function, handlePageContextMenu: Function, syncSelectionForContextMenu: Function }} 右键菜单状态与操作方法。
 */
export function useFileContextMenu(options) {
  const {
    containerRef,
    selectedRows,
    selectedIds,
    setSelectedIds,
    getRowFromTarget,
    shouldSuppressRowClick,
  } = options

  const contextMenu = ref({
    visible: false,
    position: { x: 0, y: 0 },
    contextType: 'blank',
    targetItem: null,
  })

  function resolveContextType(targetItem) {
    if (selectedRows.value.length > 1) {
      return 'multi'
    }
    if (!selectedRows.value.length && !targetItem) {
      return 'blank'
    }

    const row = targetItem || selectedRows.value[0]
    if (!row) {
      return 'blank'
    }
    return row.type === 0 ? 'folder' : 'file'
  }

  function openContextMenu(event, targetItem = null) {
    contextMenu.value = {
      visible: true,
      position: { x: event.clientX, y: event.clientY },
      contextType: resolveContextType(targetItem),
      targetItem,
    }
  }

  function closeContextMenu() {
    contextMenu.value.visible = false
  }

  function syncSelectionForContextMenu(row) {
    if (!row) {
      return
    }

    if (selectedIds.value.includes(row.id)) {
      return
    }

    setSelectedIds([row.id], row.id)
  }

  function getContextMenuRow(target) {
    if (!(target instanceof HTMLElement)) {
      return null
    }

    return getRowFromTarget(target)
  }

  function handlePageContextMenu(event) {
    if (shouldSuppressRowClick()) {
      event.preventDefault()
      event.stopPropagation()
      return
    }

    const row = getContextMenuRow(event.target)
    if (row) {
      event.preventDefault()
      event.stopPropagation()
      syncSelectionForContextMenu(row)
      openContextMenu(event, row)
      return
    }

    if (!isEditableTarget(event.target) && containerRef.value?.contains(event.target)) {
      event.preventDefault()
      event.stopPropagation()
      // 文件区空白右键只由浏览器组件接管，避免页面或 Layout 再次转发同一事件。
      openContextMenu(event, null)
    }
  }

  return {
    contextMenu,
    isEditableTarget,
    openContextMenu,
    closeContextMenu,
    handlePageContextMenu,
    syncSelectionForContextMenu,
  }
}
