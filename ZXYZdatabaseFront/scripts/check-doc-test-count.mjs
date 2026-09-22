#!/usr/bin/env node
/**
 * 文档里的「前端测试文件数」校验 —— 让 CLAUDE.md 里那个数字不再靠自觉维护。
 *
 * 背景（为什么需要这个脚本）
 * -------------------------
 * `CLAUDE.md` 的「前端测试」一行写着「N 个测试文件，M 个用例」。实测（2026-09-21）
 * 这里写的是 **26**，而真实值是 **65** —— 一个 doc 里落后 2.5 倍的数字。
 * 这类数字的问题不在于"不好看"，而在于：
 *   1) 人和 agent 都会拿它当基线（例如据此判断"这次改动是否减少了测试"），错了会得出反向结论；
 *   2) 它不会以任何方式失败 —— 没有任何门禁、编译或测试会因为数字过期而变红。
 * 这正是本仓反复出现的「看起来有维护、其实没有」的形态，故用脚本钉住**能自动求得真值的那一半**。
 *
 * 只校验「文件数」
 * --------------
 * `src/**\/*.spec.js` 的个数可以静态数出来，所以它是硬断言（fail-closed）。
 * 用例条数（M）只有跑起来才知道（`npm run test` 的汇总行），脚本不假装能算出来，
 * 因此不对它做断言，只在输出里提示需要人工同步 —— 用一个会假绿的门禁去盖住它，
 * 比不盖更糟（那正是本仓其它几处门禁踩过的坑）。
 *
 * ⚠️ 刻意**不**扫描 `vite.config.mjs`
 * -------------------------------
 * 该文件覆盖率注释里也出现过「测试文件 44 个 / 用例 970 条」。那一段是**按日期分轮的历史快照**
 * （"2026-09-11 六轮 ……"），记录的是当时的规模，44/970 对那一天是正确的。
 * 若把"与当前值一致"当成门禁，就等于强迫人去改写历史 —— 本轮审计中确实差点这么做了。
 * 因此：历史记录归历史记录，本脚本只管 `CLAUDE.md` 这一处**当前声明**。
 * （已在 `vite.config.mjs` 那段注释里加了「勿按当前值改写」的说明，防止后人重蹈。）
 *
 * ⚠️ fail-closed：解析不到数字（有人改写了措辞）直接失败，而不是"扫不到就算通过"。
 *    措辞变了就把本脚本的正则一起改 —— 否则门禁会静默消失。
 *
 * 用法：node scripts/check-doc-test-count.mjs   （CI 与本地均可跑；cwd 须为 ZXYZdatabaseFront）
 */
import { readdirSync, readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

/** 唯一的「当前声明」所在：相对本前端目录，CLAUDE.md 在仓库根（上一级）。 */
const DOC_FILE = join('..', 'CLAUDE.md')
const DOC_LABEL = 'CLAUDE.md'

/** CLAUDE.md 的措辞：「N 个测试文件，M 个用例」。 */
const FILE_COUNT = /(\d+)\s*个测试文件/
const CASE_COUNT = /(\d+)\s*个用例/

function countSpecFiles(dir = 'src') {
  let count = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'coverage') continue
      count += countSpecFiles(full)
    } else if (entry.name.endsWith('.spec.js')) {
      count++
    }
  }
  return count
}

function readDeclared() {
  if (!existsSync(DOC_FILE)) {
    throw new Error(
      `找不到 ${DOC_LABEL}（按 cwd 解析为 ${DOC_FILE}）。本脚本要求工作目录是 ZXYZdatabaseFront。`
    )
  }
  const text = readFileSync(DOC_FILE, 'utf8')
  const f = text.match(FILE_COUNT)
  const c = text.match(CASE_COUNT)
  if (!f || !c) {
    throw new Error(
      `在 ${DOC_LABEL} 里没有找到「N 个测试文件」/「M 个用例」。` +
        '若确实改了措辞，请同步修改本脚本的正则 —— 不要让门禁静默失效。'
    )
  }
  return { files: Number(f[1]), cases: Number(c[1]) }
}

function main() {
  const actualFiles = countSpecFiles()
  const declared = readDeclared()

  console.log('文档测试数量校验')
  console.log(`  src/**/*.spec.js 实测文件数     ${actualFiles}`)
  console.log(`  ${DOC_LABEL} 声明                文件 ${declared.files} / 用例 ${declared.cases}`)
  console.log('  说明：用例数无法静态推导，仅提示不校验')

  if (declared.files !== actualFiles) {
    console.error(
      `\n✖ ${DOC_LABEL} 声明 ${declared.files} 个测试文件，实测 ${actualFiles} 个` +
        `（差 ${actualFiles - declared.files}）。`
    )
    console.error(
      `\n  请把 ${DOC_LABEL} 里的数字改成 ${actualFiles}；\n` +
        '  用例数请跑 `npm run test`，看汇总行 `Tests  N passed (N)` 里的 N，一并同步。'
    )
    process.exit(1)
  }

  console.log('\n✔ 文档测试数量校验通过')
}

main()
