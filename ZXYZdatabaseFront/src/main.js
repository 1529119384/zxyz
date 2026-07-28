import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/zh-cn.mjs'

import { chatBridgePlugin } from '@/store/plugins/chatBridge'

import App from './App.vue'
import router from './router'

import 'element-plus/es/components/message/style/css'
// import './assets/main.css'

const app = createApp(App)

const pinia = createPinia()
pinia.use(chatBridgePlugin)
app.use(pinia)
app.use(router)
app.mount('#app')
