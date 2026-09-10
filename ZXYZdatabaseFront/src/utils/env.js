// 入口自检使用的「加载期必需环境变量」清单。
// 这些变量在 request.js / publicRequest.js / imRequest.js / imWebSocket.js 的模块加载期
// 经 requireViteEnv 读取，缺失会直接 throw 导致整个 SPA 启动崩溃白屏。
// 新增加载期必需变量时务必在此同步登记，否则入口友好提示会漏报。
export const REQUIRED_ENV_VARS = [
  'VITE_API_BASE_URL',
  'VITE_IM_API_BASE_URL',
  'VITE_IM_WS_URL',
]

// 返回缺失的必需环境变量名数组（不抛异常），供 src/main.js 入口做友好提示。
export function listMissingRequiredEnvs() {
  return REQUIRED_ENV_VARS.filter((name) => {
    const value = import.meta.env[name]
    return !value || !String(value).trim()
  })
}

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
