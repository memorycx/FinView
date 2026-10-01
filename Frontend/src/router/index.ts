import type { Component } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { Pencil, Sparkles, Target } from '@lucide/vue'
import Dashboard from '@/components/Dashboard.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    desc?: string
    icon?: Component
    /** 是否需要认证才能访问 */
    requiresAuth?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  // 认证页面（不需要登录）
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', requiresAuth: false },
  },

  // 业务页面（需要登录）
  {
    path: '/',
    name: 'dashboard',
    component: Dashboard,
    meta: { title: '投资看板', requiresAuth: true },
  },
  {
    path: '/assets',
    name: 'assets',
    component: () => import('@/views/AssetsView.vue'),
    meta: { title: '我的资产', requiresAuth: true },
  },
  {
    path: '/savings',
    name: 'savings',
    component: () => import('@/views/SavingsView.vue'),
    meta: {
      title: '存钱计划',
      desc: '设定储蓄目标与每月存钱节奏，自动跟踪完成进度，让每一笔积蓄都有方向。',
      icon: Target,
      requiresAuth: true,
    },
  },
  {
    path: '/savings/:id',
    name: 'savings-detail',
    component: () => import('@/views/SavingsDetail.vue'),
    meta: { title: '计划详情', requiresAuth: true },
  },
  {
    path: '/entry',
    name: 'entry',
    component: () => import('@/views/EntryView.vue'),
    meta: {
      title: '录入数据',
      desc: '手动录入每一笔买卖与收益变动，让数据记录更灵活。',
      icon: Pencil,
      requiresAuth: true,
    },
  },
  {
    path: '/ai',
    name: 'ai',
    component: () => import('@/views/PlaceholderView.vue'),
    meta: {
      title: 'AI 收益解读',
      desc: 'AI 智能分析持仓表现、收益归因与风格暴露，用大白话为你解读每一分钱的来去。',
      icon: Sparkles,
      requiresAuth: true,
    },
  },
  // 兜底：未知路径回到投资看板
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

/**
 * 全局前置守卫：
 * - 已登录用户访问 /login、/register → 重定向到首页
 * - 未登录用户访问需要认证的页面 → 重定向到 /login
 */
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('finview_token')
  const isAuth = !!token

  // 已登录 → 访问登录/注册页，跳首页
  if (isAuth && (to.path === '/login' || to.path === '/register')) {
    next('/')
    return
  }

  // 未登录 → 访问需要认证的页面，跳登录
  if (!isAuth && to.meta.requiresAuth !== false) {
    // 允许 /login、/register 正常访问（它们的 requiresAuth 明确设为 false）
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  next()
})
