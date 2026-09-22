// 入口自检使用的「加载期必需环境变量」清单。
// 这些变量在 request.js / publicRequest.js / imRequest.js / imWebSocket.js 的模块加载期
// 经 requireViteEnv 读取，缺失会直接 throw 导致整个 SPA 启动崩溃白屏。
// 新增加载期必需变量时务必在此同步登记，否则入口友好提示会漏报。
//
// 🔴 该清单与「真实调用点」的一致性由 src/utils/__tests__/env.spec.js 强制守护（F1）：
//    新增/删除 requireViteEnv 调用点而不同步本清单，测试会直接变红 —— 因为这份手写清单
//    一旦漂移，入口自检就会漏报（表现为白屏而非友好提示），属"静默失效"类缺陷。
export const REQUIRED_ENV_VARS = [
  'VITE_API_BASE_URL',
  'VITE_IM_API_BASE_URL',
  'VITE_IM_WS_URL',
]

/**
 * 统一的 API 基地址（来自 VITE_API_BASE_URL）。
 *
 * <p>request.js 与 publicRequest.js 原先各自复制了一份逐字相同的 `getApiBaseUrl()`，
 * 且变量名也各自硬编码 —— 改一处漏一处就会出现两套基地址（F7）。此处收口为单一来源，
 * 同时让 F1 的守卫只需盯住本文件。</p>
 */
export function resolveApiBaseUrl() {
  return requireViteEnv('VITE_API_BASE_URL')
}

/**
 * 「该环境变量是否可用」的唯一判定。
 *
 * <p>🔴 {@link requireViteEnv} 与 {@link listMissingRequiredEnvs} 必须共用这一个判定。
 * 两者原先各写一份「缺失」判断（一个用 `?.trim()` 判真值、一个用 `!value || !String(value).trim()`），
 * 于是「什么算配好了」这件事就有了两份定义 —— 口径一旦分叉，
 * 入口自检会报「都配好了」而模块加载期照样 throw（结果是白屏而非友好提示），
 * 且这类缺陷只在某个变量值恰好落在两份定义的夹缝里时才复现（F2）。</p>
 *
 * <p>注意本判定<b>不</b>包含 {@code devFallback}：fallback 是「缺失时的补救」，
 * 不是「已配置」。入口自检要报的是「真实缺失」，所以它必须用同一个不含 fallback 的判定。</p>
 */
function isEnvConfigured(name) {
  const value = import.meta.env[name]
  return Boolean(value && String(value).trim())
}

// 返回缺失的必需环境变量名数组（不抛异常），供 src/main.js 入口做友好提示。
export function listMissingRequiredEnvs() {
  return REQUIRED_ENV_VARS.filter((name) => !isEnvConfigured(name))
}

export function requireViteEnv(name, devFallback = '') {
  if (isEnvConfigured(name)) {
    return String(import.meta.env[name]).trim()
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
