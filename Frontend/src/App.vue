<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import { SIDEBAR_RAIL_W, useSidebar } from '@/composables/useSidebar'

const route = useRoute()
const { collapsed, width, resizing, isDesktop } = useSidebar()

/** 认证页面（登录/注册）使用独立全屏布局，不显示 Sidebar */
const isAuthPage = computed(() => route.path === '/login' || route.path === '/register')

/** 主内容区左让位：跟随侧栏宽度（收起态为图标轨宽度）；移动端为 0 */
const mainStyle = computed(() => {
  if (isAuthPage.value) return {}
  return isDesktop.value
    ? { paddingLeft: `${collapsed.value ? SIDEBAR_RAIL_W : width.value}px` }
    : { paddingLeft: '0px' }
})
</script>

<template>
  <div class="min-h-screen bg-background">
    <Sidebar v-if="!isAuthPage" />

    <main
      :style="mainStyle"
      :class="[
        isAuthPage ? '' : (resizing ? '' : 'transition-[padding] duration-200 ease-in-out'),
      ]"
    >
      <RouterView />
    </main>
  </div>
</template>
