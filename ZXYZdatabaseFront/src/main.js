import { listMissingRequiredEnvs } from '@/utils/env'

// 入口最前置的必需环境变量自检。
// 缺失时不再让 request.js 等在 import 期 throw 导致整站白屏，
// 而是用纯 DOM 渲染一个明确列出缺失变量名的错误页，便于运维定位。
// env 齐全时才动态加载 bootstrap（即原 main.js 的应用引导逻辑），
// 此时加载期 requireViteEnv 必然通过，行为与改动前完全一致。
function renderEnvErrorPage(missing) {
  const root = document.getElementById('app') || document.body
  const items = missing
    .map((name) => `<li><code>${name}</code></li>`)
    .join('')
  root.innerHTML = `
    <div role="alert" style="font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; max-width: 640px; margin: 12vh auto; padding: 0 24px; color: #1f2329; line-height: 1.6;">
      <h1 style="font-size: 20px; margin: 0 0 12px;">应用无法启动：缺少必需的环境变量</h1>
      <p style="margin: 0 0 12px; color: #646a73;">请检查部署/构建配置，以下环境变量未设置或为空：</p>
      <ul style="margin: 0 0 16px; padding-left: 20px;">${items}</ul>
      <p style="margin: 0; color: #8f959e; font-size: 13px;">设置完成后重新构建并部署即可。</p>
    </div>`
}

const missing = listMissingRequiredEnvs()
if (missing.length > 0) {
  renderEnvErrorPage(missing)
} else {
  import('./bootstrap.js')
}
