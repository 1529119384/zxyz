import { useEventListener } from '@vueuse/core'

import { isEditableTarget } from '@/composables/useFileContextMenu'

/**
 * @typedef {Object} UseFileExplorerHotkeysOptions
 * @property {Function} selectAll - 全选回调。
 * @property {Function} clearSelection - 清除选择回调。
 * @property {Function} closeContextMenu - 关闭右键菜单回调。
 */

/**
 * 文件资源管理器全局快捷键（Ctrl+A 全选、Escape 取消选择）。
 *
 * @param {UseFileExplorerHotkeysOptions} options - 配置项。
 * @returns {{ handleGlobalKeydown: Function }} 快捷键处理方法。
 */
export function useFileExplorerHotkeys(options) {
  const { selectAll, clearSelection, closeContextMenu } = options

  function handleGlobalKeydown(event) {
    if (isEditableTarget(event.target)) {
      return
    }

    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') {
      event.preventDefault()
      closeContextMenu()
      selectAll()
      return
    }

    if (event.key === 'Escape') {
      event.preventDefault()
      closeContextMenu()
      clearSelection()
    }
  }

  useEventListener(window, 'keydown', handleGlobalKeydown)

  return {
    handleGlobalKeydown,
  }
}
