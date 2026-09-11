<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { BarChart3, Info, PieChart } from '@lucide/vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart as EChartsPieChart } from 'echarts/charts'
import { TooltipComponent, TitleComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import {
  activeFunds,
  allocation,
  formatCNY,
  formatWan,
  totalAssets,
  type AssetCategory,
} from '@/lib/data'

use([CanvasRenderer, EChartsPieChart, TooltipComponent, TitleComponent])

const palette: Record<AssetCategory, string> = {
  fund: '--chart-1',
  stock: '--chart-3',
  bond: '--chart-4',
  cash: '--chart-5',
}

const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

const data = computed(() =>
  allocation.map((a) => ({
    ...a,
    colorVar: palette[a.category],
  })),
)

const option = computed<EChartsOption>(() => {
  void themeTick.value

  return {
    title: {
      text: formatWan(totalAssets),
      subtext: '总资产',
      left: 'center',
      top: 'center',
      itemGap: 4,
      textStyle: {
        fontSize: 32,
        fontWeight: 700,
        fontFamily: MONO_FONT,
        color: themeColor('--foreground'),
      },
      subtextStyle: {
        fontSize: 14,
        color: themeColor('--muted-foreground'),
      },
    },
    tooltip: {
      trigger: 'item',
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: themeColor('--foreground'), fontSize: 14, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number }
        return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;min-width:120px">
          <span style="color:${themeColor('--muted-foreground')}">${p.name}</span>
          <span style="font-family:${MONO_FONT};font-weight:500;font-variant-numeric:tabular-nums">${formatCNY(p.value)}</span>
        </div>`
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['56%', '84%'],
        padAngle: 2,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: 'transparent', borderWidth: 0 },
        emphasis: { scale: true, scaleSize: 4 },
        data: data.value.map((a) => ({
          name: a.label,
          value: a.value,
          itemStyle: { color: themeColor(a.colorVar) },
        })),
      },
    ],
  }
})

function pctOf(value: number) {
  return ((value / totalAssets) * 100).toFixed(1)
}

// 风险集中度：按个体持仓（含现金储备）降序排列
const holdings = computed(() =>
  [...activeFunds.map((f) => f.current), 128000].sort((a, b) => b - a),
)

/** 最大单一资产占比 */
const maxShare = computed(() =>
  ((Math.max(...holdings.value) / totalAssets) * 100).toFixed(1),
)

/** 前 3 大资产占比 */
const top3Share = computed(() =>
  ((holdings.value.slice(0, 3).reduce((s, v) => s + v, 0) / totalAssets) * 100).toFixed(1),
)

/** 风险集中度（HHI 归一化：0=极度分散，100=高度集中） */
const riskConcentration = computed(() => {
  const n = holdings.value.length
  const hhi = holdings.value.reduce(
    (s, v) => s + (v / totalAssets) ** 2,
    0,
  )
  const normalized = (hhi - 1 / n) / (1 - 1 / n)
  return Math.round(Math.max(0, Math.min(1, normalized)) * 100)
})

/** 风险等级标签与配色 */
const riskLevel = computed(() => {
  const v = riskConcentration.value
  if (v <= 30) return { label: '风险较低', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-400' }
  if (v <= 60) return { label: '风险适中', cls: 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-400' }
  return { label: '风险较高', cls: 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-400' }
})
</script>

<template>
  <Card class="lg:h-full">
    <CardHeader class="gap-1">
      <h2 class="text-xl font-bold">资产配置结构</h2>
      <p class="text-sm text-muted-foreground">按资产大类展示当前持仓的分布情况</p>
    </CardHeader>
    <CardContent class="flex flex-col gap-6">
      <div class="grid flex-1 gap-6 sm:grid-cols-2 sm:items-center">
        <div class="mx-auto aspect-square w-full max-w-[240px] lg:max-w-[300px]">
          <VChart
            class="h-full w-full"
            :option="option"
            autoresize
            :update-options="{ notMerge: true }"
          />
        </div>

        <ul class="space-y-4">
          <li
            v-for="a in data"
            :key="a.category"
            class="flex items-center gap-3"
          >
            <span
              class="size-3 shrink-0 rounded-full"
              :style="{ backgroundColor: `var(${a.colorVar})` }"
              aria-hidden
            />
            <span class="shrink-0 text-base font-medium">{{ a.label }}</span>
            <span class="w-14 shrink-0 font-mono text-base tabular-nums text-muted-foreground">
              {{ pctOf(a.value) }}%
            </span>
            <span class="w-16 shrink-0 text-right font-mono text-base font-semibold tabular-nums">
              {{ formatWan(a.value) }}
            </span>
          </li>
        </ul>
      </div>

      <!-- 风险集中度 -->
      <div
        class="grid grid-cols-1 gap-6 border-t border-border pt-6 sm:grid-cols-[1.5fr_1fr_1fr] sm:items-start sm:gap-8"
      >
        <div class="space-y-1 sm:pr-2">
          <p
            class="flex items-center gap-2 font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums text-foreground"
          >
            {{ riskConcentration }}%
            <span
              class="rounded-full px-3 py-0.5 text-xs font-medium font-sans"
              :class="riskLevel.cls"
            >
              {{ riskLevel.label }}
            </span>
            <button
              type="button"
              class="rounded-full p-0.5 text-muted-foreground transition-colors hover:text-foreground"
              title="基于持仓 HHI 指数计算"
            >
              <Info class="size-3.5 opacity-70" />
            </button>
          </p>
          <div class="pt-1">
            <div class="relative h-2 rounded-full bg-border">
              <div
                class="absolute inset-y-0 left-0 rounded-full bg-brand transition-all duration-500"
                :style="{ width: `${riskConcentration}%` }"
              />
              <div
                class="absolute top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-brand bg-background shadow transition-all duration-500"
                :style="{ left: `${riskConcentration}%` }"
              />
            </div>
            <div class="mt-1.5 flex justify-between text-xs text-muted-foreground">
              <span>低</span>
              <span>高</span>
            </div>
          </div>
          <p class="text-xs text-muted-foreground">衡量资产分散程度</p>
        </div>

        <div class="space-y-1 sm:border-l sm:border-border sm:pl-8">
          <p class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <PieChart class="size-3.5" />
            最大资产占比
          </p>
          <p
            class="font-mono text-2xl font-bold leading-tight tracking-tight tabular-nums text-foreground"
          >
            {{ maxShare }}%
          </p>
        </div>

        <div class="space-y-1 sm:border-l sm:border-border sm:pl-8">
          <p class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <BarChart3 class="size-3.5" />
            前3大资产占比
          </p>
          <p
            class="font-mono text-2xl font-bold leading-tight tracking-tight tabular-nums text-foreground"
          >
            {{ top3Share }}%
          </p>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
