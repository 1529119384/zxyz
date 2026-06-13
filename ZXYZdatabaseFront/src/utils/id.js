export function normalizePositiveId(value) {
  const numberValue = Number(value)
  return Number.isSafeInteger(numberValue) && numberValue > 0 ? numberValue : null
}

function createFallbackUuidFromRandomValues() {
  const cryptoApi = globalThis.crypto
  if (!cryptoApi?.getRandomValues) {
    return ''
  }

  const bytes = new Uint8Array(16)
  cryptoApi.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function createClientId(prefix = '') {
  const cryptoApi = globalThis.crypto
  const uuid =
    typeof cryptoApi?.randomUUID === 'function'
      ? cryptoApi.randomUUID()
      : createFallbackUuidFromRandomValues()

  if (uuid) {
    return prefix ? `${prefix}-${uuid}` : uuid
  }

  const fallback = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
  return prefix ? `${prefix}-${fallback}` : fallback
}
