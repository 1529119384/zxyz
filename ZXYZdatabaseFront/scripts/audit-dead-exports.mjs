#!/usr/bin/env node
/**
 * 死导出核对（audit-dead-exports）
 * ================================
 * 目的：回答「哪些 `export` 在全仓找不到任何消费者」——**只产报告，绝不自动删**。
 *
 * 为什么需要它
 * ------------
 * `ISSUE/07` P1-6 曾给出「28 个死导出」这个数字，但**从未给出清单**，且原始文档
 * 已随归档删除 ⇒ 数字无法追溯、无法复核。JS 的 `export` 不会像未使用变量那样被
 * ESLint 直接标红（跨文件可见性），所以「死导出」只能靠全仓引用分析得出。
 *
 * 判定口径（保守，宁可漏报不可误报）
 * --------------------------------
 *   A. 强证据（在用）：存在一条 `import`（含 `export ... from` 再导出、动态
 *      `import()`），其**解析后的目标文件**正是该导出的宿主文件，且**名字**命中
 *      （默认导入记 `default`；`import * as NS` 命中命名空间，另按 `NS.foo` 兜）。
 *   B. 弱证据（可能在用）：该标识符在**其它文件**里以独立单词出现过（含模板、
 *      测试、`NS.foo` 形式）。命中弱证据的条目**不进「疑似死导出」**，只列进
 *      「需人工确认」区，避免把「被自动注册/被模板引用」的东西误判为死代码。
 *   C. 结构豁免：`src/components/**` 下的 `.vue` 由 `unplugin-vue-components`
 *      自动全局注册（`vite.config.mjs` 的 `Components()` 未设 `dirs`，即默认
 *      `src/components`）⇒ 组件**不需要 import**，一律豁免。
 *
 * 用法：
 *   node scripts/audit-dead-exports.mjs            # 人类可读
 *   node scripts/audit-dead-exports.mjs --json     # 机器可读
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, posix, relative, resolve } from 'node:path'

const ROOT = resolve(process.cwd())
const SCAN_DIRS = ['src', 'e2e', 'scripts']
const SKIP_DIRS = new Set(['node_modules', 'coverage', 'dist', '.git'])
const CODE_EXT = ['.js', '.ts', '.mjs', '.vue']
/** 结构豁免：被自动全局注册的目录（相对 ROOT，posix 分隔，尾斜杠）。 */
const AUTO_REGISTERED_DIRS = ['src/components/']

/** 这些文件是「声明宿主」但本身不算消费者。 */
const HOST_ONLY = new Set()

const asJson = process.argv.includes('--json')

function walk(dir, acc = []) {
  let entries
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return acc
  }
  for (const entry of entries) {
    if (SKIP_DIRS.has(entry.name)) continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) walk(full, acc)
    else if (CODE_EXT.includes(full.slice(full.lastIndexOf('.')))) acc.push(full)
  }
  return acc
}

const files = SCAN_DIRS.flatMap((d) => {
  const abs = join(ROOT, d)
  try {
    return statSync(abs).isDirectory() ? walk(abs) : []
  } catch {
    return []
  }
}).map((f) => relative(ROOT, f).split('\\').join(posix.sep))

const source = new Map()
for (const f of files) source.set(f, readFileSync(join(ROOT, f), 'utf8'))

// ---------------------------------------------------------------- 导出抽取
/** @returns {Array<{file:string,name:string,kind:string,line:number}>} */
function extractExports(file, text) {
  const out = []
  const push = (name, kind, index) => {
    out.push({ file, name, kind, line: index + 1 })
  }

  // 去掉注释与字符串，避免把注释里的示例代码当成真导出
  const stripped = text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))

  const lineOf = (offset) => stripped.slice(0, offset).split('\n').length - 1

  const declRe =
    /^[ \t]*export\s+(?:async\s+)?(?:function\s*\*?|class|const|let|var)\s+([A-Za-z_$][\w$]*)/gm
  for (const m of stripped.matchAll(declRe)) push(m[1], 'decl', lineOf(m.index))

  const defaultRe = /^[ \t]*export\s+default\b/gm
  for (const m of stripped.matchAll(defaultRe)) push('default', 'default', lineOf(m.index))

  const namedRe = /^[ \t]*export\s*\{([^}]*)\}(\s*from\s*['"][^'"]+['"])?/gm
  for (const m of stripped.matchAll(namedRe)) {
    const isReexport = Boolean(m[2])
    for (const raw of m[1].split(',')) {
      const part = raw.trim()
      if (!part) continue
      const alias = part.includes(' as ') ? part.split(/\s+as\s+/)[1].trim() : part
      if (!/^[A-Za-z_$][\w$]*$/.test(alias)) continue
      push(alias, isReexport ? 'reexport' : 'named', lineOf(m.index))
    }
  }
  return out
}

