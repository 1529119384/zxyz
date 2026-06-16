/**
 * 校验重定向路径是否安全（仅允许本站相对路径）。
 * 阻断外部 URL、协议相对 URL 和危险协议注入。
 */
export function sanitizeRedirectPath(path, fallback = '/index') {
  if (typeof path !== 'string') return fallback

  const trimmed = path.trim()
  if (!trimmed.startsWith('/')) return fallback

  // Decode URL-encoded characters BEFORE protocol checks to prevent bypasses
  // like %2F%2F (//), %3A%2F%2F (://), or %6A%61%76%61%73%63%72%69%70%74%3A (javascript:)
  let decoded
  try {
    decoded = decodeURIComponent(trimmed)
  } catch {
    // Invalid percent-encoding — reject the path
    return fallback
  }

  if (decoded.startsWith('//')) return fallback
  if (decoded.includes('://')) return fallback

  const lower = decoded.toLowerCase()
  if (lower.includes('javascript:') || lower.includes('data:') || lower.includes('vbscript:')) {
    return fallback
  }

  // Strip control characters (tab, newline, etc.) that could bypass regex checks
  const cleaned = [...decoded]
    .filter((ch) => {
      const code = ch.charCodeAt(0)
      return code > 0x1f && code !== 0x7f
    })
    .join('')

  // Whitelist: only allow safe path characters
  if (!/^\/[\w\-./?#=&%+;@!~]*$/.test(cleaned)) {
    return fallback
  }

  return trimmed
}
