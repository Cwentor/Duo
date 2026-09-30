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
