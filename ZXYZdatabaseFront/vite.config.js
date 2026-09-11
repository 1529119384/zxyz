import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import VueDevTools from 'vite-plugin-vue-devtools'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

function parseAllowedHosts(value) {
  return value
    ? value.split(',').map((host) => host.trim()).filter(Boolean)
    : []
}

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    server: {
      allowedHosts: parseAllowedHosts(env.VITE_DEV_ALLOWED_HOSTS),
    },
    optimizeDeps: {
      include: ['element-plus/es/components/virtual-list/index.mjs'],
    },
    plugins: [
      mode !== 'production' && VueDevTools(),
      vue(),
      AutoImport({
        resolvers: [ElementPlusResolver()],
      }),
      Components({
        resolvers: [ElementPlusResolver()],
      }),
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    build: {
      target: 'es2020',
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks: {
            'element-plus': ['element-plus'],
            vendor: ['vue', 'vue-router', 'pinia', 'axios'],
          }
        }
      }
    },
    test: {
      globals: true,
      environment: 'happy-dom',
      setupFiles: ['./src/test/setup.js'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        exclude: ['node_modules/', 'src/main.js', 'src/bootstrap.js'],
        // 阈值采用「棘轮」策略：仅下调当前未达标的项，取值在实测值下方约 2 个点，
        // 使门禁可用并防回归；已达标的项（如 api 的 statements/lines 70）保持不动。
        // 实测（npx vitest run --coverage --coverage.clean=false）：
        //   2026-09-03 首轮  全局 stmts 60.14 / branch 51.98 / funcs 55.08 / lines 60.52
        //                     src/store/im  stmts 37.60 / branch 47.87 / funcs 29.58 / lines 38.13
        //                     src/api stmts 72.50 / branch 35.29 / funcs 57.77 / lines 74.54
        //   2026-09-03 三轮（再补 permissionDomain/realtimeDomain 用例 + 新增 id.spec.js）
        //                     全局 stmts 83.25 / branch 70.08 / funcs 81.14 / lines 83.58
        //                     src/store/im  stmts 98.68 / branch 93.77 / funcs 99.40 / lines 99.08
        //                     src/api stmts 72.50 / branch 35.29 / funcs 57.77 / lines 74.54
        //                     src/utils/id.js 100（原仅有 realtimeDomain 的附带覆盖 33%，
        //                     已补 id.spec.js 做正经单测，不再依赖其它测试的顺带覆盖）
        //   2026-09-03 四轮（补 files/im/team 用例，api 已测文件覆盖率达 100%）
        //                     全局 stmts 85.05 / branch 73.66 / funcs 85.38 / lines 85.19
        //                     src/api stmts 100 / branch 97.64 / funcs 100 / lines 100
        //   2026-09-11 五轮（补 utils/oss.js、services/upload.js、models/upload.js 用例，
        //                    共 52 条。三者此前均为 0% —— 正是 doc 10 P0-4 点名的高风险
        //                    零测试区，且 OSS 直传是线上真出过故障的一环）
        //                     全局 stmts 86.28 / branch 79.70 / funcs 85.17 / lines 86.56
        //                     src/utils/oss.js 100、src/services/upload.js 98.52
        //                     src/models/upload.js 100 / branch 95.41（models 整体 10.71 → 78.57）
        //   2026-09-11 六轮（补 10-P0-4 剩余高风险零测试模块，共 6 个文件 + 扩写 1 个：
        //                    store/chat.js 0→92.98、composables/team/useTeamManagement.js 0→100、
        //                    composables/useDragSelection.js 0→94.69、composables/useCorePathNavigation.js 0→100、
        //                    utils/errorModel.js 35.48→100、utils/logger.js 44.44→100、
        //                    composables/useFileUpload.js 20.38→100）
        //                     全局 stmts 92.58 / branch 85.81 / funcs 92.34 / lines 92.92
        //                     （测试文件 44 个 / 用例 970 条）
        // 阈值采用「棘轮」：仅上调已达标项，取值在实测值下方约 4 点，既守住回归又不误报。
        // 目标值（全局 70、src/store/im 75、src/api 首轮阈值）均已达成并大幅超出，故本轮一并上调。
        // 六轮按同一规则上调全局（实测 − 4）：92.58/85.81/92.34/92.92 → 88/81/88/88。
        // src/store/im 与 src/api 本轮未变动，保持原值（api 实测已达 100，无回归空间）。
        thresholds: {
          statements: 88,
          branches: 81,
          functions: 88,
          lines: 88,
          'src/store/im/**': {
            statements: 94,
            branches: 89,
            functions: 95,
            lines: 94,
          },
          'src/api/**': {
            statements: 96,
            branches: 94,
            functions: 95,
            lines: 96,
          },
        },
      }
    }
  }
})
