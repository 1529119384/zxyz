import { describe, it, expect } from 'vitest'

import { useSortState } from '@/composables/useSortState'

describe('useSortState', () => {
  it('initializes with default sort state: name/asc', () => {
    const { sortState } = useSortState()
    expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'asc' })
  })

  describe('toggleSort', () => {
    it('toggles direction on the same column (asc -> desc)', () => {
      const { sortState, toggleSort } = useSortState()

      toggleSort('fileName') // same column as default 'name'
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'desc' })
    })

    it('toggles direction on the same column (desc -> asc)', () => {
      const { sortState, toggleSort } = useSortState()

      toggleSort('fileName') // asc -> desc
      toggleSort('fileName') // desc -> asc
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'asc' })
    })

    it('switches to a new column with its default order', () => {
      const { sortState, toggleSort } = useSortState()

      toggleSort('modifyTime')
      expect(sortState.value).toEqual({ sortField: 'modifyTime', sortOrder: 'desc' })
    })

    it('switches to fileSize column with default order desc', () => {
      const { sortState, toggleSort } = useSortState()

      toggleSort('fileSize')
      expect(sortState.value).toEqual({ sortField: 'size', sortOrder: 'desc' })
    })

    it('does nothing when canSort returns false', () => {
      const canSort = { value: false }
      const { sortState, toggleSort } = useSortState({ canSort })

      toggleSort('fileName')
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'asc' })
    })

    it('does nothing when canSort function returns false', () => {
      const canSort = () => false
      const { sortState, toggleSort } = useSortState({ canSort })

      toggleSort('modifyTime')
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'asc' })
    })

    it('allows toggle when canSort ref is true', () => {
      const canSort = { value: true }
      const { sortState, toggleSort } = useSortState({ canSort })

      toggleSort('fileName')
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'desc' })
    })

    it('does nothing for an unknown column', () => {
      const { sortState, toggleSort } = useSortState()

      toggleSort('unknownColumn')
      expect(sortState.value).toEqual({ sortField: 'name', sortOrder: 'asc' })
    })
  })

  describe('isColumnSorted', () => {
    it('returns true for the currently sorted column', () => {
      const { isColumnSorted } = useSortState()

      expect(isColumnSorted('fileName')).toBe(true)
    })

    it('returns false for a non-sorted column', () => {
      const { isColumnSorted } = useSortState()

      expect(isColumnSorted('modifyTime')).toBe(false)
      expect(isColumnSorted('fileSize')).toBe(false)
    })

    it('returns true after switching sort column', () => {
      const { isColumnSorted, toggleSort } = useSortState()

      toggleSort('modifyTime')
      expect(isColumnSorted('modifyTime')).toBe(true)
      expect(isColumnSorted('fileName')).toBe(false)
    })
  })

  describe('getSortIndicator', () => {
    it('returns up arrow for ascending sort on active column', () => {
      const { getSortIndicator } = useSortState()

      expect(getSortIndicator('fileName')).toBe('↑') // up arrow
    })

    it('returns down arrow for descending sort on active column', () => {
      const { getSortIndicator, toggleSort } = useSortState()

      toggleSort('fileName') // now desc
      expect(getSortIndicator('fileName')).toBe('↓') // down arrow
    })

    it('returns shuffle arrow for inactive columns', () => {
      const { getSortIndicator } = useSortState()

      expect(getSortIndicator('modifyTime')).toBe('↕') // up-down arrow
      expect(getSortIndicator('fileSize')).toBe('↕')
    })
  })

  describe('getAriaSort', () => {
    it('returns "ascending" for the default sort column', () => {
      const { getAriaSort } = useSortState()

      expect(getAriaSort('fileName')).toBe('ascending')
    })

    it('returns "descending" after toggling direction', () => {
      const { getAriaSort, toggleSort } = useSortState()

      toggleSort('fileName')
      expect(getAriaSort('fileName')).toBe('descending')
    })

    it('returns "none" for inactive columns', () => {
      const { getAriaSort } = useSortState()

      expect(getAriaSort('modifyTime')).toBe('none')
      expect(getAriaSort('fileSize')).toBe('none')
    })
  })

  describe('getSortLabel', () => {
    it('returns Chinese label for known columns', () => {
      const { getSortLabel } = useSortState()

      expect(getSortLabel('fileName')).toBe('文件名')
      expect(getSortLabel('modifyTime')).toBe('修改时间')
      expect(getSortLabel('fileSize')).toBe('大小')
    })

    it('returns empty string for unknown columns', () => {
      const { getSortLabel } = useSortState()

      expect(getSortLabel('unknown')).toBe('')
    })
  })
})
