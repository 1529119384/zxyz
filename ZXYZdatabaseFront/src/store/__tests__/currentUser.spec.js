import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
}))

vi.mock('@/utils/auth', () => ({
  clearToken: vi.fn(),
}))

import { useCurrentUserStore } from '@/store/currentUser'
import { fetchCurrentUser, login as loginByPassword } from '@/api/auth'
import { clearToken } from '@/utils/auth'

describe('useCurrentUserStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  const fullProfileData = {
    id: 1,
    username: 'testuser',
    name: 'Test User',
    avatar: 'https://example.com/avatar.png',
    email: 'test@example.com',
    phone: '13800138000',
    emailVerified: true,
    phoneVerified: false,
    defaultTeamId: 10,
    roles: ['system_admin', 'team_member'],
    permissions: ['file:write', 'file:delete', 'trash:read'],
  }

  describe('profile initialization', () => {
    it('initializes with null profile when localStorage is empty', () => {
      const store = useCurrentUserStore()
      expect(store.profile).toBeNull()
    })
  })

  describe('setProfile', () => {
    it('normalizes and sets the profile', () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)

      expect(store.profile).toEqual({
        id: 1,
        username: 'testuser',
        name: 'Test User',
        avatar: 'https://example.com/avatar.png',
        email: 'test@example.com',
        phone: '13800138000',
        emailVerified: true,
        phoneVerified: false,
        defaultTeamId: 10,
        roles: ['system_admin', 'team_member'],
        permissions: ['file:write', 'file:delete', 'trash:read'],
      })
    })

    it('handles missing fields with defaults', () => {
      const store = useCurrentUserStore()

      store.setProfile({ id: 2 })

      expect(store.profile.username).toBe('')
      expect(store.profile.name).toBe('')
      expect(store.profile.avatar).toBe('')
      expect(store.profile.email).toBe('')
      expect(store.profile.phone).toBe('')
      expect(store.profile.emailVerified).toBe(false)
      expect(store.profile.phoneVerified).toBe(false)
      expect(store.profile.roles).toEqual([])
      expect(store.profile.permissions).toEqual([])
    })

    it('sets null profile when null data is passed', () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)
      store.setProfile(null)

      expect(store.profile).toBeNull()
    })

    it('persists display user to localStorage', async () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)
      await nextTick()

      const stored = JSON.parse(localStorage.getItem('displayUser'))
      expect(stored).toMatchObject({
        id: 1,
        username: 'testuser',
        name: 'Test User',
        avatar: 'https://example.com/avatar.png',
      })
      // display user should NOT contain sensitive fields
      expect(stored.email).toBeUndefined()
      expect(stored.phone).toBeUndefined()
      expect(stored.roles).toBeUndefined()
      expect(stored.permissions).toBeUndefined()
    })
  })

  describe('clearProfile', () => {
    it('clears profile and localStorage', () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)
      store.clearProfile()

      expect(store.profile).toBeNull()
      expect(localStorage.getItem('displayUser')).toBeNull()
    })

    it('cleans up legacy currentUser key on store initialization', () => {
      localStorage.setItem('currentUser', 'legacy-data')
      const _store = useCurrentUserStore()

      expect(localStorage.getItem('currentUser')).toBeNull()
    })
  })

  describe('computed role/permission checks', () => {
    it('isAdmin returns true when user has system_admin role', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, roles: ['system_admin'] })
      expect(store.isAdmin).toBe(true)
    })

    it('isAdmin returns false when user lacks system_admin role', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, roles: ['team_member'] })
      expect(store.isAdmin).toBe(false)
    })

    it('canWrite checks file:write permission', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['file:write'] })
      expect(store.canWrite).toBe(true)

      store.setProfile({ ...fullProfileData, permissions: ['file:delete'] })
      expect(store.canWrite).toBe(false)
    })

    it('canDelete checks file:delete permission', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['file:delete'] })
      expect(store.canDelete).toBe(true)

      store.setProfile({ ...fullProfileData, permissions: ['file:write'] })
      expect(store.canDelete).toBe(false)
    })

    it('canReadTrash checks trash:read permission', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['trash:read'] })
      expect(store.canReadTrash).toBe(true)
    })

    it('canManageSystemPermission checks system:role:manage or system:permission:read', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['system:role:manage'] })
      expect(store.canManageSystemPermissions).toBe(true)

      store.setProfile({ ...fullProfileData, permissions: ['system:permission:read'] })
      expect(store.canManageSystemPermissions).toBe(true)

      store.setProfile({ ...fullProfileData, permissions: [] })
      expect(store.canManageSystemPermissions).toBe(false)
    })

    it('handles null profile gracefully for all computed', () => {
      const store = useCurrentUserStore()

      expect(store.isAdmin).toBe(false)
      expect(store.canWrite).toBe(false)
      expect(store.canDelete).toBe(false)
      expect(store.canReadTrash).toBe(false)
      expect(store.canManageSystemPermissions).toBe(false)
    })
  })

  describe('login', () => {
    it('calls loginByPassword API then loadProfile', async () => {
      loginByPassword.mockResolvedValue({ code: 1 })
      fetchCurrentUser.mockResolvedValue({
        data: fullProfileData,
      })

      const store = useCurrentUserStore()
      const result = await store.login({ username: 'testuser', password: 'pass123' })

      expect(loginByPassword).toHaveBeenCalledWith({ username: 'testuser', password: 'pass123' })
      expect(fetchCurrentUser).toHaveBeenCalled()
      expect(result.profile).toMatchObject({ id: 1, username: 'testuser' })
    })

    it('calls clearAll on login failure', async () => {
      const error = new Error('login failed')
      loginByPassword.mockRejectedValue(error)

      const store = useCurrentUserStore()

      await expect(store.login({ username: 'bad', password: 'bad' })).rejects.toThrow(
        'login failed',
      )

      expect(store.profile).toBeNull()
      expect(clearToken).toHaveBeenCalled()
    })
  })

  describe('clearAll', () => {
    it('clears both token and profile', () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)
      store.clearAll()

      expect(clearToken).toHaveBeenCalled()
      expect(store.profile).toBeNull()
      expect(localStorage.getItem('displayUser')).toBeNull()
    })
  })

  describe('loadProfile', () => {
    it('fetches and sets profile on success', async () => {
      fetchCurrentUser.mockResolvedValue({ data: fullProfileData })

      const store = useCurrentUserStore()
      const result = await store.loadProfile()

      expect(fetchCurrentUser).toHaveBeenCalled()
      expect(result).toMatchObject({ id: 1, username: 'testuser' })
    })

    it('clears profile and throws on failure', async () => {
      const error = new Error('network error')
      fetchCurrentUser.mockRejectedValue(error)

      const store = useCurrentUserStore()
      store.setProfile(fullProfileData)

      await expect(store.loadProfile()).rejects.toThrow('network error')
      expect(store.profile).toBeNull()
    })

    it('skips loading if already loading', async () => {
      fetchCurrentUser.mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ data: fullProfileData }), 100)),
      )

      const store = useCurrentUserStore()

      const p1 = store.loadProfile()
      const _p2 = store.loadProfile()

      await p1

      // fetchCurrentUser should only be called once
      expect(fetchCurrentUser).toHaveBeenCalledTimes(1)
    })

    it('应在并发去重时把在途 Promise 返回给第二个调用方，而不是同步旧值（J-6）', async () => {
      let resolveFetch
      fetchCurrentUser.mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveFetch = () => resolve({ data: fullProfileData })
          }),
      )

      const store = useCurrentUserStore()
      // store 初始 profile 为 null：缺陷形态下第二个调用方拿到的就是 null（同步值），
      // 会被 session 快照误判成「无团队」导向 /no-team。
      const p1 = store.loadProfile()
      const p2 = store.loadProfile()
      expect(fetchCurrentUser).toHaveBeenCalledTimes(1)

      resolveFetch()
      const [result1, result2] = await Promise.all([p1, p2])

      // 两个调用方必须拿到同一份完整 profile
      expect(result2).toMatchObject({ id: 1, username: 'testuser' })
      expect(result2).toEqual(result1)
    })

    it('应在请求失败后清理 pending 状态，后续调用能重新发起请求（J-6）', async () => {
      fetchCurrentUser.mockRejectedValueOnce(new Error('network error'))
      fetchCurrentUser.mockResolvedValueOnce({ data: fullProfileData })

      const store = useCurrentUserStore()
      await expect(store.loadProfile()).rejects.toThrow('network error')

      // 失败后 pending 必须被清空：下一次调用重新发请求（缺陷形态下 loading 可能残留）
      const result = await store.loadProfile()
      expect(fetchCurrentUser).toHaveBeenCalledTimes(2)
      expect(result).toMatchObject({ id: 1 })
    })
  })

  describe('hasSystemPermission / hasAnySystemPermission', () => {
    it('hasSystemPermission returns true for existing permission', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['file:write'] })
      expect(store.hasSystemPermission('file:write')).toBe(true)
    })

    it('hasSystemPermission returns false for missing permission', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: [] })
      expect(store.hasSystemPermission('file:write')).toBe(false)
    })

    it('hasAnySystemPermission returns true if any code matches', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['file:write'] })
      expect(store.hasAnySystemPermission(['file:delete', 'file:write'])).toBe(true)
    })

    it('hasAnySystemPermission returns false if no codes match', () => {
      const store = useCurrentUserStore()

      store.setProfile({ ...fullProfileData, permissions: ['file:write'] })
      expect(store.hasAnySystemPermission(['file:delete', 'trash:read'])).toBe(false)
    })

    it('hasAnySystemPermission handles non-array input', () => {
      const store = useCurrentUserStore()

      store.setProfile(fullProfileData)
      expect(store.hasAnySystemPermission()).toBe(false)
      expect(store.hasAnySystemPermission(null)).toBe(false)
    })
  })
})
