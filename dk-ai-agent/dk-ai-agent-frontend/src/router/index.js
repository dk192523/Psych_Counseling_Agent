import { createRouter, createWebHistory } from 'vue-router'
import { ensureMe } from '../stores/auth'

const routes = [
  {
    path: '/',
    redirect: '/psych-master'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: {
      title: '登录 · AI 心理咨询师'
    }
  },
  {
    path: '/psych-master',
    name: 'PsychMaster',
    component: () => import('../views/PsychMaster.vue'),
    meta: {
      title: 'AI 心理咨询师',
      description: 'AI 心理咨询师陪你梳理职场、家庭、关系与成长中的困扰，并一起找到可执行的下一步',
      requiresAuth: true
    }
  },
  {
    path: '/admin',
    name: 'AdminPanel',
    component: () => import('../views/AdminPanel.vue'),
    meta: {
      title: '管理后台 · AI 心理咨询师',
      requiresAuth: true,
      requiresAdmin: true
    }
  },
  {
    // 兜底：未匹配路径回到主页面（由主页面守卫决定是否需要登录）
    path: '/:pathMatch(.*)*',
    redirect: '/psych-master'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫：
// 1. 设置文档标题
// 2. 需认证路由：先确保已拉取 me（模块级缓存，首次懒加载），未认证 → /login?redirect=原路径
// 3. 需 ADMIN 而角色不符 → /psych-master（不暴露管理页存在性的额外提示）
// 4. /login 页已登录 → /psych-master
router.beforeEach(async (to, from, next) => {
  if (to.meta.title) {
    document.title = to.meta.title
  }

  if (to.meta.requiresAuth) {
    const me = await ensureMe()

    if (!me) {
      return next({ path: '/login', query: { redirect: to.fullPath } })
    }

    if (to.meta.requiresAdmin && me.role !== 'ADMIN') {
      return next('/psych-master')
    }

    return next()
  }

  if (to.path === '/login') {
    // 只在"已确认登录态"时跳走；刚登出（fetched=false）的场景让用户留在登录页。
    const me = await ensureMe()
    if (me) {
      return next('/psych-master')
    }
  }

  return next()
})

export default router
