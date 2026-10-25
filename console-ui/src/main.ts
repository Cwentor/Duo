// 入口：挂载应用（路由守卫管令牌跳转；业务视图全部动态 import 懒加载）。
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).mount('#app')
