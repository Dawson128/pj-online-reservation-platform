import { createRouter, createWebHistory } from 'vue-router';
import { featureRoutes } from './route-registry';
import { canManageSystem, session } from './session';

export const router = createRouter({ history: createWebHistory(), routes: [...featureRoutes, {
  path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../features/g6/NotFound.vue'),
}], scrollBehavior: () => ({ top: 0 }) });
router.beforeEach(async to => {
  // A completed logout/self-update already cleared local identity. Rechecking a revoked
  // session while entering login can remount the old protected view and overwrite its reason.
  if (to.name === 'g5-login' && session.state.phase === 'anonymous') return true;
  await session.restore(true);
  if (session.state.phase === 'unavailable') return true;
  const principal = session.state.principal;
  if (!principal) return to.meta.public ? true : { name: 'g5-login', query: { reason: 'expired' } };
  if (!session.state.csrf) return to.name === 'g5-login' ? true : { name: 'g5-login', query: { reason: 'security' } };
  if (to.name === 'g5-login') return { name: 'g5-profile' };
  if (to.meta.systemManager && !canManageSystem()) return { name: 'g5-access-denied' };
  return true;
});
router.afterEach(to => { document.title = `${String(to.meta.title ?? '校园空间')} · 校园资源`; });
