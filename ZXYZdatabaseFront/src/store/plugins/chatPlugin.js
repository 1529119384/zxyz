import { watch } from 'vue'

import { useTeamStore } from '@/store/team'

/**
 * Pinia plugin that decouples chatStore from a direct useTeamStore() call.
 *
 * Instead of chatStore importing and calling useTeamStore() at definition time
 * (which creates a hard coupling and initialization-order dependency), this plugin
 * wires the team store dependency after both stores exist.
 *
 * The chat store exposes a _setupTeamBridge(teamStore) method that this plugin
 * calls once, replacing placeholder refs with live reactive state from teamStore.
 */
export function chatBridgePlugin({ store }) {
  if (store.$id !== 'chat') return

  let cleanup = null

  // Wire up the bridge once the team store is first accessed.
  // We watch teamStore.selectedTeamId and pass the whole store to _setupTeamBridge.
  const teamStore = useTeamStore()
  store._setupTeamBridge(teamStore)

  // Keep chatStore.selectedTeamId in sync with teamStore.selectedTeamId.
  // This replaces the former `const selectedTeamId = computed(() => teamStore.selectedTeamId)`.
  cleanup = watch(
    () => teamStore.selectedTeamId,
    (newId) => {
      store._selectedTeamId.value = newId
    },
    { immediate: true },
  )

  // Clean up the watcher if the Pinia instance is destroyed (e.g. SSR, tests).
  store.$pinia.onDispose(() => {
    cleanup?.()
  })
}
