<script setup lang="ts">
import { computed } from 'vue'
import Sidebar from '@/components/Sidebar.vue'
import { SIDEBAR_RAIL_W, useSidebar } from '@/composables/useSidebar'

const { collapsed, width, resizing, isDesktop } = useSidebar()

/** 主内容区左让位：跟随侧栏宽度（收起态为图标轨宽度）；移动端为 0 */
const mainStyle = computed(() =>
  isDesktop.value
    ? {
        paddingLeft: `${collapsed.value ? SIDEBAR_RAIL_W : width.value}px`,
      }
    : { paddingLeft: '0px' },
)
</script>

<template>
  <div class="min-h-screen bg-background">
    <Sidebar />

    <main
      :style="mainStyle"
      :class="resizing ? '' : 'transition-[padding] duration-200 ease-in-out'"
    >
      <RouterView />
    </main>
  </div>
</template>
