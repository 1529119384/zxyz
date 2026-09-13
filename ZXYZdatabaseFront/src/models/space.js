// @ts-check
/** @type {Readonly<{ PERSONAL: 1, TEAM: 2, PROJECT: 3 }>} */
export const SPACE_TYPE = Object.freeze({
  PERSONAL: 1,
  TEAM: 2,
  PROJECT: 3,
})

/**
 * 归一化空间类型：只接受 SPACE_TYPE 中的取值，其余（含 null/undefined/字符串）一律回退到个人空间。
 * @param {unknown} value
 * @returns {1 | 2 | 3}
 */
export function normalizeSpaceType(value) {
  const numberValue = Number(value)
  const allowedValues = /** @type {number[]} */ (Object.values(SPACE_TYPE))
  return allowedValues.includes(numberValue)
    ? /** @type {1 | 2 | 3} */ (numberValue)
    : SPACE_TYPE.PERSONAL
}

/**
 * @param {unknown} spaceType
 * @returns {string}
 */
export function getSpaceUsageTitle(spaceType) {
  const normalizedSpaceType = normalizeSpaceType(spaceType)
  if (normalizedSpaceType === SPACE_TYPE.PROJECT) return '项目空间用量'
  if (normalizedSpaceType === SPACE_TYPE.TEAM) return '团队空间用量'
  return '个人空间用量'
}
