import { createRouter, createWebHistory } from 'vue-router'
import { featureRoutes } from './route-registry'

export const router = createRouter({
  history: createWebHistory(),
  routes: [...featureRoutes, {
    path: '/:pathMatch(.*)*', name: 'not-found',
    component: () => import('../features/g6/NotFound.vue'),
  }],
  scrollBehavior: () => ({ top: 0 }),
})
