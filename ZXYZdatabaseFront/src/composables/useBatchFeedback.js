import { ElMessage } from 'element-plus'

const MAX_DETAIL_COUNT = 3

function getDisplayName(item = {}) {
  return item.finalName || item.fileName || '未知文件'
}

function getOriginalName(item = {}) {
  return item.fileName || item.originalName || '未知文件'
}

function buildLimitedSummary(items = [], formatter) {
  const visibleItems = items.slice(0, MAX_DETAIL_COUNT)
  const summary = visibleItems.map(formatter).filter(Boolean).join('；')

  return `${summary}${items.length > MAX_DETAIL_COUNT ? ' 等' : ''}`
}

function defaultFormatRename(item = {}) {
  return `${getOriginalName(item)} -> ${getDisplayName(item)}`
}

/**
 * 通用批量操作结果反馈，适用于移动、复制、删除、恢复等批量文件操作。
 *
 * @returns {{ showFeedback: Function }} 批量操作结果反馈方法。
 */
export function useBatchFeedback() {
  /**
   * 展示批量操作结果反馈消息。
   *
   * @param {Object} [result] - 批量操作结果。
   * @param {number} [result.successCount] - 成功数量。
   * @param {number} [result.renamedCount] - 自动重命名数量。
   * @param {number} [result.failedCount] - 失败数量。
   * @param {Array} [result.details] - 操作明细列表。
   * @param {Object} [options={}] - 配置项。
   * @param {string} [options.actionName='操作'] - 操作名称。
   * @param {string} [options.fallbackMessage] - 空结果时的兜底消息。
   * @param {Function} [options.formatRename] - 重命名格式化函数。
   */
  function showFeedback(result, options = {}) {
    const {
      actionName = '操作',
      fallbackMessage = `${actionName}成功`,
      formatRename = defaultFormatRename,
    } = options

    // 空结果通常代表后端还未返回批量明细，交给调用方提供兜底成功提示。
    if (!result) {
      ElMessage.success(fallbackMessage)
      return
    }

    const { successCount = 0, renamedCount = 0, failedCount = 0 } = result
    const details = Array.isArray(result.details) ? result.details : []

    if (failedCount > 0 && successCount === 0) {
      const failedItems = details.filter((item) => item?.status === 'failed')
      const failedSummary = buildLimitedSummary(failedItems, getDisplayName)
      ElMessage.error(
        `${actionName}失败：${failedCount} 个文件${failedSummary ? `，失败文件：${failedSummary}` : ''}`,
      )
      return
    }

    if (failedCount > 0) {
      const failedItems = details.filter((item) => item?.status === 'failed')
      const failedSummary = buildLimitedSummary(failedItems, getDisplayName)
      ElMessage.warning(
        `${actionName}完成：${successCount} 成功，${failedCount} 失败${failedSummary ? `，失败文件：${failedSummary}` : ''}`,
      )
      return
    }

    if (renamedCount > 0) {
      const renamedItems = details.filter((item) => item?.renamed)
      const renamedSummary = buildLimitedSummary(renamedItems, formatRename)

      // 批量操作只展示前几项，避免长文件名或大批量结果撑满消息框。
      ElMessage.success(
        `${actionName}成功：${successCount} 个文件，其中 ${renamedCount} 个自动重命名${renamedSummary ? `，${renamedSummary}` : ''}`,
      )
      return
    }

    ElMessage.success(`${actionName}成功：${successCount} 个文件`)
  }

  return { showFeedback }
}
