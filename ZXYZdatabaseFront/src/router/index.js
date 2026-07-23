import { createRouter, createWebHistory } from 'vue-router'

import { sanitizeRedirectPath } from '@/utils/sanitizeRedirect'
import { useChatStore } from '@/store/chat'
import { useCurrentUserStore } from '@/store/currentUser'
import { useSessionStore } from '@/store/session'
import Layout from '@/views/layout/index.vue'
import Index from '@/views/index/index.vue'
import { requirePermissionCenter, requireSystemAdminRole } from '@/router/guards/permission'
import { handleBusinessError } from '@/utils/error'

const Login = () => import('@/views/login/index.vue')
const Register = () => import('@/views/register/index.vue')
const MyShare = () => import('@/views/my-share/index.vue')
const RecycleBin = () => import('@/views/recycle-bin/index.vue')
const Setting = () => import('@/views/setting/index.vue')
const AccountSettings = () => import('@/views/setting/AccountSettings.vue')
const TeamAdminSettings = () => import('@/views/setting/TeamAdmin.vue')
const SystemAdminSettings = () => import('@/views/setting/SystemAdmin.vue')
const ConfigAdminSettings = () => import('@/views/setting/ConfigAdmin.vue')
const StorageAdminSettings = () => import('@/views/setting/StorageAdmin.vue')
const PermissionCenter = () => import('@/views/permission/index.vue')
const ChatHome = () => import('@/views/chat/index.vue')
const Projects = () => import('@/views/projects/index.vue')
const JoinTeam = () => import('@/views/join/team.vue')
const SharePublic = () => import('@/views/share/index.vue')
const NoTeam = () => import('@/views/no-team/index.vue')

const publicRouteNames = new Set(['login', 'register', 'sharePublic'])
const legacySettingTabRouteNames = Object.freeze({
  profile: 'accountSettings',
  createTeam: 'teamAdminSettings',
  systemAdmin: 'systemAdminSettings',
  permissions: 'permissionCenter',
})

function stripLegacyTabQuery(query = {}) {
  const nextQuery = { ...query }
  delete nextQuery.tab
  return nextQuery
}

function redirectLegacySettingTab(to) {
  const tab = typeof to.query.tab === 'string' ? to.query.tab : 'profile'
  const routeName = legacySettingTabRouteNames[tab] || 'accountSettings'
  const query = stripLegacyTabQuery(to.query)
  return routeName === 'permissionCenter' ? { name: routeName, query } : { name: routeName }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'layout',
      component: Layout,
      redirect: '/index',
      children: [
        { path: 'index', name: 'index', component: Index, meta: { showSearch: true } },
        {
          path: 'team-space',
          name: 'teamSpace',
          component: Index,
          meta: { showSearch: true, space: 'team' },
        },
        { path: 'my-share', name: 'myShare', component: MyShare },
        { path: 'projects', name: 'projects', component: Projects },
        {
          path: 'projects/:projectId/space',
          name: 'projectSpace',
          component: Index,
          meta: { showSearch: true, space: 'project' },
        },
        { path: 'chat', name: 'chatHome', component: ChatHome },
        { path: 'join/team/:token', name: 'joinTeam', component: JoinTeam },
        { path: 'recycle-bin', name: 'recycleBin', component: RecycleBin },
        {
          path: 'setting',
          alias: 'settings',
          name: 'settingRoot',
          component: Setting,
          children: [
            { path: '', name: 'setting', redirect: redirectLegacySettingTab },
            { path: 'account', name: 'accountSettings', component: AccountSettings },
            {
              path: 'team-admin',
              name: 'teamAdminSettings',
              component: TeamAdminSettings,
              beforeEnter: requireSystemAdminRole(),
            },
            {
              path: 'system-admin',
              name: 'systemAdminSettings',
              component: SystemAdminSettings,
              beforeEnter: requireSystemAdminRole(),
            },
            {
              path: 'config-admin',
              name: 'configAdminSettings',
              component: ConfigAdminSettings,
              beforeEnter: requireSystemAdminRole(),
            },
            {
              path: 'storage-admin',
              name: 'storageAdminSettings',
              component: StorageAdminSettings,
              beforeEnter: requireSystemAdminRole(),
            },
            {
              path: 'permissions',
              name: 'permissionCenter',
              component: PermissionCenter,
              beforeEnter: requirePermissionCenter,
            },
          ],
        },
      ],
    },
    { path: '/login', name: 'login', component: Login },
    { path: '/register', name: 'register', component: Register },
    { path: '/no-team', name: 'noTeam', component: NoTeam },
    { path: '/s/:shareKey', name: 'sharePublic', component: SharePublic },
  ],
})

router.afterEach((to, from) => {
  // 离开聊天页面时清理 read-sync 定时器，避免在非聊天页面触发无意义的已读同步请求。
  if (from.name === 'chatHome' && to.name !== 'chatHome') {
    useChatStore().clearReadSyncTimers()
  }
})

router.beforeEach(async (to, from, next) => {
  if (publicRouteNames.has(to.name)) {
    next()
    return
  }

  const currentUserStore = useCurrentUserStore()
  if (!currentUserStore.profile) {
    next({ name: 'login', query: { redirect: sanitizeRedirectPath(to.fullPath) } })
    return
  }

  const sessionStore = useSessionStore()

  try {
    const session = await sessionStore.ensureSessionReady()
    if (session.shouldEnterNoTeam && to.name !== 'noTeam') {
      next({ name: 'noTeam' })
      return
    }
    if (session.hasTeams && to.name === 'noTeam') {
      next({ name: 'index' })
      return
    }
  } catch (error) {
    // 判断是否为认证失败（401/403）
    const status = error?.response?.status
    if (status === 401 || status === 403) {
      // Token 无效，清除登录状态
      handleBusinessError(error, '登录状态已过期，请重新登录')
      sessionStore.resetSessionBootstrap()
      currentUserStore.clearAll()
      next({ name: 'login', query: { redirect: sanitizeRedirectPath(to.fullPath) } })
      return
    }
    // 网络错误等非认证错误，仍跳转登录页避免在异常状态下访问页面
    handleBusinessError(error, '加载登录状态失败，请稍后重试')
    next({ name: 'login', query: { redirect: sanitizeRedirectPath(to.fullPath) } })
    return
  }

  next()
})

export default router
