function getResultName(item = {}) {
  return item.finalName || item.fileName || item.originalName || ''
}

function getRenamedLabel(item = {}) {
  const originalName = item.originalName || item.fileName || ''
  const finalName = getResultName(item)

  if (!originalName || !finalName || originalName === finalName) {
    return ''
  }

  return `${originalName} -> ${finalName}`
}

export function formatUploadSummary(successList = [], failList = [], successText = '') {
  const summary = []

  if (successText) {
    summary.push(successText)
  }

  summary.push(`成功 ${successList.length} 个`)
  summary.push(`失败 ${failList.length} 个`)

  const renamedItems = successList.filter((item) => item.renamed && getRenamedLabel(item))
  if (renamedItems.length) {
    const renamedSummary = renamedItems.slice(0, 3).map(getRenamedLabel).join('；')
    summary.push(`已自动改名：${renamedSummary}${renamedItems.length > 3 ? ' 等' : ''}`)
  }

  if (failList.length) {
    const failSummary = failList
      .slice(0, 3)
      .map(
        (item) => `${getResultName(item) || '未知文件'}${item.message ? `(${item.message})` : ''}`,
      )
      .join('；')
    summary.push(`失败项：${failSummary}${failList.length > 3 ? ' 等' : ''}`)
  }

  return summary.join('，')
}
