/* global process */
import fs from 'node:fs'
import path from 'node:path'

import { describe, it, expect, vi, afterEach } from 'vitest'

import {
  REQUIRED_ENV_VARS,
  listMissingRequiredEnvs,
  requireViteEnv,
  resolveApiBaseUrl,
  resolveWebSocketUrl,
} from '../env'

// ⚠️ 不要用 import.meta.url 定位源码目录：在本仓的 vitest/vite 加载器下它不是 file: URL，
// `fileURLToPath(new URL(...))` 会抛 `TypeError: The URL must be of scheme file`（实测）。
// 改为从 process.cwd() 逐级向上找含标记文件的目录，覆盖两种真实执行形态：
//   ① vitest 在前端目录下运行 → <cwd>/src
//   ② 仓库根目录下运行      → <cwd>/ZXYZdatabaseFront/src
const SRC_MARKER = path.join('utils', 'env.js')

function resolveSrcDir() {
  const candidates = []
  for (let dir = process.cwd(), i = 0; i < 6 && dir; i++) {
    candidates.push(path.join(dir, 'src'), path.join(dir, 'ZXYZdatabaseFront', 'src'))
    const parent = path.dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  for (const candidate of candidates) {
    if (fs.existsSync(path.join(candidate, SRC_MARKER))) {
      return candidate
    }
  }
  // fail-closed：定位不到就直接失败，绝不返回 null / 静默跳过
  throw new Error(
    `未能定位前端 src 目录（从 ${process.cwd()} 起共尝试 ${candidates.length} 个候选，均无 ${SRC_MARKER}）`,
  )
}

const SRC_DIR = resolveSrcDir()

const SCAN_EXT = new Set(['.js', '.ts', '.mjs', '.vue'])
// 取「字符串字面量」形式的调用，捕获其中的变量名
const LITERAL_CALL = /requireViteEnv\(\s*(['"])([^'"]+)\1/g
// 任意调用（用于识别"不是字面量、无法静态校验"的动态调用）。
// `(?<!function\s)` 排除 env.js 里的**函数定义行** `export function requireViteEnv(name, ...)`。
const ANY_CALL = /(?<!function\s)requireViteEnv\s*\(/g

/**
 * 先剥掉注释再扫描：否则注释里写的示例会被误当成真实调用点，
 * 从而要求清单里也存在一个并不存在的变量 —— 守卫反而变成噪声源。
 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '') // 块注释
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1') // 行注释；[^:] 避免吃掉 https:// 里的 //
    .replace(/<!--[\s\S]*?-->/g, '') // Vue 模板注释
}

function collectSourceFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === '__tests__' || entry.name === 'node_modules') continue
      collectSourceFiles(full, out)
    } else if (SCAN_EXT.has(path.extname(entry.name))) {
      out.push(full)
    }
  }
  return out
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('env.js 的加载期必需变量清单（F1 守卫）', () => {
  it('清单必须与全仓 requireViteEnv 的真实调用点完全一致', () => {
    const files = collectSourceFiles(SRC_DIR)

    // fail-closed：一个扫不到文件的探针等于没有探针，直接失败而不是让「空集 = 空集」假绿
    expect(files.length).toBeGreaterThan(0)

    const used = new Set()
    const dynamicCallSites = []
    for (const file of files) {
      const code = stripComments(fs.readFileSync(file, 'utf8'))
      for (const match of code.matchAll(LITERAL_CALL)) {
        used.add(match[2])
      }
      // 动态调用（requireViteEnv(someVar)）无法静态校验，单独列出并要求改成字面量；
      // 否则守卫会在"自以为覆盖了"的情况下漏掉真实变量名。
      const total = [...code.matchAll(ANY_CALL)].length
      const literal = [...code.matchAll(LITERAL_CALL)].length
      if (total !== literal) {
        dynamicCallSites.push(path.relative(SRC_DIR, file))
      }
    }

    expect(
      dynamicCallSites,
      `下列文件中的 requireViteEnv 调用不是字符串字面量，无法静态校验，请改为字面量：${dynamicCallSites.join(', ')}`,
    ).toEqual([])

    // 双向一致：既不能漏登记（入口自检会漏报 ⇒ 白屏而非友好提示），也不能多登记（误报）。
    expect([...used].sort()).toEqual([...REQUIRED_ENV_VARS].sort())
  })

  /**
   * F2 不变式：入口自检（listMissingRequiredEnvs）与加载期取值（requireViteEnv）
   * 必须对「什么算没配好」给出**同一个**结论。
   *
   * 两份旧实现各自写了一份判断（一个 `?.trim()` 判真值、一个 `!value || !String(value).trim()`），
   * 于是口径可以分叉 —— 分叉的后果是自检说「都配好了」而模块加载期照样 throw =
   * 白屏而不是友好提示。这里用「全缺失」和「纯空白」两种输入把它钉住：
   * 前者覆盖最常见形态，后者是两份实现最容易给出不同答案的输入。
   */
  it('两个入口对「已配置」的判定必须一致（F2 不变式）', () => {
    for (const name of REQUIRED_ENV_VARS) {
      vi.stubEnv(name, '')
    }
    expect(listMissingRequiredEnvs()).toEqual([...REQUIRED_ENV_VARS])
    for (const name of REQUIRED_ENV_VARS) {
      expect(() => requireViteEnv(name)).toThrow(new RegExp(name))
    }

    // 纯空白：不是值，仍算缺失 —— 两个入口都必须这样认为
    vi.stubEnv(REQUIRED_ENV_VARS[0], '   ')
    expect(listMissingRequiredEnvs()).toContain(REQUIRED_ENV_VARS[0])
    expect(() => requireViteEnv(REQUIRED_ENV_VARS[0])).toThrow(new RegExp(REQUIRED_ENV_VARS[0]))
  })
})

describe('env.js 行为', () => {
  it('resolveApiBaseUrl 返回 VITE_API_BASE_URL 且去除首尾空白', () => {
    vi.stubEnv('VITE_API_BASE_URL', '  https://api.example.com  ')
    expect(resolveApiBaseUrl()).toBe('https://api.example.com')
  })

  it('requireViteEnv 在缺失时抛出，且错误信息包含变量名', () => {
    vi.stubEnv('VITE_ABSENT_FOR_TEST', '')
    expect(() => requireViteEnv('VITE_ABSENT_FOR_TEST')).toThrow(/VITE_ABSENT_FOR_TEST/)
  })

  it('listMissingRequiredEnvs 在全部齐备时返回空数组', () => {
    for (const name of REQUIRED_ENV_VARS) {
      vi.stubEnv(name, 'configured')
    }
    expect(listMissingRequiredEnvs()).toEqual([])
  })

  it('listMissingRequiredEnvs 只报出缺失的那一项', () => {
    for (const name of REQUIRED_ENV_VARS) {
      vi.stubEnv(name, 'configured')
    }
    vi.stubEnv('VITE_IM_WS_URL', '')
    expect(listMissingRequiredEnvs()).toEqual(['VITE_IM_WS_URL'])
  })

  it('resolveWebSocketUrl：相对路径补全为当前页面的 ws 地址，绝对地址原样返回', () => {
    expect(resolveWebSocketUrl('/ws')).toBe(`ws://${window.location.host}/ws`)
    expect(resolveWebSocketUrl('wss://x.example.com/ws')).toBe('wss://x.example.com/ws')
  })
})
