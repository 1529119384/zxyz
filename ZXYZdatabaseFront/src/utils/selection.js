// 统一动作目标解析逻辑，避免页面层和 composable 层重复维护同一套选择规则。
export function resolveActionTargets({ selectedItems = [], targetItem = null } = {}) {
  return selectedItems.length ? selectedItems : targetItem ? [targetItem] : []
}
