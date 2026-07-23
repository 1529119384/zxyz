import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('vue-router', () => ({
  useRoute: vi.fn(),
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn() },
}))
vi.mock('@/api/share', () => ({
  fetchPublicShareInfo: vi.fn(),
  verifySharePassword: vi.fn(),
}))
vi.mock('@/models/share', () => ({
  sanitizeSharePassword: vi.fn((v) =>
    String(v || '')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(0, 4),
  ),
}))
vi.mock('@/utils/error', () => ({
  getErrorMessage: vi.fn((_, fallback) => fallback),
}))

import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { fetchPublicShareInfo, verifySharePassword } from '@/api/share'
import { useShareVisit } from '@/composables/useShareVisit'

describe('useShareVisit', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useRoute.mockReturnValue({ params: { shareKey: 'abc123' }, query: {} })
  })

  it('初始化状态：加载中且无错误', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: true, needPassword: false },
    })

    const { shareInfo, pageLoading, verifying, submitPassword, pageError, canViewContent } =
      useShareVisit()

    // watch(immediate: true) 触发 refreshPage()，同步设置 pageLoading = true
    expect(pageLoading.value).toBe(true)

    // 等待 fetchPublicShareInfo 完成后 pageLoading 恢复为 false
    await vi.waitFor(() => expect(pageLoading.value).toBe(false))

    expect(verifying.value).toBe(false)
    expect(submitPassword.value).toBe('')
    expect(pageError.value).toBe('')
  })

  it('密码验证成功后设置 canViewContent 为 true', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: false, needPassword: true },
    })
    verifySharePassword.mockResolvedValue({})

    const { passByPassword, shareInfo, canViewContent, verifying } = useShareVisit()

    // 等待 refreshPage 的异步操作完成
    await vi.waitFor(() => expect(fetchPublicShareInfo).toHaveBeenCalled())

    expect(canViewContent.value).toBe(false)

    const result = await passByPassword('abcd')

    expect(result).toBe(true)
    expect(verifySharePassword).toHaveBeenCalledWith('abc123', 'abcd')
    expect(canViewContent.value).toBe(true)
    expect(verifying.value).toBe(false)
  })

  it('密码验证失败时返回 false 并显示错误提示', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: false, needPassword: true },
    })
    verifySharePassword.mockRejectedValue(new Error('wrong password'))

    const { passByPassword, canViewContent } = useShareVisit()

    await vi.waitFor(() => expect(fetchPublicShareInfo).toHaveBeenCalled())

    const result = await passByPassword('wrong')

    expect(result).toBe(false)
    expect(canViewContent.value).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('提取码错误')
  })

  it('空密码不发起验证请求', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: false, needPassword: true },
    })

    const { passByPassword } = useShareVisit()

    await vi.waitFor(() => expect(fetchPublicShareInfo).toHaveBeenCalled())

    const result = await passByPassword('')

    expect(result).toBe(false)
    expect(verifySharePassword).not.toHaveBeenCalled()
  })

  it('自动加载分享信息（canViewContent 为 true 时）', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: true, needPassword: false },
    })

    const { shareInfo, canViewContent, pageLoading } = useShareVisit()

    await vi.waitFor(() => expect(fetchPublicShareInfo).toHaveBeenCalled())
    await vi.waitFor(() => expect(pageLoading.value).toBe(false))

    expect(fetchPublicShareInfo).toHaveBeenCalledWith('abc123')
    expect(shareInfo.value).toEqual({ canViewContent: true, needPassword: false })
    expect(canViewContent.value).toBe(true)
  })

  it('shareKey 为空时设置错误信息', async () => {
    useRoute.mockReturnValue({ params: {}, query: {} })

    const { pageError, shareInfo } = useShareVisit()

    await vi.waitFor(() => expect(pageError.value).toBe('分享不存在'))

    expect(shareInfo.value).toBeNull()
  })

  it('加载分享信息失败时显示错误信息', async () => {
    const error = new Error('not found')
    error.data = { statusText: '分享已过期' }
    fetchPublicShareInfo.mockRejectedValue(error)

    const { pageError, shareInfo } = useShareVisit()

    await vi.waitFor(() => expect(pageError.value).toBeTruthy())

    expect(shareInfo.value).toBeNull()
    expect(pageError.value).toBe('分享已过期')
  })

  it('silent 模式下密码验证失败不显示错误提示', async () => {
    fetchPublicShareInfo.mockResolvedValue({
      data: { canViewContent: false, needPassword: true },
    })
    verifySharePassword.mockRejectedValue(new Error('wrong'))

    const { passByPassword } = useShareVisit()

    await vi.waitFor(() => expect(fetchPublicShareInfo).toHaveBeenCalled())

    const result = await passByPassword('wrong', true)

    expect(result).toBe(false)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})
