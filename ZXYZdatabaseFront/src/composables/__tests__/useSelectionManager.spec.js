import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'

vi.mock('@vueuse/core', () => ({
  useDebounceFn: (fn) => {
    const debounced = (...args) => fn(...args)
    debounced.cancel = vi.fn()
    return debounced
  },
}))

import { useSelectionManager } from '@/composables/useSelectionManager'

describe('useSelectionManager', () => {
  const items = [
    { id: 1, name: 'A' },
    { id: 2, name: 'B' },
    { id: 3, name: 'C' },
    { id: 4, name: 'D' },
    { id: 5, name: 'E' },
  ]

  let listRef, filteredListRef, tableRef
  let onSelectionChange, onBeforeSelect
  let isCheckboxClick

  function createManager(overrides = {}) {
    listRef = ref([...items])
    filteredListRef = ref([...items])
    tableRef = ref({
      clearSelection: vi.fn(),
      toggleRowSelection: vi.fn(),
    })
    onSelectionChange = vi.fn()
    onBeforeSelect = vi.fn()
    isCheckboxClick = vi.fn().mockReturnValue(false)

    return useSelectionManager({
      list: listRef,
      filteredList: filteredListRef,
      tableRef,
      isCheckboxClick,
      onSelectionChange,
      onBeforeSelect,
      ...overrides,
    })
  }

  beforeEach(() => {
    vi.clearAllMocks()
    // syncTableSelection resets isSyncingSelection inside requestAnimationFrame.
    // Happy-dom does not fire RAF callbacks synchronously, so we stub it to
    // execute the callback immediately — otherwise isSyncingSelection stays true
    // and subsequent handleCheckboxSelect calls bail out early.
    vi.stubGlobal('requestAnimationFrame', (cb) => {
      cb()
      return 0
    })
  })

  describe('setSelectedIds', () => {
    it('updates selectedIds with the given ids', () => {
      const { selectedIds, setSelectedIds } = createManager()

      setSelectedIds([1, 3, 5])
      expect(selectedIds.value).toEqual([1, 3, 5])
    })

    it('deduplicates ids', () => {
      const { selectedIds, setSelectedIds } = createManager()

      setSelectedIds([1, 1, 3, 3, 5])
      expect(selectedIds.value).toEqual([1, 3, 5])
    })

    it('sets anchorId to provided anchorId', () => {
      const { selectionAnchorId, setSelectedIds } = createManager()

      setSelectedIds([1, 3, 5], 3)
      expect(selectionAnchorId.value).toBe(3)
    })

    it('defaults anchorId to last selected id when not provided', () => {
      const { selectionAnchorId, setSelectedIds } = createManager()

      setSelectedIds([1, 3, 5])
      expect(selectionAnchorId.value).toBe(5)
    })

    it('sets anchorId to null when no ids provided and no anchor', () => {
      const { selectionAnchorId, setSelectedIds } = createManager()

      setSelectedIds([])
      expect(selectionAnchorId.value).toBeNull()
    })

    it('emits selection change event', () => {
      const { setSelectedIds } = createManager()

      setSelectedIds([1, 2])
      expect(onSelectionChange).toHaveBeenCalledWith({
        rows: [items[0], items[1]],
        anchorId: 2,
      })
    })

    it('syncs table selection', () => {
      const { setSelectedIds } = createManager()

      setSelectedIds([1, 3])
      expect(tableRef.value.clearSelection).toHaveBeenCalled()
      expect(tableRef.value.toggleRowSelection).toHaveBeenCalledWith(items[0], true)
      expect(tableRef.value.toggleRowSelection).toHaveBeenCalledWith(items[2], true)
    })
  })

  describe('clearSelection', () => {
    it('empties selectedIds and anchorId', () => {
      const { selectedIds, selectionAnchorId, setSelectedIds, clearSelection } = createManager()

      setSelectedIds([1, 2, 3], 2)
      clearSelection()

      expect(selectedIds.value).toEqual([])
      expect(selectionAnchorId.value).toBeNull()
    })
  })

  describe('selectAll', () => {
    it('selects all items in filteredList', () => {
      const { selectedIds, selectionAnchorId, selectAll } = createManager()

      selectAll()

      expect(selectedIds.value).toEqual([1, 2, 3, 4, 5])
      expect(selectionAnchorId.value).toBe(5)
    })

    it('selects only filtered items when filteredList is a subset', () => {
      const { selectedIds, selectAll } = createManager()

      filteredListRef.value = [items[1], items[3]]
      selectAll()

      expect(selectedIds.value).toEqual([2, 4])
    })
  })

  describe('selectedRows', () => {
    it('returns the full row objects matching selectedIds', () => {
      const { selectedRows, setSelectedIds } = createManager()

      setSelectedIds([2, 4])
      expect(selectedRows.value).toEqual([items[1], items[3]])
    })

    it('returns empty array when nothing selected', () => {
      const { selectedRows } = createManager()

      expect(selectedRows.value).toEqual([])
    })
  })

  describe('handleRowClick', () => {
    it('selects single row with no modifier keys', () => {
      const { selectedIds, handleRowClick } = createManager()
      const event = { shiftKey: false, ctrlKey: false, metaKey: false, target: {} }

      handleRowClick(items[2], null, event)

      expect(selectedIds.value).toEqual([3])
    })

    it('calls onBeforeSelect', () => {
      const { handleRowClick } = createManager()
      const event = { shiftKey: false, ctrlKey: false, metaKey: false, target: {} }

      handleRowClick(items[0], null, event)

      expect(onBeforeSelect).toHaveBeenCalled()
    })

    it('does nothing when click target is a checkbox', () => {
      const { selectedIds, handleRowClick } = createManager()
      const event = { shiftKey: false, ctrlKey: false, metaKey: false, target: 'checkbox-element' }

      isCheckboxClick.mockReturnValue(true)

      handleRowClick(items[0], null, event)

      expect(selectedIds.value).toEqual([])
    })

    it('toggles selection with ctrlKey', () => {
      const { selectedIds, setSelectedIds, handleRowClick } = createManager()

      setSelectedIds([1, 3])

      const event = { shiftKey: false, ctrlKey: true, metaKey: false, target: {} }
      handleRowClick(items[2], null, event) // click on item 3 -> deselect

      expect(selectedIds.value).toEqual([1])
    })

    it('adds to selection with ctrlKey when item not selected', () => {
      const { selectedIds, setSelectedIds, handleRowClick } = createManager()

      setSelectedIds([1])

      const event = { shiftKey: false, ctrlKey: true, metaKey: false, target: {} }
      handleRowClick(items[2], null, event) // click on item 3

      expect(selectedIds.value).toEqual([1, 3])
    })

    it('selects range with shiftKey', () => {
      const { selectedIds, setSelectedIds, handleRowClick } = createManager()

      setSelectedIds([2], 2) // anchor at item 2

      const event = { shiftKey: true, ctrlKey: false, metaKey: false, target: {} }
      handleRowClick(items[4], null, event) // shift-click on item 5

      expect(selectedIds.value).toEqual([2, 3, 4, 5])
    })

    it('selects range backwards with shiftKey', () => {
      const { selectedIds, setSelectedIds, handleRowClick } = createManager()

      setSelectedIds([4], 4) // anchor at item 4

      const event = { shiftKey: true, ctrlKey: false, metaKey: false, target: {} }
      handleRowClick(items[1], null, event) // shift-click on item 2

      expect(selectedIds.value).toEqual([2, 3, 4])
    })
  })

  describe('handleCheckboxSelect', () => {
    it('toggles row selection', () => {
      const { selectedIds, handleCheckboxSelect } = createManager()

      handleCheckboxSelect(null, items[0])
      expect(selectedIds.value).toEqual([1])

      handleCheckboxSelect(null, items[0])
      expect(selectedIds.value).toEqual([])
    })

    it('does nothing when isSyncingSelection is true', () => {
      const { selectedIds, setSelectedIds, isSyncingSelection, handleCheckboxSelect } =
        createManager()

      setSelectedIds([1])
      isSyncingSelection.value = true

      handleCheckboxSelect(null, items[1])

      expect(selectedIds.value).toEqual([1])
    })
  })

  describe('pruneSelection', () => {
    it('removes IDs not present in the provided rows', async () => {
      const { selectedIds, selectionAnchorId, setSelectedIds, pruneSelection } = createManager()

      setSelectedIds([1, 2, 3, 4, 5], 3)

      const remainingRows = [items[0], items[2], items[4]] // ids 1, 3, 5
      await pruneSelection(remainingRows)

      expect(selectedIds.value).toEqual([1, 3, 5])
    })

    it('updates anchorId when anchor was pruned', async () => {
      const { selectedIds, selectionAnchorId, setSelectedIds, pruneSelection } = createManager()

      setSelectedIds([1, 2, 3], 2) // anchor is 2

      const remainingRows = [items[0], items[2]] // ids 1, 3
      await pruneSelection(remainingRows)

      expect(selectedIds.value).toEqual([1, 3])
      expect(selectionAnchorId.value).toBe(3) // fallback to last
    })

    it('sets anchorId to null when all selected items are pruned', async () => {
      const { selectedIds, selectionAnchorId, setSelectedIds, pruneSelection } = createManager()

      setSelectedIds([1, 2], 1)

      await pruneSelection([]) // all items removed

      expect(selectedIds.value).toEqual([])
      expect(selectionAnchorId.value).toBeNull()
    })

    it('defaults to using list when called without args', async () => {
      const { selectedIds, setSelectedIds, pruneSelection } = createManager()

      setSelectedIds([1, 2, 3, 4, 5])

      listRef.value = [items[0], items[1]] // only ids 1, 2 remain in list

      await pruneSelection()

      expect(selectedIds.value).toEqual([1, 2])
    })
  })
})
