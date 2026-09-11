#!/usr/bin/env node
/**
 * typecheck 覆盖范围校验 —— 让 `npm run typecheck` 的真实覆盖面变成可执行断言。
 *
 * 背景（为什么需要这个脚本）
 * -------------------------
 * 本仓 `tsconfig.json` 是 `allowJs: true` + `checkJs: false`，而 `include` 里又写了
 * `src/**\/*.js`。这两者叠加的后果是：**JS 文件被纳入编译程序、但不会被类型检查**。
 * 于是 `vue-tsc --noEmit` 长期只校验极少数 `.ts` 文件，CI 却显示绿灯 —— 一个
 * "看起来有门禁其实没有"的假绿灯。
 *
 * 采用路线 A（见 ISSUE/08 4.1）：保持 `checkJs: false`，改为在文件首行逐个加
 * `// @ts-check` 显式点亮。这样"哪些文件被检查"从隐式变为显式，但随之而来的风险是
 * **有人为了让 CI 变绿，悄悄删掉某个 `// @ts-check`** —— 覆盖面无声缩小。
 *
 * 本脚本用两条规则把这件事钉死：
 *   规则 1（声明式）：`FULLY_LIT_DIRS` 下的 .js 必须**全部**带 `// @ts-check`。
 *                     一旦声明某层已纳入检查，就不允许再退回去。
 *   规则 2（棘轮）  ：全仓带 `// @ts-check` 的 .js 文件数不得低于 `CHECKJS_BASELINE`。
 *                    覆盖面只能增加，不能减少（与 vite.config.js 的覆盖率阈值同为棘轮思路）。
 *
 * 用法：node scripts/check-typecheck-scope.mjs        （CI 与本地均可跑）
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join, posix } from 'node:path'

const SRC_DIR = 'src'

/** 棘轮基线：已点亮文件数不得低于此值。上调后方可收紧。 */
const CHECKJS_BASELINE = 18

/** 声明式规则：这些目录下的 .js 必须全部带 // @ts-check（相对仓库根，含尾斜杠）。 */
const FULLY_LIT_DIRS = ['src/api/']

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'coverage') continue
      walk(full, acc)
    } else {
      acc.push(full)
    }
  }
  return acc
}

/** 判定「已点亮」：`// @ts-check` 出现在文件前若干行内（允许前面有注释/空行）。 */
function isLit(file) {
  const head = readFileSync(file, 'utf8').split('\n', 12).join('\n')
  return /^\s*\/\/\s*@ts-check\b/m.test(head)
}

const files = walk(SRC_DIR).map((f) => f.split('\\').join(posix.sep))
const jsFiles = files.filter((f) => f.endsWith('.js'))
const tsFiles = files.filter((f) => f.endsWith('.ts') && !f.endsWith('.d.ts'))
const litJs = jsFiles.filter(isLit)

const problems = []

// 规则 1：声明已点亮的目录，不允许有漏网或回退
for (const dir of FULLY_LIT_DIRS) {
  const missing = jsFiles.filter((f) => f.startsWith(dir) && !isLit(f))
  if (missing.length > 0) {
    problems.push(
      `[规则1] ${dir} 已声明纳入类型检查，但以下 .js 缺少首行 \`// @ts-check\`：\n` +
        missing.map((f) => `         - ${f}`).join('\n'),
    )
  }
}

// 规则 2：棘轮 —— 覆盖面不得缩小
if (litJs.length < CHECKJS_BASELINE) {
  problems.push(
    `[规则2] 已点亮文件数 ${litJs.length} 低于棘轮基线 ${CHECKJS_BASELINE}。\n` +
      `        typecheck 覆盖面不允许缩小。若确实要移除某文件的 // @ts-check，\n` +
      `        请同时下调本脚本的 CHECKJS_BASELINE 并在 PR 说明理由。`,
  )
}

// 规则 3：棘轮上调提示（非阻断）
if (litJs.length > CHECKJS_BASELINE) {
  console.log(
    `提示：已点亮文件数 ${litJs.length} 已高于基线 ${CHECKJS_BASELINE}，` +
      `建议把 CHECKJS_BASELINE 上调到 ${litJs.length} 以收紧棘轮。`,
  )
}

console.log('typecheck 覆盖范围：')
console.log(`  .ts  文件（始终被检查）      ${tsFiles.length}`)
console.log(`  .js  文件（带 @ts-check）    ${litJs.length}`)
console.log(`  .js  文件（未被检查）        ${jsFiles.length - litJs.length}`)
for (const f of litJs) console.log(`    lit  ${f}`)

if (problems.length > 0) {
  console.error('\n✖ typecheck 覆盖范围校验未通过：')
  for (const p of problems) console.error('  ' + p)
  process.exit(1)
}
console.log('\n✔ typecheck 覆盖范围校验通过')
