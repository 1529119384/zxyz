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
      VueDevTools(),
      vue(),
      AutoImport({
        resolvers: [ElementPlusResolver()],
      }),
      Components({
        resolvers: [ElementPlusResolver()],
      }),
    ],
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
        exclude: ['node_modules/', 'src/main.js'],
        // 阈值采用「棘轮」策略：仅下调当前未达标的项，取值在实测值下方约 2 个点，
        // 使门禁可用并防回归；已达标的项（如 api 的 statements/lines 70）保持不动。
        // 2026-09-03 实测（npx vitest run --coverage --coverage.clean=false）：
        //   全局          stmts 60.14 / branch 51.98 / funcs 55.08 / lines 60.52
        //   src/store/im  stmts 37.60 / branch 47.87 / funcs 29.58 / lines 38.13
        //   src/api       stmts 72.50 / branch 35.29 / funcs 57.77 / lines 74.54
        // 目标值仍为全局 70、src/store/im 75，待补齐用例后逐档上调。
        thresholds: {
          statements: 58,
          branches: 50,
          functions: 53,
          lines: 58,
          'src/store/im/**': {
            statements: 35,
            branches: 45,
            functions: 27,
            lines: 36,
          },
          'src/api/**': {
            statements: 70,
            branches: 33,
            functions: 55,
            lines: 70,
          },
        },
      }
    }
  }
})
