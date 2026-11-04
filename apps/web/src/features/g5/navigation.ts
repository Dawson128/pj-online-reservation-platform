import type { NavigationItem } from '../../app/route-registry';
export const navigation: NavigationItem[] = [
  {
    "label": "账户管理",
    "to": "/admin/accounts",
    "systemManager": true
  },
  {
    "label": "角色与权限",
    "to": "/admin/roles",
    "systemManager": true
  },
  {
    "label": "院系管理",
    "to": "/admin/departments",
    "systemManager": true
  },
  {
    "label": "全局参数",
    "to": "/admin/config",
    "systemManager": true
  },
  {
    "label": "个人身份",
    "to": "/profile",
    "systemManager": false
  }
];