// ---------------------------------------------------------------- 导入抽取
/** @returns {Array<{from:string,names:string[],namespace:boolean,line:number}>} */
function extractImports(text) {
  const out = []
  const stripped = text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))

  const importRe = /import\s+([^'";]*?)\s*from\s*['"]([^'"]+)['"]/g
  for (const m of stripped.matchAll(importRe)) {
    const clause = m[1]
    const names = []
    let namespace = false
    const braces = clause.match(/\{([^}]*)\}/)
    if (braces) {
      for (const raw of braces[1].split(',')) {
        const part = raw.trim()
        if (!part) continue
        const imported = part.includes(' as ') ? part.split(/\s+as\s+/)[0].trim() : part
        if (/^[A-Za-z_$][\w$]*$/.test(imported)) names.push(imported)
      }
    }
    if (/\*\s+as\s+[A-Za-z_$][\w$]*/.test(clause)) namespace = true
    const head = clause.replace(/\{[^}]*\}/, '').replace(/\*\s+as\s+\w+/, '').trim()
    if (head && !head.startsWith(',')) names.push('default')
    out.push({ from: m[2], names, namespace, line: 0 })
  }

  // 再导出：export { a, b } from 'x'
  const reexportRe = /export\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]/g
  for (const m of stripped.matchAll(reexportRe)) {
    const names = []
    for (const raw of m[1].split(',')) {
      const part = raw.trim()
      if (!part) continue
      const imported = part.includes(' as ') ? part.split(/\s+as\s+/)[0].trim() : part
      if (/^[A-Za-z_$][\w$]*$/.test(imported)) names.push(imported)
    }
    out.push({ from: m[2], names, namespace: false, line: 0 })
  }

  // 动态 import('x')
  const dynRe = /import\s*\(\s*['"]([^'"]+)['"]\s*\)/g
  for (const m of stripped.matchAll(dynRe)) {
    out.push({ from: m[1], names: ['*dynamic*'], namespace: true, line: 0 })
  }
  return out
}

// ---------------------------------------------------------------- 路径解析
const RESOLVE_EXT = ['', '.js', '.ts', '.mjs', '.vue', '/index.js', '/index.ts', '/index.vue']

function resolveSpecifier(fromFile, spec) {
  let base
  if (spec.startsWith('@/')) base = join(ROOT, 'src', spec.slice(2))
  else if (spec.startsWith('.')) base = resolve(ROOT, dirname(fromFile), spec)
  else return null // 裸包名（node_modules）不参与本仓分析

  for (const ext of RESOLVE_EXT) {
    const candidate = base + ext
    try {
      if (statSync(candidate).isFile()) return relative(ROOT, candidate).split('\\').join(posix.sep)
    } catch {
      /* 继续尝试下一个后缀 */
    }
  }
  return null
}

// ---------------------------------------------------------------- 建索引
const exportRecords = []
const consumers = new Map() // 目标文件 -> Set<名字>

for (const file of files) {
  const text = source.get(file)
  for (const rec of extractExports(file, text)) exportRecords.push(rec)
  for (const imp of extractImports(text)) {
    const target = resolveSpecifier(file, imp.from)
    if (!target) continue
    if (!consumers.has(target)) consumers.set(target, new Set())
    const bucket = consumers.get(target)
    for (const n of imp.names) bucket.add(n)
    if (imp.namespace) bucket.add('*dynamic*')
  }
}

// 弱证据：标识符出现在「别的文件」里
const wordIndex = new Map() // name -> Set<file>
for (const file of files) {
  const text = source.get(file)
  for (const m of text.matchAll(/[A-Za-z_$][\w$]*/g)) {
    if (!wordIndex.has(m[0])) wordIndex.set(m[0], new Set())
    wordIndex.get(m[0]).add(file)
  }
}

const dead = []
const weak = []
const exempt = []

for (const rec of exportRecords) {
  if (AUTO_REGISTERED_DIRS.some((d) => rec.file.startsWith(d) && rec.file.endsWith('.vue'))) {
    exempt.push(rec)
    continue
  }
  if (HOST_ONLY.has(rec.file)) continue
  const used = consumers.get(rec.file)
  if (used && (used.has(rec.name) || used.has('*dynamic*'))) continue

  const others = wordIndex.get(rec.name) || new Set()
  const elsewhere = [...others].filter((f) => f !== rec.file)
  if (elsewhere.length > 0) {
    weak.push({ ...rec, seenIn: elsewhere.slice(0, 4), seenCount: elsewhere.length })
  } else {
    dead.push(rec)
  }
}

// ---------------------------------------------------------------- 输出
const byFile = (a, b) => (a.file === b.file ? a.line - b.line : a.file < b.file ? -1 : 1)
dead.sort(byFile)
weak.sort(byFile)

if (asJson) {
  console.log(JSON.stringify({ dead, weak, exemptCount: exempt.length, files: files.length }, null, 2))
} else {
  console.log(`扫描文件 ${files.length} 个；导出声明 ${exportRecords.length} 条`)
  console.log(`  结构豁免（自动全局注册）       ${exempt.length}`)
  console.log(`  疑似死导出（强证据为 0）       ${dead.length}`)
  console.log(`  需人工确认（仅弱证据）         ${weak.length}`)
  console.log('')
  console.log('== 疑似死导出 ==')
  for (const r of dead) console.log(`  ${r.file}:${r.line}\t${r.kind}\t${r.name}`)
  console.log('')
  console.log('== 需人工确认（标识符在别处出现，但无 import 证据）==')
  for (const r of weak) {
    console.log(`  ${r.file}:${r.line}\t${r.kind}\t${r.name}\t(出现于 ${r.seenCount} 处: ${r.seenIn.join(', ')})`)
  }
}
