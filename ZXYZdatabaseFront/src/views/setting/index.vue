<template>
  <div class="settings-page">
    <header v-once class="settings-header">
      <div>
        <h1>设置</h1>
        <p>按业务域管理账号设置、团队后台、系统运营和权限中心。</p>
      </div>
    </header>

    <el-menu
      class="settings-nav"
      mode="horizontal"
      :default-active="activeRouteName"
      @select="goSection"
    >
      <el-menu-item v-for="item in visibleNavItems" :key="item.name" :index="item.name">
        {{ item.label }}
      </el-menu-item>
    </el-menu>

    <main class="settings-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { useCurrentUserStore } from '@/store/currentUser'

const route = useRoute()
const router = useRouter()
const currentUserStore = useCurrentUserStore()
const { canAccessPermissionCenter } = useTeamManagement()

const canCreateTeam = computed(() => currentUserStore.isAdmin)
const canShowPermissionCenter = computed(
  () => currentUserStore.canReadSystemPermissionCenter || canAccessPermissionCenter.value,
)
const navItems = computed(() => [
  { name: 'accountSettings', label: '个人设置', visible: true },
  { name: 'teamAdminSettings', label: '创建团队', visible: canCreateTeam.value },
  { name: 'systemAdminSettings', label: '系统运营', visible: canCreateTeam.value },
  { name: 'configAdminSettings', label: '配置管理', visible: true },
  { name: 'storageAdminSettings', label: '存储管理', visible: canCreateTeam.value },
  { name: 'permissionCenter', label: '权限管理', visible: canShowPermissionCenter.value },
])
const visibleNavItems = computed(() => navItems.value.filter((item) => item.visible))
const activeRouteName = computed(() => {
  const currentRoute = visibleNavItems.value.find((item) => item.name === route.name)
  return currentRoute?.name || visibleNavItems.value[0]?.name || 'accountSettings'
})

watch(
  [() => route.name, canCreateTeam, canShowPermissionCenter],
  () => {
    if (!route.name) return
    if (visibleNavItems.value.some((item) => item.name === route.name)) {
      return
    }
    router.replace({ name: visibleNavItems.value[0]?.name || 'accountSettings' }).catch((err) => {
      console.warn('Settings nav redirect failed:', err?.message || err)
    })
  },
  { immediate: true },
)

function goSection(routeName) {
  if (route.name === routeName) {
    return
  }
  router.push({
    name: routeName,
    query: routeName === 'permissionCenter' ? route.query : {},
  })
}
</script>

<style src="./style.css"></style>
