import type { RouteRecordRaw } from 'vue-router';
export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'g5-login', component: () => import('./pages/LoginPage.vue'), meta: { public: true, title: '登录' } },
  { path: '/change-password', name: 'g5-change-password', component: () => import('./pages/ChangePasswordPage.vue'), meta: { title: '修改密码', layout: 'auth' } },
  { path: '/profile', name: 'g5-profile', component: () => import('./pages/ProfilePage.vue'), meta: { title: '个人身份' } },
  { path: '/admin/accounts', name: 'g5-accounts', component: () => import('./pages/AccountsPage.vue'), meta: { title: '账户管理', systemManager: true } },
  { path: '/admin/roles', name: 'g5-roles', component: () => import('./pages/RolesPage.vue'), meta: { title: '角色与权限', systemManager: true } },
  { path: '/admin/departments', name: 'g5-departments', component: () => import('./pages/DepartmentsPage.vue'), meta: { title: '院系管理', systemManager: true } },
  { path: '/admin/config', name: 'g5-config', component: () => import('./pages/ConfigurationPage.vue'), meta: { title: '全局参数', systemManager: true } },
  { path: '/access-denied', name: 'g5-access-denied', component: () => import('./pages/AccessDeniedPage.vue'), meta: { title: '访问受限' } },
];
