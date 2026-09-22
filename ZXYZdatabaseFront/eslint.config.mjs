import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'
import pluginPrettier from 'eslint-plugin-prettier'
import importPlugin from 'eslint-plugin-import-x'

export default [
  {
    ignores: ['dist/', 'node_modules/', 'auto-imports.d.ts', 'components.d.ts', 'public/iconFont/', 'commitlint.config.js'],
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
    plugins: {
      prettier: pluginPrettier,
      'import-x': importPlugin,
    },
    rules: {
      'prettier/prettier': 'warn',
      'vue/multi-word-component-names': 'off',
      'vue/no-useless-template-attributes': 'off',
      'vue/no-mutating-props': 'error',
      'vue/no-v-html': 'warn',
      'vue/define-macros-order': ['error', {
        order: ['defineProps', 'defineEmits', 'defineSlots']
      }],
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      'no-console': 'warn',
      'no-debugger': 'error',
      'prefer-const': 'error',
      'no-var': 'error',
      'eqeqeq': ['error', 'smart'],
      'func-style': ['warn', 'declaration', { allowArrowFunctions: true }],
      'import-x/order': ['error', {
        groups: ['builtin', 'external', 'internal', 'parent', 'sibling'],
        pathGroups: [{ pattern: '@/**', group: 'internal' }],
        'newlines-between': 'always'
      }],
    },
  },
  {
    files: ['vite.config.mjs', 'vitest.config.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    // scripts/ 下的校验脚本跑在 Node 里，需要 node globals。
    //
    // ⚠️ 上面 `**/*.{js,vue}` 那一块只给 .js / .vue 装了 `globals.browser`，
    // 而 .mjs **不在**该 pattern 内 ⇒ 这些脚本此前既没有 browser 也没有 node globals，
    // 每个文件都会报一片 `'console' is not defined` / `'process' is not defined`
    //（实测 4 个脚本共 55 条）。CI 的 lint 只跑 `eslint src/`，所以一直没暴露；
    // 本条把 scripts/ 的类型环境补正，日后把 `eslint scripts/` 纳入 CI 即可直接可用。
    files: ['scripts/**/*.{js,mjs}'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    files: ['**/*.{spec,test}.{js,ts}', '**/test/**/*.{js,ts}', '**/tests/**/*.{js,ts}', '**/__tests__/**/*.{js,ts}'],
    languageOptions: {
      globals: {
        ...globals.vitest,
      },
    },
  },
  prettier,
]
