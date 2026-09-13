#!/usr/bin/env node
/**
 * 前端产物体积预算门禁（ISSUE/08 §5.6「前端产物体积预算门禁」）
 *
 * 背景（为什么需要这个脚本）
 * -------------------------
 * 本仓此前**没有任何体积回归护栏**：页面变慢、主包变大只能靠人肉感知。
 * Vite 自带的 `chunks are larger than 500 kB` 只是**控制台提示**，既不区分
 * gzip 后的真实传输量，也不会让 CI 变红 —— 属于「看起来有告警其实没有」。
 *
 * 本脚本把"体积不回归"变成**可执行断言**，遵循与覆盖率 / `typecheck:scope`
 * 完全相同的**棘轮**思路：预算写在 `bundle-budget.json`，只能收紧、不能放宽
 * （放宽必须在 PR 里说明理由，与 `check-typecheck-scope.mjs` 的规则 2 同构）。
 *
 * 三个设计选择（都是刻意的）
 * -------------------------
 * 1. **只门禁 JS 与 CSS**，不管图片/字体。图片与字体独立缓存、不参与解析，
 *    混进来会让"换了一张大图"误伤 JS 预算的判读（它们仍会打印，但不阻断）。
 * 2. **阈值取 gzip 后**。原始字节数对"用户等多久"没有意义；gzip 后才是真实传输量。
 *    取 Vite 打印时用的默认压缩级别，便于与 `vite build` 输出直接对照。
 * 3. **分组键去掉内容哈希**。产物名形如 `element-plus-B2ub7-nJ.js`，哈希每次构建
 *    都变，按整名记预算等于每构建一次就要改一次预算文件 —— 那样的门禁会立刻被人
 *    关掉。因此分组键 = 去掉末尾 `-<8 位 base64url 哈希>` 的名字；同名多文件（如
 *    路由懒加载产生的多个 `index-*.js`）**按组求和**，这正是我们关心的粒度。
 *
 * 用法：
 *   node scripts/check-bundle-size.mjs              # 门禁校验（需先 npm run build）
 *   node scripts/check-bundle-size.mjs --baseline   # 按当前产物打印建议预算（不校验、不写文件）
 */
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'

const DIST = 'dist'
const ASSET_DIR = join(DIST, 'assets')
const BUDGET_FILE = 'bundle-budget.json'

/** 受门禁的扩展名：只有会被浏览器解析/执行的体积才算"主包"。 */
const GATED_EXT = new Set(['js', 'css'])
/** 仅打印、不门禁的静态资源（独立缓存，混入门禁会误伤判读）。 */
const REPORT_ONLY_EXT = new Set([
  'png',
  'jpg',
  'jpeg',
  'gif',
  'svg',
  'webp',
  'ico',
  'ttf',
  'woff',
  'woff2',
])

/** Vite 内容哈希：`<name>-<8 位 base64url 哈希>.<ext>`。 */
const HASHED_NAME = /^(.*)-[A-Za-z0-9_-]{8}\.([A-Za-z0-9]+)$/

const KIB = 1024
const toKib = (bytes) => bytes / KIB

/** 去掉内容哈希后的分组名；无法识别（未带哈希）时退化为文件名本身并标记。 */
function groupOf(fileName) {
  const m = HASHED_NAME.exec(fileName)
  return { group: m ? m[1] : fileName.replace(/\.[^.]+$/, ''), hashed: Boolean(m) }
}

function readAssets() {
  if (!existsSync(ASSET_DIR)) {
    console.error(
      `✖ 找不到 ${ASSET_DIR}/ —— 请先执行 \`npm run build\`（本脚本只校验构建产物，不负责构建）。`,
    )
    process.exit(1)
  }
  const entries = []
  for (const name of readdirSync(ASSET_DIR)) {
    const full = join(ASSET_DIR, name)
    if (!statSync(full).isFile()) continue
    const ext = name.slice(name.lastIndexOf('.') + 1).toLowerCase()
    if (!GATED_EXT.has(ext) && !REPORT_ONLY_EXT.has(ext)) continue
    const buf = readFileSync(full)
    const { group, hashed } = groupOf(name)
    const precompressed = ext === 'ttf' || ext === 'woff' || ext === 'woff2'
    entries.push({
      name,
      ext,
      group,
      hashed,
      raw: buf.length,
      gzip: precompressed ? buf.length : gzipSync(buf).length,
      gated: GATED_EXT.has(ext),
    })
  }
  return entries
}

