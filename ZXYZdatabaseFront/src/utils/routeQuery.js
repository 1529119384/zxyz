export function getRouteQueryText(value) {
  if (Array.isArray(value)) {
    return value.find((item) => typeof item === 'string') || ''
  }

  return typeof value === 'string' ? value : ''
}

export function withRouteQueryText(query, key, value) {
  const nextQuery = { ...query }
  const normalizedValue = typeof value === 'string' ? value : String(value ?? '')

  if (normalizedValue.trim()) {
    nextQuery[key] = normalizedValue
  } else {
    delete nextQuery[key]
  }

  return nextQuery
}
