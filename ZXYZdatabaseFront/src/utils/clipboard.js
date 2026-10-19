import { useClipboard } from '@vueuse/core'

import { logger } from '@/utils/logger'

const { copy: vueUseCopy, isSupported } = useClipboard({
  // 65/66 号发现的 openresty 外层形态下，站点可能经 HTTP（非 Secure Context）提供服务：
  // navigator.clipboard 在非安全上下文为 undefined，复制会静默失败。
  // legacy 模式让 vueuse 在不支持时走 document.execCommand('copy') 回退。
  legacy: true,
})

/**
 * 兜底复制：隐藏 textarea + execCommand('copy')。
 * 仅在 navigator.clipboard 不可用/失败时使用（execCommand 已废弃但兼容面最广）。
 */
function legacyCopy(text) {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  try {
    const ok = document.execCommand('copy')
    if (!ok) throw new Error('execCommand copy 被拒绝')
  } finally {
    document.body.removeChild(textarea)
  }
}

export async function copyText(text) {
  const value = String(text ?? '')
  if (isSupported.value) {
    try {
      await vueUseCopy(value)
      return
    } catch (error) {
      // HTTPS 下用户拒绝权限等场景——落入 legacy 兜底再试一次
      logger.warn('navigator.clipboard 复制失败，尝试 legacy 兜底', error)
    }
  }
  legacyCopy(value)
}
