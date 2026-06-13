export function requireViteEnv(name, devFallback = '') {
  const value = import.meta.env[name]?.trim()
  if (value) {
    return value
  }
  if (import.meta.env.DEV && devFallback) {
    return devFallback
  }
  throw new Error(`${name} 未配置`)
}

export function resolveWebSocketUrl(value) {
  if (!value.startsWith('/')) {
    return value
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${value}`
}
