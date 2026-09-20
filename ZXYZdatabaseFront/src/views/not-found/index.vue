<!--
  404 页 —— 兜底路由 `/:pathMatch(.*)*` 的落地页。

  为什么需要（ISSUE/28 §5 / §7-C「技术债：404 路由」）：
    本仓此前**没有** catch-all 路由。未匹配路径经 nginx 兜底落到 index.html 后，
    路由解析不到任何一条 ⇒ 页面**白屏**，且控制台不报错，
    用户与运维都看不出「这是路径写错了」还是「服务坏了」。
    这是与 vue-router 版本无关的**既有缺口**（不是 v5 升级引入的回归），本页补上。

  为什么不回显用户输入的路径：
    该路径来自 URL，完全由外部可控（例如有人构造 /paypal-verify-account 这样的链接）。
    把它渲染进自家域名下的页面 = 回显攻击者构造的文案，属于钓鱼面。
    Vue 的 `{{ }}` 虽会转义、不存在 XSS，但也没必要为此引入这个面 ⇒ 文案保持**静态**。

  为什么只有「返回首页」一个出口：
    404 最常见的成因是链接过期或手误，回首页是最确定的兜底动作；
    「返回上一页」浏览器自身就提供，不必在页面里重复。
-->
<template>
  <main class="not-found-page">
    <section class="not-found-panel">
      <p class="not-found-code" v-once>404</p>
      <h1 v-once>页面不存在</h1>
      <p v-once>你访问的地址不存在或已被移除，请检查链接是否完整。</p>
      <el-button type="primary" @click="goHome">返回首页</el-button>
    </section>
  </main>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

/**
 * 用 `replace` 而**不是** `push`：
 * 404 是「错误态」，不该留在历史栈里 —— 否则用户点浏览器「后退」会退回这个 404，
 * 观感上像死循环。
 */
function goHome() {
  router.replace({ name: 'index' })
}
</script>

<style scoped>
/* 全部走 design token（src/styles/tokens.css）。
   为什么必须这样：`scripts/check-hardcoded-colors.mjs` 的棘轮基线当前**恰好等于实测值 47**
   （零余量 —— 这是「已经压到没得压」的正常状态，不是配置错误）。
   新写一个页面若带 6 个字面量就会把总数顶到 53 ⇒ 直接撞红 colors:check。
   正确做法是用 token，而不是「新增页面所以上调基线」—— 那是放松棘轮。

   说明：间距刻意保留字面量（与同样简单的 no-team 页保持一致）。token 层虽有
   --zxyz-space-*，但 28px / 20px 在其中没有等值项，只把其中 3 个换成变量
   会让同一段样式一半变量一半字面量，可读性反而更差；且间距不受任何门禁约束。

   注：这里的取值与 no-team 页**不完全等值**（如 bg-page #f5f7fa vs no-team 的 #f6f8fb），
   差异为 1 个灰阶、肉眼不可辨。新页面按 token 取值是既定方向，不做字面量复制。*/
.not-found-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--zxyz-color-bg-page);
}

.not-found-panel {
  width: min(420px, 100%);
  padding: 28px;
  border: 1px solid var(--zxyz-color-border);
  border-radius: var(--zxyz-radius-md);
  background: var(--zxyz-color-bg-surface);
  box-shadow: var(--zxyz-shadow-panel);
}

/* 注意：`.not-found-code` 也是 <p>（语义上是提示语），
   必须写成 `.not-found-panel .not-found-code` 才能在特异性上压过下面的
   `.not-found-panel p`，否则 margin 会被 20px 覆盖掉。*/
.not-found-panel .not-found-code {
  margin: 0 0 4px;
  font-size: 40px;
  font-weight: 600;
  line-height: 1;
  color: var(--zxyz-color-text-tertiary);
}

.not-found-panel h1 {
  margin: 0 0 12px;
  font-size: 22px;
  color: var(--zxyz-color-text-primary);
}

.not-found-panel p {
  margin: 0 0 20px;
  line-height: 1.7;
  color: var(--zxyz-color-text-secondary);
}
</style>
