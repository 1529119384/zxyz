/**
 * Minimal event emitter for decoupling store domains.
 *
 * 用于打破 teamDomain ↔ permissionDomain 的循环依赖：
 * 两个 domain 不再直接调用对方的方法，而是通过事件间接通信。
 * 事件监听由 team.js（组合层）统一注册。
 */
export function createEventEmitter() {
  const listeners = new Map()

  return {
    on(event, handler) {
      if (!listeners.has(event)) {
        listeners.set(event, new Set())
      }
      listeners.get(event).add(handler)
    },
    off(event, handler) {
      const set = listeners.get(event)
      if (set) {
        set.delete(handler)
        if (set.size === 0) listeners.delete(event)
      }
    },
    emit(event, ...args) {
      const set = listeners.get(event)
      if (set) {
        for (const handler of set) {
          handler(...args)
        }
      }
    },
  }
}
