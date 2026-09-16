#!/usr/bin/env node
/**
 * 硬编码颜色棘轮门禁
 * ==================
 *
 * 规则
 * ----
 *  规则 1：`src/` 下的硬编码色值总数（hex + rgb/rgba 字面量）**不得高于** `COLOR_BASELINE`。
 *  规则 2：已降到基线以下时给出提示，建议下调基线以收紧棘轮（与 TS 棘轮的写法一致）。
 *
 * 为什么需要它
 * ------------
 * `src/styles/tokens.css` 建立了 Design Token 层，但「Token 层存在」不等于「有人在用」——
 * 2026-09-16 建立 token 层时**一次都没迁移调用点**，实际使用数为 0。
 * 没有门禁的迁移会在下一次随手写样式时退回硬编码，因此这里把「只能减少」固化下来。
 *
 * 统计口径（关键，别改错）
 * ------------------------
 *  1. **只统计样式上下文**：`.vue` 只取 `<style>` 块内部；`.js` 不计
 *     —— `#ffffff` 出现在 JS 里可能被交给 canvas / 图表 / 字符串拼接，
 *        换成 `var(...)` 会直接坏掉，把不计入当成"漏统计"是错的。
 *  2. **先剥注释再统计**：CSS 块注释与 HTML 注释里常出现「示例色值」
 *     （`tokens.css` 头部就写了一整张对照表）。不剥注释会把文档当成代码，
 *     数字虚高且永远降不下来。
 *  3. **排除 `url(#...)`**：`url(#filter)` 是 SVG 片段引用，不是颜色。
 *  4. `src/styles/tokens.css` 自身豁免 —— 它就是色值的唯一真源。
 *
 * 用法
 * ----
 *   node scripts/check-hardcoded-colors.mjs              # 门禁（超基线则 exit 1）
 *   node scripts/check-hardcoded-colors.mjs --list       # 额外打印按文件明细
 *   node scripts/check-hardcoded-colors.mjs --json       # 机器可读
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const SRC_DIR = path.resolve(process.cwd(), 'src')
// ⚠️ 必须写成正斜杠常量：下面比较用的 `rel` 是 path.relative 结果再归一化成 '/' 的，
// 若这里用 path.join（Windows 上产出反斜杠）会永不相等 ⇒ 豁免静默失效。
const TOKEN_FILE = 'styles/tokens.css'

/**
 * 棘轮基线：当前硬编码色值命中数上限。**只允许下调，不允许上调。**
 * 历史：2026-09-16 建立门禁前实测 166（含 tokens.css 自身 10 处，豁免后 156）；
 *       同批迁移 34 个文件 109 处后降到 47（hex 30 + rgb/rgba 17），故基线取 47。
 * 剩余 47 处的色值在 token 层没有等值项，替换会改视觉 —— 必须先做「近似色是否合并」的
 * 视觉决策才能继续下压，别为了降数字硬换。
 */
const COLOR_BASELINE = 47

/** 只扫这些扩展名；`.js` 刻意不含（见文件头「统计口径」第 1 条）。 */
const STYLE_EXTS = new Set(['.vue', '.css', '.scss'])

const HEX_RE = /#[0-9a-fA-F]{3,8}\b/g
const FUNC_RE = /\brgba?\s*\(/g

/** 剥掉 CSS 注释（非贪婪，跨行）。 */
function stripCssComments(s) {
  return s.replace(/\/\*[\s\S]*?\*\//g, '')
}

/** 取出 .vue 的全部 <style> 块内容（不含标签本身）。 */
function extraStyleBlocks(s) {
  const out = []
  const re = /<style[^>]*>([\s\S]*?)<\/style>/g
  let m
  while ((m = re.exec(s)) !== null) out.push(m[1])
  return out
}

/**
 * 统计一段样式文本里的硬编码色值。
 * 排除 `url(#...)`（SVG 片段引用），其余 hex 与 rgb/rgba 字面量都计入。
 */
function countColors(text) {
  const cleaned = text.replace(/url\(\s*['"]?#[^)]*\)/gi, 'url()')
  const hex = cleaned.match(HEX_RE) || []
  const fn = cleaned.match(FUNC_RE) || []
  return { hex: hex.length, fn: fn.length, values: hex.map((h) => h.toLowerCase()) }
}

function walk(dir, acc = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name)
    if (ent.isDirectory()) {
      if (ent.name === '__tests__' || ent.name === 'node_modules') continue
      walk(p, acc)
    } else if (STYLE_EXTS.has(path.extname(ent.name))) {
      acc.push(p)
    }
  }
  return acc
}

function analyze() {
  const files = walk(SRC_DIR)
  const perFile = []
  let total = 0
  let totalHex = 0
  let totalFn = 0

  for (const abs of files) {
    const rel = path.relative(SRC_DIR, abs).split(path.sep).join('/')
    if (rel === TOKEN_FILE) continue
    const raw = fs.readFileSync(abs, 'utf8')
    let scope
    if (path.extname(abs) === '.vue') {
      scope = stripCssComments(extraStyleBlocks(raw).join('\n'))
    } else {
      scope = stripCssComments(raw)
    }
    const c = countColors(scope)
    const n = c.hex + c.fn
    if (n > 0) {
      perFile.push({ file: rel, hex: c.hex, fn: c.fn, total: n })
      total += n
      totalHex += c.hex
      totalFn += c.fn
    }
  }

  perFile.sort((a, b) => b.total - a.total)
  return { total, totalHex, totalFn, fileCount: perFile.length, perFile }
}

const argv = process.argv.slice(2)
const asJson = argv.includes('--json')
const asList = argv.includes('--list')

const r = analyze()

if (asJson) {
  console.log(JSON.stringify({ ...r, baseline: COLOR_BASELINE }, null, 2))
  process.exit(r.total > COLOR_BASELINE ? 1 : 0)
}

console.log('硬编码颜色棘轮校验')
console.log(`  扫描范围        src/ 下的 .vue <style> 块 + .css/.scss（不含 .js，不含 tokens.css）`)
console.log(`  命中总数        ${r.total}（hex ${r.totalHex} + rgb/rgba ${r.totalFn}）`)
console.log(`  涉及文件        ${r.fileCount} 个`)
console.log(`  棘轮基线        ${COLOR_BASELINE}`)

if (asList) {
  console.log('\n按文件明细（前 40）：')
  for (const f of r.perFile.slice(0, 40)) {
    console.log(`  ${String(f.total).padStart(4)}  ${f.file}`)
  }
}

if (r.total > COLOR_BASELINE) {
  console.error(
    `\n✖ 硬编码色值 ${r.total} 处，已高于棘轮基线 ${COLOR_BASELINE}。\n` +
      `  新增样式请改用变量：\n` +
      `    · 本项目自有语义 → src/styles/tokens.css 里的 var(--zxyz-*)\n` +
      `    · Element Plus 已拥有的色值 → 直接 var(--el-*)，不要另造一份\n` +
      `  （tokens.css 文件头列了 EP 已拥有的那一批，别重复声明。）\n` +
      `  若非确实无法避免，请说明理由后再上调基线 —— 上调基线 = 放松门禁。`
  )
  process.exit(1)
}

if (r.total < COLOR_BASELINE) {
  console.log(
    `\n提示：命中数 ${r.total} 已低于基线 ${COLOR_BASELINE}，` +
      `建议把本脚本的 COLOR_BASELINE 下调到 ${r.total} 以收紧棘轮。`
  )
}

console.log('\n✔ 硬编码颜色棘轮校验通过')
