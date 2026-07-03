import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'Robots',
    path: '/robots',
    component: () => import('#/views/pophie/robots/index.vue'),
    meta: {
      affixTab: true,
      icon: 'lucide:bot',
      order: 1,
      title: '机器人管理',
    },
  },
  {
    name: 'Users',
    path: '/users',
    component: () => import('#/views/pophie/users/index.vue'),
    meta: {
      icon: 'lucide:users',
      order: 2,
      title: '用户管理',
    },
  },
  {
    name: 'Memories',
    path: '/memories',
    component: () => import('#/views/pophie/memories/index.vue'),
    meta: {
      icon: 'lucide:brain',
      order: 3,
      title: '记忆管理',
    },
  },
  {
    name: 'Conversations',
    path: '/conversations',
    component: () => import('#/views/pophie/conversations/index.vue'),
    meta: {
      icon: 'lucide:messages-square',
      order: 4,
      title: '对话历史',
    },
  },
  {
    name: 'PophieConfig',
    path: '/config',
    component: () => import('#/views/pophie/config/index.vue'),
    meta: {
      icon: 'lucide:settings',
      order: 5,
      title: '配置管理',
    },
  },
  {
    name: 'Push',
    path: '/push',
    component: () => import('#/views/pophie/push/index.vue'),
    meta: {
      icon: 'lucide:send',
      order: 6,
      title: '消息推送',
    },
  },
  {
    name: 'Service',
    path: '/service',
    component: () => import('#/views/pophie/service/index.vue'),
    meta: {
      icon: 'lucide:server',
      order: 7,
      title: '服务管理',
    },
  },
];

export default routes;
