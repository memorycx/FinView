<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import {
  distributionData,
  distributionDimLabel,
  formatCNY,
  formatWan,
  totalAssets,
  type DistributionDim,
} from '@/lib/data'

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent])

const dims: DistributionDim[] = ['industry', 'region', 'currency']
const dimIndex = ref(0)
const dim = computed(() => dims[dimIndex.value])

function cycleDim() {
  dimIndex.value = (dimIndex.value + 1) % dims.length
}

// 滑动选中指示条
const dimBtns = ref<(HTMLElement | null)[]>([])
const dimOverlay = ref({ left: 0, width: 0 })
function updateDimOverlay() {
  const btn = dimBtns.value[dimIndex.value]
  if (btn) {
    dimOverlay.value = { left: btn.offsetLeft, width: btn.offsetWidth }
  }
}
watch(dimIndex, async () => {
  await nextTick()
  updateDimOverlay()
})
onMounted(() => {
  updateDimOverlay()
  window.addEventListener('resize', updateDimOverlay)
})
onUnmounted(() => {
  window.removeEventListener('resize', updateDimOverlay)
})

const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

/** 当前维度数据，按 value 降序排列 */
const sortedData = computed(() =>
  [...distributionData[dim.value]]
    .filter((d) => d.value > 0)
    .sort((a, b) => b.value - a.value),
)

const option = computed<EChartsOption>(() => {
  void themeTick.value

  const data = sortedData.value
  const names = data.map((d) => d.name)
  const values = data.map((d) => d.value)
  const maxVal = Math.max(...values, 1)

  const axisLabelColor = themeColor('--muted-foreground')
  const foreground = themeColor('--foreground')
  const gridColor = themeColor('--border', 0.5)
  const barColor = themeColor('--chart-1')
  const barBgColor = themeColor('--border', 0.35)

  return {
    grid: { left: 8, right: 8, top: 20, bottom: 86, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { show: false },
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: foreground, fontSize: 13, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const p = (Array.isArray(params) ? params[0] : params) as {
          name: string
          value: number
        }
        const pct = ((p.value / totalAssets) * 100).toFixed(1)
        return `<div style="min-width:140px">
          <div style="font-weight:600;font-size:13px;color:${foreground}">${p.name}</div>
          <div style="margin-top:6px;display:flex;justify-content:space-between;gap:20px">
            <span style="color:${axisLabelColor}">市值</span>
            <span style="font-family:${MONO_FONT};font-weight:600;font-variant-numeric:tabular-nums">${formatCNY(p.value)}</span>
          </div>
          <div style="display:flex;justify-content:space-between;gap:20px">
            <span style="color:${axisLabelColor}">占比</span>
            <span style="font-family:${MONO_FONT};font-weight:600;font-variant-numeric:tabular-nums">${pct}%</span>
          </div>
        </div>`
      },
    },
    xAxis: {
      type: 'category',
      data: names,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: axisLabelColor,
        fontSize: 12,
        fontWeight: 500,
        fontFamily: SANS_FONT,
        interval: 0,
        rotate: 0,
      },
    },
    yAxis: {
      type: 'value',
      show: false,
      max: maxVal * 1.15,
    },
    series: [
      {
        type: 'bar',
        data: values,
        barWidth: '42%',
        barGap: '20%',
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: barColor },
              { offset: 1, color: themeColor('--chart-1', 0.5) },
            ],
          },
        },
        emphasis: {
          itemStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: themeColor('--chart-1', 1) },
                { offset: 1, color: themeColor('--chart-1', 0.7) },
              ],
            },
          },
        },
        showBackground: true,
        backgroundStyle: {
          color: barBgColor,
          borderRadius: [6, 6, 0, 0],
        },
        label: {
          show: true,
          position: 'top',
          color: axisLabelColor,
          fontSize: 11,
          fontFamily: MONO_FONT,
          fontWeight: 500,
          formatter: (p: { value: number }) => formatWan(p.value),
        },
      },
    ],
  } as EChartsOption
})
</script>

<template>
  <Card class="lg:h-full">
    <CardHeader class="gap-1">
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-xl font-bold">
            {{ distributionDimLabel[dim] }}分布
          </h2>
          <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">
            资产分布
          </p>
        </div>
        <div
          class="relative inline-flex rounded-xl border border-border bg-background p-1 text-sm"
        >
          <div
            class="absolute top-1 bottom-1 rounded-lg bg-brand shadow-sm transition-all duration-300 ease-out"
            :style="{
              left: `${dimOverlay.left}px`,
              width: `${dimOverlay.width}px`,
            }"
          />
          <button
            v-for="(d, i) in dims"
            :key="d"
            :ref="(el) => (dimBtns[i] = el as HTMLElement | null)"
            type="button"
            class="relative z-10 rounded-lg px-4 py-1.5 font-medium transition-colors"
            :class="
              i === dimIndex
                ? 'text-brand-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            @click="dimIndex = i"
          >
            {{ distributionDimLabel[d] }}
          </button>
        </div>
      </div>
    </CardHeader>
    <CardContent class="pt-2">
      <div
        role="button"
        aria-label="点击切换分布维度"
        class="h-[320px] w-full cursor-pointer sm:h-[360px] lg:h-[400px]"
        @click="cycleDim"
      >
        <VChart
          class="h-full w-full"
          :option="option"
          autoresize
          :update-options="{ notMerge: true }"
        />
      </div>
    </CardContent>
  </Card>
</template>
