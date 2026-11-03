import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [{
  path: '/', name: 'g6-home', component: () => import('./HomePage.vue'),
}]
