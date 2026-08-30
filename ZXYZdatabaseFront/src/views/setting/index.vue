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
// 导航 Tab 从路由 meta 派生（settings 子路由声明 meta.settingTab），
// 新增设置页只需添加子路由即可自动出现 Tab；可见性与路由守卫读取同一权限来源，
// 避免导航与守卫不一致（如非管理员看到「配置管理」Tab 却访问被拒）。
const settingRoutes = computed(() => {
  const layoutRoute = router.options.routes.find((route) => route.name === 'layout')
  const settingRoot = layoutRoute?.children?.find((route) => route.name === 'settingRoot')
  return settingRoot?.children || []
})
const navItems = computed(() =>
  settingRoutes.value
    .filter((route) => route.meta?.settingTab)
    .map((route) => ({
      name: route.name,
      label: route.meta?.label || route.name,
      visible: resolveNavVisibility(route.name, route.meta),
    })),
)
function resolveNavVisibility(routeName, meta = {}) {
  if (routeName === 'permissionCenter' || meta.requiresSystemPermissionCenter) {
    return canShowPermissionCenter.value
  }
  if (meta.requiresAdmin) {
    return canCreateTeam.value
  }
  // 个人设置等无权限门槛的 Tab 始终可见。
  return true
}
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
