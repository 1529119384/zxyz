<template>
  <div class="common-layout">
    <el-container>
      <el-header height="50px" class="app-header">
        <div class="header-left">
          <img src="@/assets/images/logo2.png" alt="logo" class="logo" />
        </div>
        <div class="header-right">
          <el-input
            v-if="showFileSearch"
            v-model="searchKeyword"
            class="search-input"
            :placeholder="searchPlaceholder"
            :suffix-icon="Search"
          />
          <el-dropdown
            placement="bottom"
            trigger="click"
            :show-arrow="false"
            @command="handleDropdownCommand"
          >
            <el-avatar :size="40" :src="avatarSrc" class="avatar" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :command="'profile'" disabled>{{ displayName }}</el-dropdown-item>
                <el-dropdown-item :command="'logout'">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <LogoutDialog v-model="logoutDialogVisible" @confirm="logout" />

      <el-container>
        <el-aside width="200px">
          <el-menu :default-active="activeMenuIndex" router>
            <el-menu-item index="/index">
              <el-icon><HomeFilled /></el-icon>
              <span>个人空间</span>
            </el-menu-item>
            <el-menu-item v-if="teamStore.teams.length" index="/team-space">
              <el-icon><OfficeBuilding /></el-icon>
              <span>团队空间</span>
            </el-menu-item>
            <el-menu-item index="/my-share">
              <el-icon><Link /></el-icon>
              <span>链接分享</span>
            </el-menu-item>
            <el-menu-item index="/chat">
              <el-icon><ChatDotRound /></el-icon>
              <el-badge
                :value="chatMenuUnreadCount"
                :hidden="chatMenuUnreadCount <= 0"
                class="menu-badge"
              >
                <span>聊天会话</span>
              </el-badge>
              <span class="ws-status" :class="{ connected: chatStore.connected }">{{
                wsStatusText
              }}</span>
            </el-menu-item>
            <el-menu-item v-if="showRecycleBin" index="/recycle-bin">
              <el-icon><Delete /></el-icon>
              <span>回收站</span>
            </el-menu-item>
            <el-menu-item index="/setting">
              <el-icon><Setting /></el-icon>
              <span>设置</span>
            </el-menu-item>
          </el-menu>
          <TeamSwitcher />
        </el-aside>
        <el-main>
          <router-view v-slot="{ Component }">
            <component :is="Component" :key="routeViewKey" />
          </router-view>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import {
  ChatDotRound,
  Delete,
  HomeFilled,
  Link,
  OfficeBuilding,
  Search,
  Setting,
} from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import LogoutDialog from '@/components/LogoutDialog.vue'
import TeamSwitcher from '@/components/TeamSwitcher.vue'
import { useImWorkspace } from '@/composables/useImWorkspace'
import { defaultAvatarUrl } from '@/config/defaultAssets'
import { formatWsStatus } from '@/models/imPresentation'
import { useChatStore } from '@/store/chat'
import { useCurrentUserStore } from '@/store/currentUser'
import { useSessionStore } from '@/store/session'
import { useTeamStore } from '@/store/team'
import { logout as logoutApi } from '@/api/auth'
import { logger } from '@/utils/logger'
import { getRouteQueryText, withRouteQueryText } from '@/utils/routeQuery'

const route = useRoute()
const router = useRouter()
const currentUserStore = useCurrentUserStore()
const teamStore = useTeamStore()
const chatStore = useChatStore()
const imWorkspace = useImWorkspace({ teamStore, chatStore })
const sessionStore = useSessionStore()
const logoutDialogVisible = ref(false)

const showFileSearch = computed(() => Boolean(route.meta?.showSearch))
const showRecycleBin = computed(() => currentUserStore.canReadTrash)
const avatarSrc = computed(() => currentUserStore.profile?.avatar || defaultAvatarUrl)
const displayName = computed(
  () => currentUserStore.profile?.name || currentUserStore.profile?.username || '当前用户',
)
const searchPlaceholder = computed(() => '搜索文件')
const wsStatusText = computed(() => formatWsStatus(chatStore.status))
const chatMenuUnreadCount = computed(() => Number(chatStore.totalConversationUnreadCount || 0))
const activeMenuIndex = computed(() => {
  if (route.name === 'joinTeam') {
    return '/chat'
  }
  if (route.matched.some((record) => record.name === 'settingRoot' || record.name === 'setting')) {
    return '/setting'
  }
  if (route.name === 'collaboration') {
    return '/chat'
  }
  if (route.name === 'projectSpace') {
    return '/team-space'
  }
  return route.path || '/index'
})
const searchKeyword = computed({
  get: () => getRouteQueryText(route.query.search),
  set: (value) => {
    const currentSearch = getRouteQueryText(route.query.search)
    if (value === currentSearch) {
      return
    }

    // 搜索词每次输入都会变化，使用 replace 避免为每个字符新增浏览器历史记录。
    router
      .replace({
        name: route.name || 'index',
        params: route.params,
        query: withRouteQueryText(route.query, 'search', value),
      })
      .catch((error) => {
        logger.error('同步文件搜索参数失败:', error)
      })
  },
})
const routeViewKey = computed(() => {
  const navQuery = Object.entries(route.query)
    .filter(([key]) => key !== 'search' && key !== 'path')
    .sort(([leftKey], [rightKey]) => leftKey.localeCompare(rightKey))

  if (navQuery.length === 0) {
    return route.path
  }

  const queryKey = navQuery.map(([key, value]) => `${key}=${formatQueryKeyValue(value)}`).join('&')

  return `${route.path}?${queryKey}`
})

onMounted(() => {
  chatStore.ensureConnected()
  imWorkspace.loadChatNavigation().catch((err) => console.warn('Operation failed:', err))
})

onUnmounted(() => {
  chatStore.disconnect()
})

function handleDropdownCommand(command) {
  if (command === 'logout') {
    logoutDialogVisible.value = true
  }
}

async function logout() {
  chatStore.disconnect()
  try {
    await logoutApi()
  } catch (_) {
    // 即使后端清理失败，也要清除本地状态
  }
  sessionStore.resetSessionBootstrap()
  currentUserStore.clearAll()
  router.replace({
    name: 'login',
    query: {
      redirect: route.fullPath,
    },
  })
}

function formatQueryKeyValue(value) {
  if (Array.isArray(value)) {
    return value.map((item) => item ?? '').join(',')
  }

  return value ?? ''
}
</script>

<style scoped>
.common-layout,
.common-layout :deep(.el-container) {
  height: 100%;
  user-select: none;
  -webkit-user-select: none;
}

.common-layout :deep(.el-main) {
  height: 100%;
}

.common-layout :deep(.el-aside) {
  display: flex;
  flex-direction: column;
}

.common-layout :deep(.el-menu) {
  flex: 1;
}

.common-layout :deep(input),
.common-layout :deep(textarea),
.common-layout :deep([contenteditable='true']),
.common-layout :deep(.el-input__inner),
.common-layout :deep(.el-textarea__inner) {
  user-select: text;
  -webkit-user-select: text;
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.search-input {
  width: 260px;
}

.search-input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.88);
  border-radius: 999px;
  box-shadow: none;
}

.avatar {
  cursor: pointer;
}

.logo {
  height: 48px;
  width: auto;
  display: block;
}

.menu-badge {
  display: inline-flex;
  align-items: center;
}

.menu-badge :deep(.el-badge__content) {
  transform: translate(8px, 2px);
}

.ws-status {
  margin-left: auto;
  max-width: 72px;
  overflow: hidden;
  color: #909399;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-status.connected {
  color: #67c23a;
}
</style>
