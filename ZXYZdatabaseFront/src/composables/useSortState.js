import { ref } from 'vue'

const SORT_DEFAULTS = {
  name: 'asc',
  modifyTime: 'desc',
  size: 'desc',
}

const SORT_COLUMNS = {
  fileName: 'name',
  modifyTime: 'modifyTime',
  fileSize: 'size',
}

const SORT_LABELS = {
  fileName: '文件名',
  modifyTime: '修改时间',
  fileSize: '大小',
}

function isSortEnabled(canSort) {
  if (!canSort) {
    return true
  }
  if (typeof canSort === 'function') {
    return Boolean(canSort())
  }
  return Boolean(canSort.value)
}

/**
 * @typedef {Object} UseSortStateOptions
 * @property {Function|import('vue').Ref<boolean>} [canSort] - 排序是否可用，为函数时动态判断。
 */

/**
 * 文件列表排序状态管理，支持按列切换排序方向。
 *
 * @param {UseSortStateOptions} [options={}] - 配置项。
 * @returns {{ sortState: import('vue').Ref<Object>, getSortLabel: Function, isColumnSorted: Function, getSortIndicator: Function, toggleSort: Function }} 排序状态与操作方法。
 */
export function useSortState(options = {}) {
  const { canSort } = options
  const sortState = ref({
    sortField: 'name',
    sortOrder: 'asc',
  })

  function getSortLabel(column) {
    return SORT_LABELS[column] || ''
  }

  function isColumnSorted(column) {
    return sortState.value.sortField === SORT_COLUMNS[column]
  }

  function getSortIndicator(column) {
    if (!isColumnSorted(column)) {
      return '↕'
    }

    return sortState.value.sortOrder === 'asc' ? '↑' : '↓'
  }

  function getAriaSort(column) {
    if (!isColumnSorted(column)) {
      return 'none'
    }

    return sortState.value.sortOrder === 'asc' ? 'ascending' : 'descending'
  }

  function toggleSort(column) {
    if (!isSortEnabled(canSort)) {
      return
    }

    const nextSortField = SORT_COLUMNS[column]
    if (!nextSortField) {
      return
    }

    if (sortState.value.sortField === nextSortField) {
      sortState.value = {
        sortField: nextSortField,
        sortOrder: sortState.value.sortOrder === 'asc' ? 'desc' : 'asc',
      }
      return
    }

    sortState.value = {
      sortField: nextSortField,
      sortOrder: SORT_DEFAULTS[nextSortField] || 'asc',
    }
  }

  return {
    sortState,
    getSortLabel,
    isColumnSorted,
    getSortIndicator,
    getAriaSort,
    toggleSort,
  }
}
