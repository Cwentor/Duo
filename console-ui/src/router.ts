// 路由（hash 模式：同源托管在 /console/** 下不依赖 history 服务端配置）。
// 守卫只有两条：无令牌一律先进登录页；已持令牌不回登录页。
// 令牌正确性不在路由层判断——统一由 API 401 反杀（request 里清会话跳转）。
import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './stores/session'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/library' },
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/library', component: () => import('./views/LibraryView.vue') },
    { path: '/workspace/:id', component: () => import('./views/WorkspaceView.vue') },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !session.token) return '/login'
  if (to.path === '/login' && session.token) return '/library'
})
