import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const SELECTION_SYNC_DEBOUNCE_MS = 100

/**
 * @typedef {Object} UseSelectionManagerOptions
 * @property {import('vue').Ref<Array<{id: string|number}>>} list - 完整数据列表。
 * @property {import('vue').Ref<Array<{id: string|number}>>} filteredList - 当前过滤后的数据列表。
 * @property {import('vue').Ref<HTMLElement|null>} tableRef - Element Plus 表格组件模板引用。
 * @property {Function} isCheckboxClick - 判断点击事件是否来自复选框，接收 event.target。
 * @property {Function} [getSelectionModifiers] - 返回当前键盘修饰键状态（shiftKey、ctrlKey、metaKey、anchorId）。
 * @property {Function} [onSelectionChange] - 选中项变化时的回调，接收 { rows, anchorId }。
 * @property {Function} [onBeforeSelect] - 选择操作前的回调。
 */

/**
 * 文件列表选择管理器，支持单选、多选、Shift 范围选、Ctrl/⌘ 切换选。
 *
 * @param {UseSelectionManagerOptions} options - 配置项。
 * @returns {{ selectedIds: import('vue').Ref<Array<string|number>>, selectedRows: import('vue').ComputedRef<Array>, selectionAnchorId: import('vue').Ref<string|number|null>, isSyncingSelection: import('vue').Ref<boolean>, setSelectedIds: Function, clearSelection: Function, selectAll: Function, handleRowClick: Function, handleCheckboxSelect: Function, handleCheckboxSelectAll: Function, pruneSelection: Function }} 选择管理状态与操作方法。
 */
export function useSelectionManager(options) {
  const {
    list,
    filteredList,
    tableRef,
    isCheckboxClick,
    getSelectionModifiers,
    onSelectionChange,
    onBeforeSelect,
  } = options

  const selectedIds = ref([])
  const selectionAnchorId = ref(null)
  const isSyncingSelection = ref(false)

  const selectedRows = computed(() => {
    const selectedIdSet = new Set(selectedIds.value)
    return list.value.filter((item) => selectedIdSet.has(item.id))
  })

  function emitSelectionChange() {
    onSelectionChange?.({
      rows: selectedRows.value,
      anchorId: selectionAnchorId.value,
    })
  }

  function syncTableSelection() {
    if (!tableRef.value) {
      return
    }

    isSyncingSelection.value = true
    tableRef.value.clearSelection()

    const currentSelectedIdSet = new Set(selectedIds.value)
    filteredList.value.forEach((row) => {
      if (currentSelectedIdSet.has(row.id)) {
        tableRef.value.toggleRowSelection(row, true)
      }
    })

    requestAnimationFrame(() => {
      isSyncingSelection.value = false
    })
  }

  function setSelectedIds(ids, anchorId = null) {
    const validIds = [...new Set(ids)]
    selectedIds.value = validIds
    selectionAnchorId.value = anchorId ?? validIds[validIds.length - 1] ?? null
    emitSelectionChange()
    syncTableSelection()
  }

  function clearSelection() {
    setSelectedIds([], null)
  }

  function selectAll() {
    const ids = filteredList.value.map((item) => item.id)
    setSelectedIds(ids, ids[ids.length - 1] ?? null)
  }

  function toggleRowSelectionById(rowId, anchorId = rowId) {
    const current = new Set(selectedIds.value)
    if (current.has(rowId)) {
      current.delete(rowId)
    } else {
      current.add(rowId)
    }

    setSelectedIds([...current], anchorId)
  }

  function selectRange(rowId, anchorRowId = selectionAnchorId.value) {
    const rows = filteredList.value
    const targetIndex = rows.findIndex((item) => item.id === rowId)
    const anchorIndex = rows.findIndex((item) => item.id === anchorRowId)

    if (targetIndex === -1 || anchorIndex === -1) {
      setSelectedIds([rowId], rowId)
      return
    }

    const [start, end] =
      targetIndex < anchorIndex ? [targetIndex, anchorIndex] : [anchorIndex, targetIndex]
    const ids = rows.slice(start, end + 1).map((item) => item.id)
    setSelectedIds(ids, anchorRowId)
  }

  async function pruneSelection(rows = list.value) {
    const validIds = new Set(rows.map((item) => item.id))
    selectedIds.value = selectedIds.value.filter((id) => validIds.has(id))

    if (!selectedIds.value.includes(selectionAnchorId.value)) {
      selectionAnchorId.value = selectedIds.value[selectedIds.value.length - 1] || null
    }

    emitSelectionChange()
    await nextTick()
    syncTableSelection()
  }

  function handleRowClick(row, column, event) {
    onBeforeSelect?.()

    if (isCheckboxClick(event.target)) {
      return
    }

    if (event.shiftKey) {
      selectRange(row.id)
      return
    }

    if (event.ctrlKey || event.metaKey) {
      toggleRowSelectionById(row.id, row.id)
      return
    }

    setSelectedIds([row.id], row.id)
  }

  function handleCheckboxSelect(_, row) {
    if (isSyncingSelection.value) {
      return
    }

    const modifiers = getSelectionModifiers?.() || {}

    if (modifiers.shiftKey) {
      selectRange(row.id, modifiers.anchorId ?? selectionAnchorId.value)
      return
    }

    if (modifiers.ctrlKey || modifiers.metaKey) {
      toggleRowSelectionById(row.id, row.id)
      return
    }

    toggleRowSelectionById(row.id, row.id)
  }

  function handleCheckboxSelectAll(selection) {
    if (isSyncingSelection.value) {
      return
    }

    // Element Plus 的表头全选不会触发 @select，这里必须显式同步自定义选中状态。
    const ids = selection.map((item) => item.id)
    setSelectedIds(ids, ids[ids.length - 1] ?? null)
  }

  let syncTimer = null
  function debouncedSync() {
    clearTimeout(syncTimer)
    syncTimer = setTimeout(() => {
      syncTableSelection()
    }, SELECTION_SYNC_DEBOUNCE_MS)
  }

  watch(filteredList, async () => {
    await nextTick()
    debouncedSync()
  })

  onBeforeUnmount(() => {
    clearTimeout(syncTimer)
  })

  return {
    selectedIds,
    selectedRows,
    selectionAnchorId,
    isSyncingSelection,
    setSelectedIds,
    clearSelection,
    selectAll,
    handleRowClick,
    handleCheckboxSelect,
    handleCheckboxSelectAll,
    pruneSelection,
  }
}
