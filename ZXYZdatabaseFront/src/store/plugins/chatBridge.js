import { useTeamStore } from '@/store/team'

/**
 * Pinia plugin that bridges chatStore -> teamStore.
 *
 * chatStore 定义时不再 import useTeamStore，而是暴露 _setTeamBridge(teamStore) 方法。
 * 本插件在 chatStore 首次实例化时自动调用该方法注入团队依赖，
 * 解除 chat.js 对 team.js 的模块级耦合。
 *
 * 用法：在 main.js 中 `createPinia().use(chatBridgePlugin)` 注册即可。
 */
export function chatBridgePlugin({ store }) {
  if (store.$id !== 'chat') return

  const teamStore = useTeamStore()
  store._setTeamBridge(teamStore)
}
