import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/geist'
import '@fontsource-variable/geist-mono'
import App from './App.vue'
import { router } from './router'
import './style.css'
// 初始化主题（在应用挂载前应用 dark/light class）
import './composables/useTheme'

createApp(App).use(createPinia()).use(router).mount('#app')
