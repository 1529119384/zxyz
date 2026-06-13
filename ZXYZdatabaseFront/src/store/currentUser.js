import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useLocalStorage } from '@vueuse/core'

import { fetchCurrentUser, login as loginByPassword } from '@/api/auth'
import { clearToken } from '@/utils/auth'

const DISPLAY_USER_KEY = 'displayUser'
const SYSTEM_ADMIN_ROLE = 'system_admin'
const SYSTEM_PERMISSIONS = {
  fileWrite: 'file:write',
  fileDelete: 'file:delete',
  trashRead: 'trash:read',
  systemRoleManage: 'system:role:manage',
  systemPermissionRead: 'system:permission:read',
  systemAuditRead: 'system:audit:read',
}

function normalizeCurrentUser(data = {}) {
  return {
    id: data.id ?? null,
    username: data.username || '',
    name: data.name || '',
    avatar: data.avatar || '',
    email: data.email || '',
    phone: data.phone || '',
    emailVerified: Boolean(data.emailVerified),
    phoneVerified: Boolean(data.phoneVerified),
    defaultTeamId: data.defaultTeamId ?? null,
    roles: Array.isArray(data.roles) ? data.roles : [],
    permissions: Array.isArray(data.permissions) ? data.permissions : [],
  }
}

// 仅返回显示层字段，不含 email、phone、roles、permissions 等敏感数据
function normalizeDisplayUser(data = {}) {
  return {
    id: data.id ?? null,
    username: data.username || '',
    name: data.name || '',
    avatar: data.avatar || '',
    defaultTeamId: data.defaultTeamId ?? null,
    emailVerified: Boolean(data.emailVerified),
    phoneVerified: Boolean(data.phoneVerified),
  }
}

export const useCurrentUserStore = defineStore('currentUser', () => {
  // 清除旧版遗留数据
  localStorage.removeItem('currentUser')

  const displayUserRef = useLocalStorage(DISPLAY_USER_KEY, null, {
    serializer: {
      read: (raw) => {
        if (raw == null) return null
        try {
          return JSON.parse(raw)
        } catch {
          return null
        }
      },
      write: (v) => (v == null ? '' : JSON.stringify(v)),
    },
  })
  const profile = ref(displayUserRef.value)
  const loading = ref(false)

  const roles = computed(() => (Array.isArray(profile.value?.roles) ? profile.value.roles : []))
  const permissions = computed(() =>
    Array.isArray(profile.value?.permissions) ? profile.value.permissions : [],
  )
  const isAdmin = computed(() => roles.value.includes(SYSTEM_ADMIN_ROLE))
  const canWrite = computed(() => permissions.value.includes(SYSTEM_PERMISSIONS.fileWrite))
  const canDelete = computed(() => permissions.value.includes(SYSTEM_PERMISSIONS.fileDelete))
  const canReadTrash = computed(() => permissions.value.includes(SYSTEM_PERMISSIONS.trashRead))
  const canManageSystemPermissions = computed(
    () =>
      permissions.value.includes(SYSTEM_PERMISSIONS.systemRoleManage) ||
      permissions.value.includes(SYSTEM_PERMISSIONS.systemPermissionRead),
  )
  const canReadSystemPermissionCenter = computed(
    () =>
      permissions.value.includes(SYSTEM_PERMISSIONS.systemRoleManage) ||
      permissions.value.includes(SYSTEM_PERMISSIONS.systemPermissionRead) ||
      permissions.value.includes(SYSTEM_PERMISSIONS.systemAuditRead),
  )

  function setProfile(data) {
    const nextProfile = data ? normalizeCurrentUser(data) : null
    profile.value = nextProfile
    displayUserRef.value = nextProfile ? normalizeDisplayUser(nextProfile) : null
  }

  function clearProfile() {
    profile.value = null
    displayUserRef.value = null
  }

  async function loadProfile() {
    if (loading.value) {
      return profile.value
    }

    loading.value = true

    try {
      const response = await fetchCurrentUser()
      setProfile(response?.data || null)
      return profile.value
    } catch (error) {
      clearProfile()
      throw error
    } finally {
      loading.value = false
    }
  }

  async function ensureProfileLoaded(options = {}) {
    const { force = false } = options

    // localStorage 仅缓存显示层数据，不含 roles/permissions，需检查 roles 确保完整 profile 已加载
    if (!force && profile.value?.roles) {
      return profile.value
    }

    return loadProfile()
  }

  async function login(payload) {
    try {
      await loginByPassword(payload)
      // 登录成功后立即拉取 profile，保证路由守卫和页面读取到的是同一份最新用户状态。
      await loadProfile()

      return {
        profile: profile.value,
      }
    } catch (error) {
      clearAll()
      throw error
    }
  }

  function clearAll() {
    clearToken()
    clearProfile()
  }

  function hasSystemPermission(code) {
    return permissions.value.includes(code)
  }

  function hasAnySystemPermission(codes = []) {
    return Array.isArray(codes) && codes.some((code) => hasSystemPermission(code))
  }

  return {
    profile,
    roles,
    permissions,
    isAdmin,
    canWrite,
    canDelete,
    canReadTrash,
    canManageSystemPermissions,
    canReadSystemPermissionCenter,
    hasSystemPermission,
    hasAnySystemPermission,
    loading,
    setProfile,
    clearProfile,
    login,
    loadProfile,
    ensureProfileLoaded,
    clearAll,
  }
})
