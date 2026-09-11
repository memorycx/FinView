<script setup lang="ts">
import { useRouter } from 'vue-router'
import AssetOverview from '@/components/AssetOverview.vue'
import AssetDistribution from '@/components/AssetDistribution.vue'
import Leaderboard from '@/components/Leaderboard.vue'

const router = useRouter()

/** 点击排行榜基金：跳回投资看板并选中该基金 */
function handleSelect(id: string, active: boolean) {
  router.push({
    path: '/',
    query: { fund: id, tab: active ? 'active' : 'archived' },
  })
}
</script>

<template>
  <div class="w-full px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 占位：与投资看板的返回按钮区对齐，保持页面标题顶部一致 -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>
    <header class="mb-6 space-y-1.5">
      <h1 class="text-3xl font-bold tracking-tight">我的资产</h1>
      <p class="text-sm text-muted-foreground">资产大类配置结构，以及各只持仓的收益率排行</p>
    </header>

    <div class="grid gap-4 lg:gap-5 lg:grid-cols-[1.6fr_1fr]">
      <!-- 左列：环形图 + 柱状图（大屏上下堆叠） -->
      <div class="flex flex-col gap-4 lg:gap-5">
        <AssetOverview />
        <AssetDistribution />
      </div>
      <!-- 右列：排行榜 -->
      <Leaderboard @select="handleSelect" />
    </div>
  </div>
</template>
