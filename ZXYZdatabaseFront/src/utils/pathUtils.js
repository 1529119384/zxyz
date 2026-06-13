function isPathOptions(value) {
  return Boolean(
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    ('leadingSlash' in value || 'decode' in value),
  )
}

function parsePathSegments(path, { decode = true } = {}) {
  let normalizedPath = String(path || '').replace(/\\/g, '/')

  if (decode) {
    normalizedPath = decodeURIComponent(normalizedPath)
  }

  return normalizedPath.split('/').filter(Boolean)
}

export function normalizePath(path, options = {}) {
  const { leadingSlash = true, decode = true } = options
  const segments = parsePathSegments(path, { decode })

  if (!segments.length) {
    return ''
  }

  const joinedPath = segments.join('/')
  return leadingSlash ? `/${joinedPath}` : joinedPath
}

export function joinPath(...input) {
  const lastItem = input[input.length - 1]
  const options = isPathOptions(lastItem) ? input.pop() : {}
  const segments = input
    .filter((segment) => segment !== undefined && segment !== null && segment !== '')
    .map((segment) => String(segment))

  return normalizePath(segments.join('/'), {
    decode: false,
    ...options,
  })
}

export function parseCrumbs(path, options = {}) {
  return parsePathSegments(path, options)
}

export function buildCrumbPath(crumbs, index, options = {}) {
  // 面包屑回拼统一走 joinPath，避免各处重复维护斜线规则。
  return joinPath(...crumbs.slice(0, index + 1), options)
}
