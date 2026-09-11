import type { Component } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { Pencil, Repeat, Sparkles, Target } from '@lucide/vue'
import Dashboard from '@/components/Dashboard.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    desc?: string
    icon?: Component
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'dashboard',
    component: Dashboard,
    meta: { title: '投资看板' },
  },
  {
    path: '/assets',
    name: 'assets',
    component: () => import('@/views/AssetsView.vue'),
    meta: { title: '我的资产' },
  },
  {
    path: '/savings',
    name: 'savings',
    component: () => import('@/views/SavingsView.vue'),
    meta: {
      title: '存钱计划',
      desc: '设定储蓄目标与每月存钱节奏，自动跟踪完成进度，让每一笔积蓄都有方向。',
      icon: Target,
    },
  },
  {
    path: '/savings/:id',
    name: 'savings-detail',
    component: () => import('@/views/SavingsDetail.vue'),
    meta: { title: '计划详情' },
  },
  {
    path: '/adjust',
    name: 'adjust',
    component: () => import('@/views/PlaceholderView.vue'),
    meta: {
      title: '修订定投',
      desc: '结合市场估值与个人现金流，一键调整各只基金的定投金额与周期，纪律投资更从容。',
      icon: Repeat,
    },
  },
  {
    path: '/manual-entry',
    name: 'manual-entry',
    component: () => import('@/views/PlaceholderView.vue'),
    meta: {
      title: '手动录入',
      desc: '手动录入每一笔买卖与收益变动，让数据记录更灵活。',
      icon: Pencil,
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
