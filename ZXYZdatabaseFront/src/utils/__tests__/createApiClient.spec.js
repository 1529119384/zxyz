import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('@/utils/auth', () => ({
  clearToken: vi.fn(),
}))

vi.mock('@/utils/sanitizeRedirect', () => ({
  sanitizeRedirectPath: vi.fn((path) => path),
}))

vi.mock('element-plus', () => ({
  ElMessageBox: { alert: vi.fn().mockResolvedValue('confirm') },
}))

// 用 hoisted 共享 spy，才能断言 clearProfile 是否被调用（vi.mock 工厂会被提升）
const { clearProfileMock } = vi.hoisted(() => ({ clearProfileMock: vi.fn() }))

vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(() => ({
    clearProfile: clearProfileMock,
  })),
}))

import { createApiClient } from '@/utils/createApiClient'

describe('createApiClient', () => {
  let server
  let client
  let port

  beforeEach(async () => {
    vi.clearAllMocks()
    // Use a mock HTTP server via axios adapter
    const { default: http } = await import('http')

    server = http.createServer((req, res) => {
      let body = ''
      req.on('data', (chunk) => (body += chunk))
      req.on('end', () => {
        const parsed = body ? JSON.parse(body) : {}
        const url = req.url

        if (url === '/success') {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 1, msg: 'success', data: { id: 1 } }))
        } else if (url === '/business-error') {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4000, msg: '参数错误', data: null }))
        } else if (url === '/auth-failure-code') {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4010, msg: '未登录' }))
        } else if (url === '/auth-failure-http') {
          res.writeHead(401, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4000, msg: '未授权' }))
        } else if (url === '/server-error') {
          res.writeHead(500, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 5000, msg: '服务器异常' }))
        } else if (url === '/service-unavailable') {
          res.writeHead(503, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ error: 'Service Unavailable', status: 503 }))
        } else if (url === '/html-error') {
          res.writeHead(404, { 'Content-Type': 'text/html' })
          res.end('<html>Not Found</html>')
        } else if (url === '/client-error') {
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4000, msg: '参数校验失败' }))
        } else if (url === '/contains-not-login') {
          // 业务文案恰好包含「未登录」子串，但不是认证失败
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4000, msg: '对方未登录，消息发送失败' }))
        } else if (url === '/auth-failure-text') {
          // 无 401/4010，仅靠精确文本兜底判定
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ code: 4000, msg: 'Token expired' }))
        } else {
          res.writeHead(404)
          res.end('Not Found')
        }
      })
    })

    await new Promise((resolve) => server.listen(0, resolve))
    port = server.address().port

    client = createApiClient({
      baseURL: `http://localhost:${port}`,
      timeout: 5000,
      onTokenExpired: 'silent',
    })
  })

  afterEach(() => {
    server?.close()
  })

  it('should return payload when code === 1', async () => {
    const result = await client.get('/success')
    expect(result).toEqual({ code: 1, msg: 'success', data: { id: 1 } })
  })

  it('should reject with BusinessError when code !== 1', async () => {
    await expect(client.get('/business-error')).rejects.toThrow()
  })

  it('should handle auth failure via code 4010', async () => {
    await expect(client.get('/auth-failure-code')).rejects.toThrow()
  })

  it('should handle auth failure via HTTP 401', async () => {
    await expect(client.get('/auth-failure-http')).rejects.toThrow()
  })

  it('should show ElMessageBox then redirect on auth failure in redirect mode', async () => {
    const { ElMessageBox } = await import('element-plus')
    const redirectClient = createApiClient({
      baseURL: `http://localhost:${port}`,
      timeout: 5000,
      onTokenExpired: 'redirect',
    })
    const replaceMock = vi.fn()
    vi.stubGlobal('location', { pathname: '/index', replace: replaceMock })
    await expect(redirectClient.get('/auth-failure-code')).rejects.toThrow()
    expect(clearProfileMock).toHaveBeenCalled()
    expect(ElMessageBox.alert).toHaveBeenCalledWith(
      '登录状态已过期，请重新登录',
      '会话过期',
      expect.objectContaining({ confirmButtonText: '重新登录', type: 'warning' }),
    )
    vi.unstubAllGlobals()
  })

  it('should handle 500 server error', async () => {
    await expect(client.get('/server-error')).rejects.toThrow('服务器异常')
  })

  it('should handle 503 with Spring error format', async () => {
    await expect(client.get('/service-unavailable')).rejects.toThrow('服务暂时不可用')
  })

  it('should handle HTML error page (non-object payload)', async () => {
    await expect(client.get('/html-error')).rejects.toThrow('请求失败')
  })

  it('should handle 4xx with object payload', async () => {
    await expect(client.get('/client-error')).rejects.toThrow('参数校验失败')
  })

  it('silent 实例的认证失败不清空主应用登录态（审计 12-第三节中危项）', async () => {
    await expect(client.get('/auth-failure-code')).rejects.toThrow()
    expect(clearProfileMock).not.toHaveBeenCalled()
  })

  it('redirect 实例仅在精确文本兜底命中时清空登录态', async () => {
    const { ElMessageBox } = await import('element-plus')
    const redirectClient = createApiClient({
      baseURL: `http://localhost:${port}`,
      timeout: 5000,
      onTokenExpired: 'redirect',
    })
    const replaceMock = vi.fn()
    vi.stubGlobal('location', { pathname: '/index', replace: replaceMock })
    await expect(redirectClient.get('/auth-failure-text')).rejects.toThrow()
    expect(clearProfileMock).toHaveBeenCalled()
    expect(ElMessageBox.alert).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('业务文案仅包含「未登录」子串时不得误判为认证失败', async () => {
    const { ElMessageBox } = await import('element-plus')
    const redirectClient = createApiClient({
      baseURL: `http://localhost:${port}`,
      timeout: 5000,
      onTokenExpired: 'redirect',
    })
    await expect(redirectClient.get('/contains-not-login')).rejects.toThrow(
      '对方未登录，消息发送失败',
    )
    expect(clearProfileMock).not.toHaveBeenCalled()
    expect(ElMessageBox.alert).not.toHaveBeenCalled()
  })
})
