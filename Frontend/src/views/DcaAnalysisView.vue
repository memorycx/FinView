<script setup lang="ts">
import PageFooter from '@/components/PageFooter.vue'
import CashFlowPlanningCard from '@/components/CashFlowPlanningCard.vue'
import CashAccumulationCard from '@/components/CashAccumulationCard.vue'
import DcaCalendarSection from '@/components/DcaCalendarSection.vue'
import { useRequest } from '@/composables/useApi'
import { getCashFlowForecast } from '@/api'
import type { CashFlowForecast } from '@/api/types'

/**
 * 定投分析页：现金流规划（四个数）+ 现金积累预测（月度柱状图 + 余额折线）。
 * 数据全部来自 GET /cashflow/forecast，口径见后端 CashFlowService。
 */
const { data: forecast, error } = useRequest(() => getCashFlowForecast())

/** 「每月固定新增」保存成功后，后端返回重算好的整份预测，两张卡片一起刷新 */
function applyForecast(next: CashFlowForecast) {
  forecast.value = next
}
</script>

<template>
  <div class="mx-auto flex min-h-screen w-full max-w-[1400px] flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 与投资看板的返回按钮行对齐（各页面统一的占位） -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>

    <header class="mb-6 space-y-1.5">
      <h1 class="text-3xl font-bold tracking-tight">定投分析</h1>
      <p class="text-sm text-muted-foreground">按当前定投节奏，预测未来 12 个月的现金流与现金积累。</p>
    </header>

    <div class="flex flex-col gap-5">
      <CashFlowPlanningCard :forecast="forecast" @saved="applyForecast" />
      <CashAccumulationCard :forecast="forecast" @saved="applyForecast" />
      <!-- 逐日明细：月份自己导航，数据独立拉（不受上面的预测卡影响） -->
      <DcaCalendarSection />
    </div>

    <p v-if="error" class="mt-4 text-sm text-destructive">预测数据加载失败：{{ error.message }}</p>

    <PageFooter />
  </div>
</template>
