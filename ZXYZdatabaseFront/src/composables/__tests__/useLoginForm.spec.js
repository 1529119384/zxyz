import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

import { useLoginForm } from '@/composables/useLoginForm'
import { useCurrentUserStore } from '@/store/currentUser'
import { handleBusinessError } from '@/utils/error'

describe('useLoginForm', () => {
  let mockStore
  const validFormRef = {
    validate: vi.fn().mockResolvedValue(true),
  }
  const invalidFormRef = {
    validate: vi.fn().mockRejectedValue(new Error('invalid')),
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    mockStore = {
      login: vi.fn().mockResolvedValue({ profile: { id: 1 } }),
    }
    useCurrentUserStore.mockReturnValue(mockStore)
  })

  it('initializes with empty form fields', () => {
    const { loginForm, loggingIn } = useLoginForm()

    expect(loginForm.username).toBe('')
    expect(loginForm.password).toBe('')
    expect(loginForm.rememberMe).toBe(false)
    expect(loggingIn.value).toBe(false)
  })

  it('has required validation rules for username and password', () => {
    const { loginRules } = useLoginForm()

    expect(loginRules.username[0].required).toBe(true)
    expect(loginRules.password[0].required).toBe(true)
    expect(loginRules.username[0].trigger).toBe('blur')
    expect(loginRules.password[0].trigger).toBe('blur')
  })

  it('submitLogin calls store.login with trimmed username', async () => {
    const { loginForm, loginFormRef, submitLogin } = useLoginForm()

    loginFormRef.value = validFormRef
    loginForm.username = '  testuser  '
    loginForm.password = 'pass123'

    await submitLogin()

    expect(mockStore.login).toHaveBeenCalledWith({
      username: 'testuser',
      password: 'pass123',
      rememberMe: false,
    })
  })

  it('submitLogin returns null when form validation fails', async () => {
    const { loginFormRef, submitLogin } = useLoginForm()

    loginFormRef.value = invalidFormRef

    const result = await submitLogin()

    expect(result).toBeNull()
    expect(mockStore.login).not.toHaveBeenCalled()
  })

  it('submitLogin returns null when loginFormRef is undefined', async () => {
    const { submitLogin } = useLoginForm()

    const result = await submitLogin()

    expect(result).toBeNull()
    expect(mockStore.login).not.toHaveBeenCalled()
  })

  it('submitLogin prevents double-submit via loggingIn flag', async () => {
    let resolveLogin
    mockStore.login.mockReturnValue(
      new Promise((resolve) => {
        resolveLogin = resolve
      }),
    )

    const { loginForm, loginFormRef, loggingIn, submitLogin } = useLoginForm()
    loginFormRef.value = validFormRef
    loginForm.username = 'user'
    loginForm.password = 'pass'

    const firstCall = submitLogin()
    // Flush all microtasks: the validate().then().catch() chain and submitLogin's continuation.
    await new Promise((r) => setTimeout(r, 0))

    expect(loggingIn.value).toBe(true)

    // second call while first is still pending
    const secondCall = submitLogin()
    await nextTick()

    expect(mockStore.login).toHaveBeenCalledTimes(1)

    resolveLogin({ profile: { id: 1 } })
    await firstCall
    await secondCall
  })

  it('submitLogin handles errors with handleBusinessError', async () => {
    const error = new Error('login failed')
    mockStore.login.mockRejectedValue(error)

    const { loginForm, loginFormRef, submitLogin } = useLoginForm()
    loginFormRef.value = validFormRef
    loginForm.username = 'user'
    loginForm.password = 'pass'

    const result = await submitLogin()

    expect(handleBusinessError).toHaveBeenCalledWith(error, '登录失败，请稍后重试')
    expect(result).toBeNull()
  })

  it('submitLogin resets loggingIn flag after error', async () => {
    mockStore.login.mockRejectedValue(new Error('fail'))

    const { loginForm, loginFormRef, loggingIn, submitLogin } = useLoginForm()
    loginFormRef.value = validFormRef
    loginForm.username = 'user'
    loginForm.password = 'pass'

    await submitLogin()

    expect(loggingIn.value).toBe(false)
  })

  it('handleKeydown calls submitLogin on Enter key', async () => {
    const { loginForm, loginFormRef, handleKeydown } = useLoginForm()
    loginFormRef.value = validFormRef
    loginForm.username = 'user'
    loginForm.password = 'pass'

    await handleKeydown({ key: 'Enter' })

    expect(mockStore.login).toHaveBeenCalled()
  })

  it('handleKeydown does nothing for non-Enter keys', async () => {
    const { handleKeydown } = useLoginForm()

    const result = await handleKeydown({ key: 'Escape' })

    expect(result).toBeNull()
    expect(mockStore.login).not.toHaveBeenCalled()
  })

  it('submitLogin returns the store result on success', async () => {
    const expected = { profile: { id: 1, name: 'Test' } }
    mockStore.login.mockResolvedValue(expected)

    const { loginForm, loginFormRef, submitLogin } = useLoginForm()
    loginFormRef.value = validFormRef
    loginForm.username = 'user'
    loginForm.password = 'pass'

    const result = await submitLogin()

    expect(result).toEqual(expected)
  })
})
