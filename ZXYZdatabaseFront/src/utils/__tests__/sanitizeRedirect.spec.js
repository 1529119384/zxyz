import { describe, it, expect } from 'vitest'

import { sanitizeRedirectPath } from '@/utils/sanitizeRedirect'

describe('sanitizeRedirectPath', () => {
  const fallback = '/fallback'

  it('should return fallback for non-string input', () => {
    expect(sanitizeRedirectPath(null)).toBe('/index')
    expect(sanitizeRedirectPath(undefined)).toBe('/index')
    expect(sanitizeRedirectPath(123)).toBe('/index')
    expect(sanitizeRedirectPath(null, fallback)).toBe(fallback)
  })

  it('should return fallback for path not starting with /', () => {
    expect(sanitizeRedirectPath('relative-path', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('http://evil.com', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('', fallback)).toBe(fallback)
  })

  it('should allow normal paths', () => {
    expect(sanitizeRedirectPath('/index')).toBe('/index')
    expect(sanitizeRedirectPath('/folder?path=/docs')).toBe('/folder?path=/docs')
    expect(sanitizeRedirectPath('/setting/config-admin')).toBe('/setting/config-admin')
    expect(sanitizeRedirectPath('/search?q=hello&page=1')).toBe('/search?q=hello&page=1')
  })

  it('should block protocol-relative URLs', () => {
    expect(sanitizeRedirectPath('//evil.com', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('//evil.com/path', fallback)).toBe(fallback)
  })

  it('should block :// protocol injection', () => {
    expect(sanitizeRedirectPath('/path?url=http://evil.com', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('/path?url=https://evil.com', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('/path?url=ftp://evil.com', fallback)).toBe(fallback)
  })

  it('should block javascript: protocol', () => {
    expect(sanitizeRedirectPath('/path?x=javascript:alert(1)', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('/path?x=JavaScript:void(0)', fallback)).toBe(fallback)
  })

  it('should block data: protocol', () => {
    expect(sanitizeRedirectPath('/path?x=data:text/html', fallback)).toBe(fallback)
  })

  it('should block vbscript: protocol', () => {
    expect(sanitizeRedirectPath('/path?x=vbscript:MsgBox', fallback)).toBe(fallback)
  })

  it('should block URL-encoded bypasses', () => {
    // %2F%2F = //
    expect(sanitizeRedirectPath('/%2F%2Fevil.com', fallback)).toBe(fallback)
    // %3A%2F%2F = ://
    expect(sanitizeRedirectPath('/path%3A%2F%2Fevil.com', fallback)).toBe(fallback)
    // %6A%61%76%61%73%63%72%69%70%74%3A = javascript:
    expect(sanitizeRedirectPath('/%6A%61%76%61%73%63%72%69%70%74%3Aalert(1)', fallback)).toBe(
      fallback,
    )
  })

  it('should block invalid percent-encoding', () => {
    expect(sanitizeRedirectPath('/path%ZZ', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('/path%2', fallback)).toBe(fallback)
  })

  it('should strip control characters', () => {
    // Tab (0x09) and newline (0x0A) should be stripped
    expect(sanitizeRedirectPath('/path\thost', fallback)).toBe('/path\thost')
    expect(sanitizeRedirectPath('/path\nhost', fallback)).toBe('/path\nhost')
  })

  it('should block disallowed characters via whitelist', () => {
    expect(sanitizeRedirectPath('/path with spaces', fallback)).toBe(fallback)
    expect(sanitizeRedirectPath('/path\\backslash', fallback)).toBe(fallback)
  })

  it('should preserve original (untrimmed) path when safe', () => {
    expect(sanitizeRedirectPath('/index')).toBe('/index')
  })

  it('should handle empty string after trim', () => {
    expect(sanitizeRedirectPath('  ', fallback)).toBe(fallback)
  })
})
