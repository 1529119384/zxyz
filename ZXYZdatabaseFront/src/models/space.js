export const SPACE_TYPE = Object.freeze({
  PERSONAL: 1,
  TEAM: 2,
  PROJECT: 3,
})

export function normalizeSpaceType(value) {
  const numberValue = Number(value)
  return Object.values(SPACE_TYPE).includes(numberValue) ? numberValue : SPACE_TYPE.PERSONAL
}

export function getSpaceUsageTitle(spaceType) {
  const normalizedSpaceType = normalizeSpaceType(spaceType)
  if (normalizedSpaceType === SPACE_TYPE.PROJECT) return '项目空间用量'
  if (normalizedSpaceType === SPACE_TYPE.TEAM) return '团队空间用量'
  return '个人空间用量'
}
