import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: {
    post: vi.fn().mockResolvedValue({ code: 1 }),
    get: vi.fn().mockResolvedValue({ code: 1, data: {} }),
  },
}))

import { login, register, fetchCurrentUser, logout } from '@/api/auth'
import request from '@/utils/request'

describe('auth API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('login', () => {
    it('sends POST to /api/users/login with payload', async () => {
      const payload = { username: 'testuser', password: 'pass123' }

      await login(payload)

      expect(request.post).toHaveBeenCalledWith('/api/users/login', payload)
    })

    it('returns the response from the server', async () => {
      const mockResponse = { code: 1, data: { token: 'abc' } }
      request.post.mockResolvedValue(mockResponse)

      const result = await login({ username: 'u', password: 'p' })

      expect(result).toEqual(mockResponse)
    })
  })

  describe('register', () => {
    it('sends POST to /api/users/register with payload', async () => {
      const payload = { username: 'newuser', password: 'pass123', email: 'new@example.com' }

      await register(payload)

      expect(request.post).toHaveBeenCalledWith('/api/users/register', payload)
    })

    it('returns the response from the server', async () => {
      const mockResponse = { code: 1, data: { id: 1 } }
      request.post.mockResolvedValue(mockResponse)

      const result = await register({ username: 'u', password: 'p' })

      expect(result).toEqual(mockResponse)
    })
  })

  describe('fetchCurrentUser', () => {
    it('sends GET to /api/users/me', async () => {
      await fetchCurrentUser()

      expect(request.get).toHaveBeenCalledWith('/api/users/me')
    })

    it('returns the response from the server', async () => {
      const mockResponse = { code: 1, data: { id: 1, username: 'test' } }
      request.get.mockResolvedValue(mockResponse)

      const result = await fetchCurrentUser()

      expect(result).toEqual(mockResponse)
    })
  })

  describe('logout', () => {
    it('sends POST to /api/users/logout with no payload', async () => {
      await logout()

      expect(request.post).toHaveBeenCalledWith('/api/users/logout')
    })

    it('returns the response from the server', async () => {
      const mockResponse = { code: 1, data: null }
      request.post.mockResolvedValue(mockResponse)

      const result = await logout()

      expect(result).toEqual(mockResponse)
    })
  })

  it('propagates errors from the request module', async () => {
    const error = new Error('network error')
    request.post.mockRejectedValue(error)

    await expect(login({ username: 'u', password: 'p' })).rejects.toThrow('network error')
  })
})