function summarize(entries) {
  const groups = new Map()
  let jsTotal = 0
  let cssTotal = 0
  const unhashed = []
  for (const e of entries) {
    if (!e.gated) continue
    if (e.ext === 'js') jsTotal += e.gzip
    else cssTotal += e.gzip
    if (!groups.has(e.group)) groups.set(e.group, { group: e.group, gzip: 0, files: 0, ext: e.ext })
    const g = groups.get(e.group)
    g.gzip += e.gzip
    g.files += 1
    if (!e.hashed) unhashed.push(e.name)
  }
  const sorted = [...groups.values()].sort((a, b) => b.gzip - a.gzip)
  return { groups: sorted, jsTotal, cssTotal, unhashed }
}

const entries = readAssets()
const { groups, jsTotal, cssTotal, unhashed } = summarize(entries)

// ---------------------------------------------------------------- baseline 模式
if (process.argv.includes('--baseline')) {
  // 建议预算 = 当前实测 + 少量余量（分组 8%、总计 3%），并向上取整到 1 KiB。
  // 余量的意义：让「同步依赖版本小幅漂移」不会天天报警，而「功能级膨胀」一定报警。
  const groupsBudget = {}
  for (const g of groups) {
    if (g.gzip < 8 * KIB) continue // 小分组不单独设预算，避免噪声；总计已覆盖
    groupsBudget[g.group] = Math.ceil(toKib(g.gzip) * 1.08)
  }
  const suggested = {
    $comment:
      '前端产物体积预算（gzip KiB）。棘轮：只能收紧。改动请附理由。' +
      '重新生成：npm run build && node scripts/check-bundle-size.mjs --baseline',
    totalJsGzipKib: Math.ceil(toKib(jsTotal) * 1.03),
    totalCssGzipKib: Math.ceil(toKib(cssTotal) * 1.03),
    groupGzipKib: groupsBudget,
  }
  console.log(JSON.stringify(suggested, null, 2))
  process.exit(0)
}

// ---------------------------------------------------------------- 校验模式
if (unhashed.length > 0) {
  console.error(
    '✖ 以下产物没有内容哈希，分组键会随构建漂移，无法作为预算依据：\n' +
      unhashed.map((n) => `    - assets/${n}`).join('\n') +
      '\n  请确认 vite.config.js 的 build.rollupOptions.output 未关闭 hash。',
  )
  process.exit(1)
}

if (!existsSync(BUDGET_FILE)) {
  console.error(
    `✖ 缺少 ${BUDGET_FILE}。请先跑 \`npm run build\`，再执行：\n` +
      `    node scripts/check-bundle-size.mjs --baseline > ${BUDGET_FILE}\n` +
      '  然后**人工复核**其中的数值（不要盲信自动生成的余量）。',
  )
  process.exit(1)
}

const budget = JSON.parse(readFileSync(BUDGET_FILE, 'utf8'))
const failures = []
const warnings = []

console.log('前端产物体积（gzip）：')
const line = (label, actual, limit) => {
  const ok = limit === undefined || actual <= limit
  const suffix = limit === undefined ? '（未设预算）' : `/ ${limit.toFixed(1)} KiB`
  console.log(
    `  ${label.padEnd(24)}${actual.toFixed(1).padStart(8)} KiB ${suffix}  ${ok ? '✔' : '✖'}`,
  )
  return ok
}

if (!line('JS 总计', toKib(jsTotal), budget.totalJsGzipKib)) {
  failures.push(
    `JS 总计 ${toKib(jsTotal).toFixed(1)} KiB 超出预算 ${budget.totalJsGzipKib} KiB ` +
      `（+${(toKib(jsTotal) - budget.totalJsGzipKib).toFixed(1)} KiB）`,
  )
}
if (!line('CSS 总计', toKib(cssTotal), budget.totalCssGzipKib)) {
  failures.push(
    `CSS 总计 ${toKib(cssTotal).toFixed(1)} KiB 超出预算 ${budget.totalCssGzipKib} KiB ` +
      `（+${(toKib(cssTotal) - budget.totalCssGzipKib).toFixed(1)} KiB）`,
  )
}

