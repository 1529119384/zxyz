function isDevMode() {
  return Boolean(import.meta.env?.DEV)
}

export const logger = Object.freeze({
  debug(...args) {
    if (isDevMode()) {
      console.debug(...args)
    }
  },
  info(...args) {
    if (isDevMode()) {
      console.info(...args)
    }
  },
  warn(...args) {
    if (isDevMode()) {
      console.warn(...args)
    }
  },
  error(...args) {
    console.error(...args)
  },
})
