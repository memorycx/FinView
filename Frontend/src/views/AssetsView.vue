<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AssetOverview from '@/components/AssetOverview.vue'
import Leaderboard from '@/components/Leaderboard.vue'
import PageFooter from '@/components/PageFooter.vue'
import type { AllocationBucket } from '@/api/types'

const router = useRouter()

/** 点「资产配置结构」里的分类 → 只在该分类里看收益排行（null = 不筛选） */
const bucketFilter = ref<AllocationBucket | null>(null)

/** 点击排行榜基金：跳回投资看板并选中该基金（已归档的直接落在「归档」tab） */
function handleSelect(id: string, archived: boolean) {
  router.push({
    path: '/',
    query: { fund: id, tab: archived ? 'archived' : 'active' },
  })
}
</script>

<template>
  <!-- page-scroll：lg 起锁定一屏（高度 = 视口高度），卡片拉到页面底部并各自内部滚动 -->
  <div class="page-scroll flex w-full flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 占位：与投资看板的返回按钮区对齐，保持页面标题顶部一致 -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>
    <header class="mb-6 shrink-0 space-y-1.5">
      <h1 class="text-3xl font-bold tracking-tight">我的资产</h1>
      <p class="text-sm text-muted-foreground">资产大类配置结构，以及各只持仓的收益率排行</p>
    </header>

    <!-- 高度固定在剩余空间上：两张卡等高、拉到页面底部，基金只数 / 筛选都不再改变卡片高度 -->
    <div class="ds-flex grid gap-4 lg:min-h-0 lg:flex-1 lg:gap-5 lg:grid-cols-[1.6fr_1fr]">
      <!-- 左列：资产配置结构 -->
      <AssetOverview :active-bucket="bucketFilter" @select="bucketFilter = $event" />
      <!-- 右列：排行榜（可按左侧点选的分类筛选，筛选只改内部滚动内容） -->
      <Leaderboard
        :filter-bucket="bucketFilter"
        @select="handleSelect"
        @clear="bucketFilter = null"
      />
    </div>

    <PageFooter />
  </div>
</template>
