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
    files: ['vite.config.js', 'vitest.config.js'],
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
