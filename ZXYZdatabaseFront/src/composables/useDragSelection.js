import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * @typedef {Object} UseDragSelectionOptions
 * @property {import('vue').Ref<HTMLElement|null>} dragContainerRef - 拖拽容器 DOM 引用。
 * @property {import('vue').Ref<HTMLElement|null>} tableWrapperRef - 表格包裹 DOM 引用。
 * @property {import('vue').Ref<Array>} filteredList - 当前过滤后的文件列表。
 * @property {Function} isCheckboxClick - 判断点击目标是否为复选框的函数。
 * @property {import('vue').Ref<Array<number>>} selectedIds - 当前选中的 ID 列表。
 * @property {Function} setSelectedIds - 设置选中 ID 列表的函数。
 * @property {Function} closeContextMenu - 关闭右键菜单回调。
 */

/**
 * 文件列表拖拽框选功能，支持鼠标拖拽多选行。
 *
 * @param {UseDragSelectionOptions} options - 配置项。
 * @returns {{ dragState: import('vue').Ref<Object>, selectionBoxStyle: import('vue').ComputedRef<Object>, getRowFromTarget: Function, isBodyWrapperTarget: Function, shouldSuppressRowClick: Function }} 拖拽选择状态与操作方法。
 */
export function useDragSelection(options) {
  const {
    dragContainerRef,
    tableWrapperRef,
    filteredList,
    isCheckboxClick,
    selectedIds,
    setSelectedIds,
    closeContextMenu,
  } = options

  const dragState = ref({
    active: false,
    visible: false,
    suppressClick: false,
    startX: 0,
    startY: 0,
    currentX: 0,
    currentY: 0,
  })

  const dragSession = ref({
    hasStarted: false,
    additive: false,
    initialSelectedIds: [],
  })

  let boundDragContainer = null
  let dragAbortController = null
  let cachedMetrics = null
  let cachedRowRects = []

  const selectionBoxStyle = computed(() => {
    const left = Math.min(dragState.value.startX, dragState.value.currentX)
    const top = Math.min(dragState.value.startY, dragState.value.currentY)
    const width = Math.abs(dragState.value.currentX - dragState.value.startX)
    const height = Math.abs(dragState.value.currentY - dragState.value.startY)

    return {
      left: `${left}px`,
      top: `${top}px`,
      width: `${width}px`,
      height: `${height}px`,
    }
  })

  function getBodyWrapperElement() {
    return tableWrapperRef.value?.querySelector('.el-table__body-wrapper') || null
  }

  function getDragContainerElement() {
    return dragContainerRef.value || null
  }

  function getRowElements() {
    return Array.from(
      tableWrapperRef.value?.querySelectorAll('.el-table__body-wrapper tbody .el-table__row') || [],
    )
  }

  function getBodyWrapperMetrics() {
    const wrapper = getBodyWrapperElement()
    const container = getDragContainerElement()
    if (!wrapper || !container) {
      return null
    }

    const wrapperRect = wrapper.getBoundingClientRect()
    const containerRect = container.getBoundingClientRect()

    return {
      wrapper,
      wrapperRect,
      containerRect,
      offsetLeft: wrapperRect.left - containerRect.left + wrapper.scrollLeft,
      offsetTop: wrapperRect.top - containerRect.top + wrapper.scrollTop,
    }
  }

  function getRowFromTarget(target) {
    const rowElement = target.closest('.el-table__row')
    if (!rowElement) {
      return null
    }

    const rowIndex = getRowElements().indexOf(rowElement)
    if (rowIndex < 0) {
      return null
    }

    return filteredList.value[rowIndex] || null
  }

  function isBodyWrapperTarget(target) {
    return getBodyWrapperElement()?.contains(target) || false
  }

  function isDragContainerTarget(target) {
    return getDragContainerElement()?.contains(target) || false
  }

  function isInteractiveTarget(target) {
    if (!(target instanceof HTMLElement)) {
      return false
    }

    return Boolean(
      target.closest(
        'input, textarea, button, a, [contenteditable="true"], .el-checkbox, .el-button, .el-input, .el-textarea, .el-dialog, .el-overlay',
      ),
    )
  }

  function cacheRowRects() {
    if (!cachedMetrics) return
    cachedMetrics.offsetLeft =
      cachedMetrics.wrapperRect.left -
      cachedMetrics.containerRect.left +
      cachedMetrics.wrapper.scrollLeft
    cachedMetrics.offsetTop =
      cachedMetrics.wrapperRect.top -
      cachedMetrics.containerRect.top +
      cachedMetrics.wrapper.scrollTop

    const rows = getRowElements()
    const list = filteredList.value
    cachedRowRects = rows.map((rowElement, index) => ({
      id: list[index]?.id,
      rect: rowElement.getBoundingClientRect(),
    }))
  }

  function startDragSelection(event) {
    const metrics = getBodyWrapperMetrics()
    if (!metrics) {
      return
    }

    cachedMetrics = metrics
    cacheRowRects()

    dragState.value = {
      active: true,
      visible: false,
      suppressClick: dragState.value.suppressClick,
      startX: event.clientX - metrics.wrapperRect.left + metrics.offsetLeft,
      startY: event.clientY - metrics.wrapperRect.top + metrics.offsetTop,
      currentX: event.clientX - metrics.wrapperRect.left + metrics.offsetLeft,
      currentY: event.clientY - metrics.wrapperRect.top + metrics.offsetTop,
    }

    dragAbortController = new AbortController()
    metrics.wrapper.addEventListener('scroll', cacheRowRects, {
      signal: dragAbortController.signal,
    })
    document.addEventListener('mousemove', handleDragMouseMove, {
      signal: dragAbortController.signal,
    })
    document.addEventListener('mouseup', handleDragMouseUp, { signal: dragAbortController.signal })
  }

  function updateDragSelection(clientX, clientY) {
    if (!cachedMetrics) {
      return
    }

    dragState.value.currentX = clientX - cachedMetrics.wrapperRect.left + cachedMetrics.offsetLeft
    dragState.value.currentY = clientY - cachedMetrics.wrapperRect.top + cachedMetrics.offsetTop

    const width = Math.abs(dragState.value.currentX - dragState.value.startX)
    const height = Math.abs(dragState.value.currentY - dragState.value.startY)
    dragState.value.visible = width > 3 || height > 3

    if (!dragState.value.visible) {
      return
    }

    const selectionRect = {
      left: Math.min(dragState.value.startX, dragState.value.currentX),
      top: Math.min(dragState.value.startY, dragState.value.currentY),
      right: Math.max(dragState.value.startX, dragState.value.currentX),
      bottom: Math.max(dragState.value.startY, dragState.value.currentY),
    }

    const nextIds = cachedRowRects.reduce((ids, { id, rect }) => {
      const localRect = {
        left: rect.left - cachedMetrics.wrapperRect.left + cachedMetrics.offsetLeft,
        top: rect.top - cachedMetrics.wrapperRect.top + cachedMetrics.offsetTop,
        right: rect.right - cachedMetrics.wrapperRect.left + cachedMetrics.offsetLeft,
        bottom: rect.bottom - cachedMetrics.wrapperRect.top + cachedMetrics.offsetTop,
      }
      const intersected = !(
        localRect.right < selectionRect.left ||
        localRect.left > selectionRect.right ||
        localRect.bottom < selectionRect.top ||
        localRect.top > selectionRect.bottom
      )

      if (intersected && id != null) {
        ids.push(id)
      }
      return ids
    }, [])

    const mergedIds = dragSession.value.additive
      ? [...new Set([...dragSession.value.initialSelectedIds, ...nextIds])]
      : nextIds

    setSelectedIds(
      mergedIds,
      nextIds[nextIds.length - 1] || mergedIds[mergedIds.length - 1] || null,
    )
  }

  function handleDragMouseMove(event) {
    if (!dragState.value.active) {
      return
    }

    updateDragSelection(event.clientX, event.clientY)
  }

  function stopDragSelection() {
    dragAbortController?.abort()
    dragAbortController = null
    cachedMetrics = null
    cachedRowRects = []
    dragState.value.active = false
    dragState.value.visible = false
  }

  function handleDragMouseUp() {
    if (!dragState.value.active) {
      return
    }

    if (dragState.value.visible) {
      dragState.value.suppressClick = true
      requestAnimationFrame(() => {
        dragState.value.suppressClick = false
      })
    }

    dragSession.value = {
      hasStarted: false,
      additive: false,
      initialSelectedIds: [],
    }

    stopDragSelection()
  }

  function handleBodyMouseDown(event) {
    if (event.button !== 0) {
      return
    }
    if (!isDragContainerTarget(event.target)) {
      return
    }
    if (isInteractiveTarget(event.target) || isCheckboxClick(event.target)) {
      return
    }

    dragSession.value = {
      hasStarted: true,
      additive: event.ctrlKey || event.metaKey,
      initialSelectedIds: [...selectedIds.value],
    }

    closeContextMenu()
    startDragSelection(event)
  }

  function shouldSuppressRowClick() {
    return dragState.value.suppressClick
  }

  watch(
    dragContainerRef,
    async (container) => {
      boundDragContainer?.removeEventListener('mousedown', handleBodyMouseDown)
      boundDragContainer = null

      if (!container) {
        return
      }

      await nextTick()
      boundDragContainer = getDragContainerElement()
      boundDragContainer?.addEventListener('mousedown', handleBodyMouseDown)
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    boundDragContainer?.removeEventListener('mousedown', handleBodyMouseDown)
    dragAbortController?.abort()
    dragAbortController = null
    cachedMetrics = null
    cachedRowRects = []
  })

  return {
    dragState,
    selectionBoxStyle,
    getRowFromTarget,
    isBodyWrapperTarget,
    shouldSuppressRowClick,
  }
}
