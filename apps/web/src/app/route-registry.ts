import type { RouteRecordRaw } from 'vue-router'

export interface NavigationItem { label: string; to: string }
interface RouteModule { routes: RouteRecordRaw[] }
interface NavigationModule { navigation: NavigationItem[] }

// The only module registry. Owners export routes/navigation; no second Router.
const routeModules = import.meta.glob<RouteModule>('../features/g[1-6]/routes.ts', { eager: true })
const menuModules = import.meta.glob<NavigationModule>('../features/g[1-6]/navigation.ts', { eager: true })
export const featureRoutes = Object.keys(routeModules).sort().flatMap(key => routeModules[key]!.routes)
export const featureNavigation = Object.keys(menuModules).sort().flatMap(key => menuModules[key]!.navigation)
