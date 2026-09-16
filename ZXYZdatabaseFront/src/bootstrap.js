import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import { chatBridgePlugin } from '@/store/plugins/chatBridge'

import App from './App.vue'
import router from './router'

// 必须在 element-plus 样式之后、App 挂载之前：
// tokens.css 只声明 :root 变量，而部分变量（如 --zxyz-color-bg-page）语义上
// 与 Element Plus 的浅色底同族，放前面会被主题覆盖顺序搞乱。
import 'element-plus/es/components/message/style/css'
import './styles/tokens.css'
// import './assets/main.css'

const app = createApp(App)

const pinia = createPinia()
pinia.use(chatBridgePlugin)
app.use(pinia)
app.use(router)
app.mount('#app')