// 打印门槛：只列「已设预算」或「≥ 8 KiB」的分组，其余折叠成一行 —— 否则 50+ 个
// element-plus 按需样式分片会把关键信息冲掉（噪声会让门禁失去可读性，进而被无视）。
const MIN_PRINT_KIB = 8

console.log('\n分组（gzip；预算见 bundle-budget.json 的 groupGzipKib）：')
let tinyCount = 0
let tinyTotal = 0
for (const g of groups) {
  const limit = budget.groupGzipKib?.[g.group]
  if (limit === undefined && toKib(g.gzip) < MIN_PRINT_KIB) {
    tinyCount += 1
    tinyTotal += toKib(g.gzip)
    continue
  }
  const ok = line(`${g.group} (${g.files} 文件)`, toKib(g.gzip), limit)
  if (!ok) {
    failures.push(
      `分组 ${g.group} ${toKib(g.gzip).toFixed(1)} KiB 超出预算 ${limit} KiB ` +
        `（+${(toKib(g.gzip) - limit).toFixed(1)} KiB）`,
    )
  } else if (limit !== undefined && toKib(g.gzip) < limit * 0.85) {
    // 相对判据 + 15% 松弛：budget 由 --baseline 按"实测 + 8% 余量"生成，若用 8% 或绝对差
    // 作判据，会把每个分组都判成"应下调"（天天误报的门禁会被直接无视）。只有实际明显低于
    // 预算（说明有真实优化、或预算已过时）才提示收紧。
    warnings.push(
      `分组 ${g.group} 实测 ${toKib(g.gzip).toFixed(1)} KiB 明显低于预算 ${limit} KiB，` +
        `建议把预算下调到 ${Math.ceil(toKib(g.gzip) * 1.05)}（棘轮：只收紧不放松）`,
    )
  }
}
if (tinyCount > 0) {
  console.log(
    `  …另有 ${tinyCount} 个小于 ${MIN_PRINT_KIB} KiB 的分组，合计 ${tinyTotal.toFixed(1)} KiB（未设预算，已计入总计）`,
  )
}

// 未被预算覆盖、但已超过打印门槛的新分组：只提示，不阻断（新增路由懒加载 chunk 属正常演进）。
const unbudgeted = groups.filter(
  (g) => budget.groupGzipKib?.[g.group] === undefined && g.gzip >= MIN_PRINT_KIB * KIB,
)
if (unbudgeted.length > 0) {
  warnings.push(
    `以下分组已超过 ${MIN_PRINT_KIB} KiB 但未设预算：` +
      unbudgeted.map((g) => `${g.group}(${toKib(g.gzip).toFixed(1)} KiB)`).join('、'),
  )
}

// 静态资源仅报告：字体/图片独立缓存，不参与门禁。
const heavy = entries.filter((e) => !e.gated && e.raw >= 100 * KIB).sort((a, b) => b.raw - a.raw)
if (heavy.length > 0) {
  console.log('\n静态资源（仅报告，不门禁）：')
  for (const a of heavy)
    console.log(`  ${a.name.padEnd(38)}${toKib(a.raw).toFixed(0).padStart(6)} KiB`)
}

for (const w of warnings) console.log(`\n提示：${w}`)

if (failures.length > 0) {
  console.error('\n✖ 产物体积预算校验未通过：')
  for (const f of failures) console.error(`  ${f}`)
  console.error(
    '\n  处理方式：\n' +
      '    · 是**非预期膨胀** ⇒ 找出引入它的改动并修复（这才是门禁的本意）。\n' +
      '    · 是**有意新增能力** ⇒ 在 PR 说明理由后，人工上调 bundle-budget.json 的对应值。\n' +
      '      （--baseline 只能生成"当前值 + 余量"，绝不要直接覆盖以掩盖回归。）',
  )
  process.exit(1)
}
console.log('\n✔ 产物体积预算校验通过')
