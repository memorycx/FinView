<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import {
  ChevronsLeft,
  ChevronsRight,
  LayoutDashboard,
  Pencil,
  Repeat,
  Sparkles,
  Target,
  Wallet,
} from '@lucide/vue'
import { useRoute } from 'vue-router'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { SIDEBAR_RAIL_W, useSidebar } from '@/composables/useSidebar'

const route = useRoute()
const { collapsed, width, resizing, isDesktop, toggle, setWidth } = useSidebar()

/** 桌面端收起态：仅图标轨 */
const rail = computed(() => isDesktop.value && collapsed.value)

const navItems: { key: string; label: string; icon: Component; to: string }[] = [
  { key: 'dashboard', label: '投资看板', icon: LayoutDashboard, to: '/' },
  { key: 'assets', label: '我的资产', icon: Wallet, to: '/assets' },
  { key: 'savings', label: '存钱计划', icon: Target, to: '/savings' },
  { key: 'adjust', label: '修订定投', icon: Repeat, to: '/adjust' },
  { key: 'manual-entry', label: '手动录入', icon: Pencil, to: '/manual-entry' },
  { key: 'ai', label: 'AI 收益解读', icon: Sparkles, to: '/ai' },
]

/** 侧栏宽度（仅桌面端固定布局生效） */
const asideStyle = computed(() =>
  isDesktop.value
    ? { width: `${collapsed.value ? SIDEBAR_RAIL_W : width.value}px` }
    : undefined,
)

/** 拖拽右边缘调整宽度 */
function startResize(e: MouseEvent) {
  if (!isDesktop.value || collapsed.value) return
  e.preventDefault()
  resizing.value = true
  const startX = e.clientX
  const startW = width.value
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  const onMove = (ev: MouseEvent) => {
    setWidth(startW + ev.clientX - startX)
  }
  const onUp = () => {
    resizing.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}
</script>

<template>
  <aside
    :style="asideStyle"
    class="z-20 border-b border-sidebar-border bg-sidebar backdrop-blur-sm lg:fixed lg:inset-y-0 lg:left-0 lg:flex lg:flex-col lg:overflow-hidden lg:border-b-0 lg:border-r"
    :class="resizing ? '' : 'lg:transition-[width] lg:duration-200 lg:ease-in-out'"
  >
    <!-- 品牌区（移动端与主题切换同行） -->
    <div
      class="flex items-center justify-between px-4 pt-4 lg:px-3 lg:pt-6"
      :class="rail ? 'lg:justify-center lg:px-0' : 'lg:px-4'"
    >
      <div class="flex items-center gap-3">
        <div
          class="flex size-10 shrink-0 items-center justify-center rounded-xl bg-brand text-brand-foreground"
        >
          <span class="font-mono text-lg font-bold">盈</span>
        </div>
        <div v-show="!rail" class="lg:whitespace-nowrap">
          <p class="text-lg font-semibold leading-none">盈析</p>
          <p class="mt-1 text-xs text-muted-foreground">理财数据分析看板</p>
        </div>
      </div>
      <ThemeToggle class="lg:hidden" />
    </div>

    <!-- 导航：移动端横向滚动 / 桌面端纵向菜单并在整列内均匀分布；收起态仅图标 -->
    <nav
      class="flex gap-1 overflow-x-auto px-3 py-3 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden lg:flex-1 lg:flex-col lg:justify-evenly lg:gap-2 lg:overflow-hidden lg:py-2"
      :class="rail ? 'lg:px-2' : 'lg:px-4'"
    >
      <RouterLink
        v-for="item in navItems"
        :key="item.key"
        :to="item.to"
        :title="rail ? item.label : undefined"
        class="flex shrink-0 items-center gap-3 rounded-xl px-4 py-2.5 text-base transition-colors lg:w-full lg:py-3.5"
        :class="[
          rail ? 'lg:justify-center lg:px-0' : '',
          route.name === item.key
            ? 'bg-brand/15 font-bold text-brand'
            : 'font-medium text-muted-foreground hover:bg-muted hover:text-foreground',
        ]"
      >
        <component :is="item.icon" class="size-5 shrink-0" />
        <span v-show="!rail" class="lg:whitespace-nowrap">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <!-- 桌面端底部：收起/展开 + 主题切换 + 注释 -->
    <div class="hidden px-4 pb-5 lg:block" :class="rail ? 'px-2' : ''">
      <!-- 收起 / 展开按钮 -->
      <button
        type="button"
        class="flex w-full items-center gap-3 rounded-xl border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground lg:py-3"
        :class="rail ? 'justify-center px-0' : ''"
        :title="collapsed ? '展开导航栏' : '收起导航栏'"
        @click="toggle"
      >
        <ChevronsLeft v-if="!rail" class="size-5 shrink-0" />
        <ChevronsRight v-else class="size-5 shrink-0" />
        <span v-show="!rail" class="whitespace-nowrap">{{ collapsed ? '' : '收起导航' }}</span>
      </button>

      <div
        class="mt-3 flex items-center justify-between rounded-xl border border-border px-4 py-3"
        :class="rail ? 'justify-center px-0' : ''"
      >
        <span v-show="!rail" class="whitespace-nowrap text-sm text-muted-foreground">深色模式</span>
        <ThemeToggle />
      </div>
      <p
        v-show="!rail"
        class="mt-4 text-center text-xs leading-relaxed text-muted-foreground/70"
      >
        数据仅用于展示与分析<br />不构成任何投资建议
      </p>
    </div>

    <!-- 右边缘拖拽条：仅桌面展开态可见可拖 -->
    <div
      v-if="isDesktop && !collapsed"
      class="group absolute inset-y-0 right-0 z-30 hidden w-2 cursor-col-resize lg:block"
      title="拖拽调整导航栏宽度"
      @mousedown="startResize"
    >
      <div
        class="absolute inset-y-2 right-0 w-0.5 rounded-full transition-colors"
        :class="resizing ? 'bg-brand' : 'bg-transparent group-hover:bg-brand/60'"
      />
    </div>
  </aside>
</template>
