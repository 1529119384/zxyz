<!--
  权限页通用面板壳。

  存在的理由：`index.vue` 的四个面板（系统角色任命 / 系统权限字典 / 系统审计 /
  团队侧同样三个）此前各自重复一份 `<section class="panel">` + `.panel-title`
  + `v-if` 提示的标记，共 4 处；面板边框/内边距/标题排版散落在父组件的 scoped
  样式里，任何一处调整都要同步四处。收敛到这里后，样式只有一份。

  注意：`variant="assignment"` 在当前全仓样式表里**没有对应规则**
  （属历史遗留的无害类名，为保持 DOM 不变而保留）。
-->
<template>
  <section :class="panelClasses">
    <div class="panel-title">
      <h3>{{ title }}</h3>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <p v-if="tip" class="permission-tip">{{ tip }}</p>
    <slot />
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 面板标题，必填——面板没有标题就没有存在意义。 */
  title: { type: String, required: true },
  /** 标题下的一行说明。空串则不渲染该 `<p>`（与原实现一致）。 */
  subtitle: { type: String, default: '' },
  /** 权限不足等提示文案。空串则不渲染。 */
  tip: { type: String, default: '' },
  /** `default` | `assignment` | `wide`；`wide` 会横跨栅格整行。 */
  variant: { type: String, default: 'default' },
})

const panelClasses = computed(() =>
  [
    'panel',
    props.variant === 'assignment' ? 'panel--assignment' : '',
    props.variant === 'wide' ? 'panel--wide' : '',
  ].filter(Boolean),
)
</script>

<style scoped>
.panel {
  display: grid;
  gap: var(--zxyz-space-5);
  min-width: 0;
  padding: var(--zxyz-space-6);
  border: 1px solid var(--zxyz-color-border);
  border-radius: var(--zxyz-radius-md);
  background: var(--zxyz-color-bg-surface);
}

.panel--wide {
  grid-column: 1 / -1;
}

.panel-title {
  display: grid;
  gap: var(--zxyz-space-1);
}

.panel-title h3 {
  margin: 0;
  color: var(--zxyz-color-text-primary);
  font-size: 16px;
  font-weight: 700;
}

.panel-title p,
.permission-tip {
  margin: var(--zxyz-space-2) 0 0;
  color: var(--zxyz-color-text-secondary);
  font-size: 13px;
}
</style>
