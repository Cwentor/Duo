import { createRouter, createWebHashHistory } from 'vue-router'

// hash 路由：SPA fallback 由控制台静态托管承担（无扩展名回 index.html），
// hash 形态让刷新/深链不依赖服务端重写行为。Task 3 补守卫与页面路由。
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', redirect: '/library' }],
})
