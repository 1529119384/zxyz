import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

import { useFileContextMenu, isEditableTarget } from '@/composables/useFileContextMenu'

describe('isEditableTarget', () => {
  it('应拒绝非 HTMLElement', () => {
    expect(isEditableTarget(null)).toBe(false)
    expect(isEditableTarget('string')).toBe(false)
  })

  it('应识别 input 元素', () => {
    const el = document.createElement('input')
    document.body.appendChild(el)
    expect(isEditableTarget(el)).toBe(true)
    document.body.removeChild(el)
  })

  it('应识别 textarea 元素', () => {
    const el = document.createElement('textarea')
    document.body.appendChild(el)
    expect(isEditableTarget(el)).toBe(true)
    document.body.removeChild(el)
  })
})

describe('useFileContextMenu', () => {
  let containerRef,
    selectedRows,
    selectedIds,
    setSelectedIds,
    getRowFromTarget,
    shouldSuppressRowClick

  beforeEach(() => {
    containerRef = ref(document.createElement('div'))
    selectedRows = ref([])
    selectedIds = ref([])
    setSelectedIds = vi.fn()
    getRowFromTarget = vi.fn(() => null)
    shouldSuppressRowClick = vi.fn(() => false)
  })

  function createInstance() {
    return useFileContextMenu({
      containerRef,
      selectedRows,
      selectedIds,
      setSelectedIds,
      getRowFromTarget,
      shouldSuppressRowClick,
    })
  }

  it('应初始化菜单为不可见', () => {
    const { contextMenu } = createInstance()
    expect(contextMenu.value.visible).toBe(false)
  })

  it('应打开菜单并设置位置', () => {
    const { openContextMenu, contextMenu } = createInstance()
    openContextMenu({ clientX: 100, clientY: 200 })
    expect(contextMenu.value.visible).toBe(true)
    expect(contextMenu.value.position).toEqual({ x: 100, y: 200 })
  })

  it('应关闭菜单', () => {
    const { openContextMenu, closeContextMenu, contextMenu } = createInstance()
    openContextMenu({ clientX: 0, clientY: 0 })
    closeContextMenu()
    expect(contextMenu.value.visible).toBe(false)
  })

  it('应识别文件类型右键', () => {
    const { openContextMenu, contextMenu } = createInstance()
    selectedRows.value = [{ id: 1, type: 1 }]
    openContextMenu({ clientX: 0, clientY: 0 }, { id: 1, type: 1 })
    expect(contextMenu.value.contextType).toBe('file')
  })

  it('应识别文件夹类型右键', () => {
    const { openContextMenu, contextMenu } = createInstance()
    openContextMenu({ clientX: 0, clientY: 0 }, { id: 2, type: 0 })
    expect(contextMenu.value.contextType).toBe('folder')
  })

  it('应识别多选右键', () => {
    const { openContextMenu, contextMenu } = createInstance()
    selectedRows.value = [{ id: 1 }, { id: 2 }]
    openContextMenu({ clientX: 0, clientY: 0 }, { id: 1 })
    expect(contextMenu.value.contextType).toBe('multi')
  })

  it('应识别空白区域右键', () => {
    const { openContextMenu, contextMenu } = createInstance()
    openContextMenu({ clientX: 0, clientY: 0 }, null)
    expect(contextMenu.value.contextType).toBe('blank')
  })

  it('应同步选区当右键未选中行', () => {
    const { syncSelectionForContextMenu } = createInstance()
    selectedIds.value = []
    syncSelectionForContextMenu({ id: 5 })
    expect(setSelectedIds).toHaveBeenCalledWith([5], 5)
  })

  it('不应重复同步已选中行', () => {
    const { syncSelectionForContextMenu } = createInstance()
    selectedIds.value = [5]
    syncSelectionForContextMenu({ id: 5 })
    expect(setSelectedIds).not.toHaveBeenCalled()
  })
})
