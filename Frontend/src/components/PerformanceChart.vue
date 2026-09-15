<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  MarkLineComponent,
} from 'echarts/components'
import type { EChartsOption } from 'echarts'
import {
  BarChart3,
  Calendar,
  Coins,
  Info,
  Percent,
  RefreshCw,
  TrendingUp,
  Wallet,
} from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY, formatPct, formatWan } from '@/lib/finance'
import type { DayPoint } from '@/api/types'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, MarkLineComponent])

type Mode = 'value' | 'profit'
type Range = '1' | '3' | '5' | 'all'

const props = defineProps<{
  series: DayPoint[]
  title: string
  subtitle: string
  /** 开始时间 YYYY-MM，用于投资时长/年化收益计算 */
  startDate?: string
}>()

const mode = ref<Mode>('value')
const range = ref<Range>('all')

const rangeOptions: { key: Range; label: string }[] = [
  { key: '1', label: '1年' },
  { key: '3', label: '3年' },
  { key: '5', label: '5年' },
  { key: 'all', label: '全部' },
]

// 滑动选中指示条（时间范围 / 视图模式）
const rangeBtns = ref<(HTMLElement | null)[]>([])
const rangeOverlay = ref({ left: 0, width: 0 })
function updateRangeOverlay() {
  const idx = rangeOptions.findIndex((r) => r.key === range.value)
  const btn = rangeBtns.value[idx]
  if (btn) rangeOverlay.value = { left: btn.offsetLeft, width: btn.offsetWidth }
}
watch(range, async () => {
  await nextTick()
  updateRangeOverlay()
})

const modeBtns = ref<(HTMLElement | null)[]>([])
const modeOverlay = ref({ left: 0, width: 0 })
function updateModeOverlay() {
  const idx = mode.value === 'value' ? 0 : 1
  const btn = modeBtns.value[idx]
  if (btn) modeOverlay.value = { left: btn.offsetLeft, width: btn.offsetWidth }
}
watch(mode, async () => {
  await nextTick()
  updateModeOverlay()
})

function updateOverlays() {
  updateRangeOverlay()
  updateModeOverlay()
}
onMounted(() => {
  updateOverlays()
  window.addEventListener('resize', updateOverlays)
})
onUnmounted(() => {
  window.removeEventListener('resize', updateOverlays)
})

// 主题切换后等待 .dark class 应用完成，再重算颜色
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

const last = computed(() => props.series[props.series.length - 1])
const totalProfit = computed(() => last.value.total - last.value.principal)
const rate = computed(() =>
  last.value.principal === 0 ? 0 : totalProfit.value / last.value.principal,
)
const positive = computed(() => totalProfit.value >= 0)

/** 时间范围过滤后的图表数据 */
const chartSeries = computed(() => {
  if (range.value === 'all') return props.series
  const [ly, lm] = last.value.day.split('-').map(Number)
  const minDay = `${ly - Number(range.value)}-${String(lm).padStart(2, '0')}-01`
  return props.series.filter((p) => p.day >= minDay)
})

const profitData = computed(() =>
  chartSeries.value.map((p) => ({ day: p.day, profit: p.total - p.principal })),
)

/** 汇总统计（始终基于完整区间） */
const stats = computed(() => {
  const startRaw = props.startDate ?? props.series[0].day
  const startDay = startRaw.length === 7 ? `${startRaw}-01` : startRaw
  const [sy, sm] = startDay.split('-').map(Number)
  const [ey, em] = last.value.day.split('-').map(Number)
  const months = (ey - sy) * 12 + (em - sm)
  const years = Math.max(0, Math.floor(months / 12))
  const remMonths = Math.max(0, months % 12)
  const cagr =
    months > 0 && last.value.principal > 0 && last.value.total > last.value.principal
      ? Math.pow(last.value.total / last.value.principal, 12 / months) - 1
      : 0
  return {
    startLabel: startDay,
    startShort: startDay.slice(0, 7),
    years,
    remMonths,
    principal: last.value.principal,
    total: last.value.total,
    profit: totalProfit.value,
    rate: rate.value,
    cagr,
  }
})

function toggleMode() {
  mode.value = mode.value === 'value' ? 'profit' : 'value'
}

function signedCNY(n: number) {
  return (n >= 0 ? '+' : '-') + '¥' + Math.abs(n).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function baseTooltip() {
  return {
    backgroundColor: themeColor('--popover'),
    borderColor: themeColor('--border', 0.5),
    borderWidth: 1,
    padding: [10, 14] as [number, number],
    textStyle: { color: themeColor('--foreground'), fontSize: 14, fontFamily: SANS_FONT },
    extraCssText:
      'border-radius:12px;box-shadow:0 10px 24px rgba(10,37,64,0.15);min-width:200px;',
  }
}

function tipDot(color: string) {
  return `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${color};margin-right:8px"></span>`
}

function tipRow(label: string, value: string, color?: string) {
  return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;margin-top:6px;width:100%">
    <span style="color:${themeColor('--muted-foreground')}">${label}</span>
    <span style="font-family:${MONO_FONT};font-weight:600;font-variant-numeric:tabular-nums;${color ? `color:${color}` : ''}">${value}</span>
  </div>`
}

const option = computed<EChartsOption>(() => {
  // 依赖 themeTick，主题切换时重新读取 CSS 变量
  void themeTick.value

  const axisLabelColor = themeColor('--muted-foreground')
  const foreground = themeColor('--foreground')
  const gridColor = themeColor('--border', 0.7)
  const gainColor = themeColor('--gain')
  const colorTotal = themeColor('--chart-1')
  const colorPrincipal = themeColor('--chart-2')
  const cardColor = themeColor('--card')

  const days = chartSeries.value.map((p) => p.day)
  const axisPointer = {
    type: 'line' as const,
    lineStyle: { type: 'dashed' as const, color: gridColor },
    label: { show: false },
  }

  const baseAxis = {
    grid: { left: 2, right: 4, top: 34, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category' as const,
      data: days,
      boundaryGap: false,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: axisLabelColor,
        margin: 12,
        fontSize: 14,
        fontWeight: 500,
        fontFamily: SANS_FONT,
        // 每个年份只显示一个标签（该年第一个数据点），避免相邻年份文字重叠
        interval: (index: number, value: string) =>
          index === 0 || days[index - 1].slice(0, 4) !== value.slice(0, 4),
        hideOverlap: true,
        formatter: (value: string) => value.slice(0, 4),
      },
    },
    yAxis: {
      type: 'value' as const,
      scale: true,
      name: '金额（万元）',
      nameTextStyle: {
        color: axisLabelColor,
        fontSize: 14,
        align: 'left' as const,
        fontFamily: SANS_FONT,
      },
      nameGap: 12,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { type: 'dashed' as const, color: gridColor } },
      axisLabel: {
        color: axisLabelColor,
        fontFamily: MONO_FONT,
        fontSize: 14,
        fontWeight: 500,
        formatter: (v: number) => formatWan(v),
      },
    },
  }

  if (mode.value === 'value') {
    return {
      ...baseAxis,
      tooltip: {
        ...baseTooltip(),
        trigger: 'axis',
        axisPointer,
        formatter: (params: unknown) => {
          const arr = (Array.isArray(params) ? params : [params]) as Array<{
            seriesName: string
            value: number
            axisValue: string
          }>
          const total = arr.find((p) => p.seriesName === 'total')?.value ?? 0
          const principal = arr.find((p) => p.seriesName === 'principal')?.value ?? 0
          const profit = total - principal
          const r = principal === 0 ? 0 : profit / principal
          return `<div style="min-width:200px">
            <div style="font-weight:700;font-size:14px;color:${foreground};margin-bottom:4px">${(arr[0]?.axisValue ?? '').slice(0, 7)}</div>
            <div style="margin-top:8px"><span style="color:${axisLabelColor}">${tipDot(colorTotal)}</span><span style="color:${axisLabelColor}">总持仓</span></div>
            ${tipRow('', formatCNY(total))}
            <div style="margin-top:8px"><span style="color:${axisLabelColor}">${tipDot(colorPrincipal)}</span><span style="color:${axisLabelColor}">投入本金</span></div>
            ${tipRow('', formatCNY(principal))}
            <div style="border-top:1px solid ${gridColor};margin:10px 0 2px"></div>
            ${tipRow('累计收益', signedCNY(profit), gainColor)}
            ${tipRow('收益率', formatPct(r), gainColor)}
          </div>`
        },
      },
      series: [
        {
          name: 'total',
          type: 'line',
          smooth: false,
          symbol: 'none',
          showSymbol: false,
          data: chartSeries.value.map((p) => p.total),
          lineStyle: { width: 3, color: colorTotal },
          itemStyle: { color: colorTotal, borderColor: cardColor, borderWidth: 2 },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: themeColor('--chart-1', 0.22) },
                { offset: 1, color: themeColor('--chart-1', 0.02) },
              ],
            },
          },
          emphasis: { scale: 1.4 },
          z: 2,
        },
        {
          name: 'principal',
          type: 'line',
          smooth: false,
          symbol: 'none',
          showSymbol: false,
          data: chartSeries.value.map((p) => p.principal),
          lineStyle: { width: 2, type: [6, 5], color: colorPrincipal },
          itemStyle: { color: cardColor, borderColor: colorPrincipal, borderWidth: 2 },
          emphasis: { scale: 1.4 },
          z: 1,
        },
      ],
    }
  }

  return {
    ...baseAxis,
    tooltip: {
      ...baseTooltip(),
      trigger: 'axis',
      axisPointer,
      formatter: (params: unknown) => {
          const arr = (Array.isArray(params) ? params : [params]) as Array<{
            value: number
            axisValue: string
          }>
          const p = arr[0]
          return `<div style="min-width:180px">
          <div style="font-weight:700;font-size:14px;color:${foreground};margin-bottom:4px">${(p?.axisValue ?? '').slice(0, 7)}</div>
          ${tipRow('累计收益', signedCNY(p?.value ?? 0), gainColor)}
        </div>`
      },
    },
    series: [
      {
        name: 'profit',
        type: 'line',
        smooth: false,
        symbol: 'none',
        showSymbol: false,
        data: profitData.value.map((p) => p.profit),
        lineStyle: { width: 3, color: colorTotal },
        itemStyle: { color: colorTotal, borderColor: cardColor, borderWidth: 2 },
        emphasis: { scale: 1.4 },
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: false },
          lineStyle: { type: 'dashed', color: gridColor },
          data: [{ yAxis: 0 }],
        },
      },
    ],
  }
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 界面大标题：与下方内容卡片保持间距 -->
    <div class="mb-5 shrink-0">
      <h1 class="text-3xl font-bold tracking-tight text-foreground">
        {{ title }}
      </h1>
      <p class="mt-1.5 text-xs text-muted-foreground/80">{{ subtitle }}</p>
    </div>

    <Card class="min-h-0 flex-1 overflow-hidden">
      <div class="flex h-full flex-col px-4 pt-6 pb-[18px] sm:px-10 sm:pt-10">
        <!-- 头部：三组 KPI（大标题已移至卡片外） -->
        <div class="flex shrink-0 flex-col gap-4 pt-2">
          <div
            class="grid grid-cols-1 gap-4 sm:flex sm:flex-row sm:items-start sm:justify-between sm:divide-x sm:divide-border"
          >
          <div class="space-y-1 sm:pr-8">
            <p class="text-xs font-medium text-muted-foreground">当前总持仓</p>
            <p class="font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums text-foreground">
              {{ formatCNY(stats.total) }}
            </p>
            <p class="text-xs text-muted-foreground">本金 {{ formatCNY(stats.principal) }}</p>
          </div>
          <div class="space-y-1 sm:px-8">
            <p class="text-xs font-medium text-muted-foreground">累计收益</p>
            <p
              class="font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums"
              :style="{ color: positive ? 'var(--gain)' : 'var(--loss)' }"
            >
              {{ signedCNY(stats.profit) }}
            </p>
            <p
              class="flex items-center gap-1 text-xs font-semibold"
              :style="{ color: positive ? 'var(--gain)' : 'var(--loss)' }"
            >
              {{ formatPct(stats.rate) }}
              <Info class="size-3.5 opacity-70" />
            </p>
          </div>
          <div class="space-y-1 sm:pl-8">
            <p class="text-xs font-medium text-muted-foreground">年化收益率</p>
            <p class="font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums text-foreground">
              {{ (stats.cagr * 100).toFixed(1) }}%
            </p>
            <p class="text-xs text-muted-foreground">(自 {{ stats.startShort }})</p>
          </div>
        </div>
      </div>

      <!-- 工具栏：左时间范围 / 右视图切换+图例 -->
      <div class="mt-4 flex shrink-0 flex-wrap items-center justify-between gap-3">
        <div class="relative inline-flex rounded-xl border border-border bg-background p-1 text-sm">
          <div
            class="absolute top-1 bottom-1 rounded-lg bg-brand shadow-sm transition-all duration-300 ease-out"
            :style="{
              left: `${rangeOverlay.left}px`,
              width: `${rangeOverlay.width}px`,
            }"
          />
          <button
            v-for="(r, i) in rangeOptions"
            :key="r.key"
            :ref="(el) => (rangeBtns[i] = el as HTMLElement | null)"
            type="button"
            class="relative z-10 rounded-lg px-4 py-1.5 font-medium transition-colors"
            :class="
              range === r.key
                ? 'text-brand-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            @click="range = r.key"
          >
            {{ r.label }}
          </button>
        </div>

        <div class="flex items-center gap-5">
          <div class="relative inline-flex rounded-full border border-border bg-background p-1 text-sm">
            <div
              class="absolute top-1 bottom-1 rounded-full bg-brand shadow-sm transition-all duration-300 ease-out"
              :style="{
                left: `${modeOverlay.left}px`,
                width: `${modeOverlay.width}px`,
              }"
            />
            <button
              :ref="(el) => (modeBtns[0] = el as HTMLElement | null)"
              type="button"
              class="relative z-10 rounded-full px-4 py-1.5 font-medium transition-colors"
              :class="
                mode === 'value'
                  ? 'text-brand-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              "
              @click="mode = 'value'"
            >
              本金 vs 总持仓
            </button>
            <button
              :ref="(el) => (modeBtns[1] = el as HTMLElement | null)"
              type="button"
              class="relative z-10 rounded-full px-4 py-1.5 font-medium transition-colors"
              :class="
                mode === 'profit'
                  ? 'text-brand-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              "
              @click="mode = 'profit'"
            >
              收益走势
            </button>
          </div>

          <div class="hidden items-center gap-4 text-xs text-muted-foreground md:flex">
            <span class="flex items-center gap-2">
              <span
                class="inline-block h-[3px] w-6 rounded-full"
                :style="{ backgroundColor: 'var(--chart-1)' }"
              />
              总持仓
            </span>
            <span class="flex items-center gap-2">
              <span
                class="inline-block h-0 w-6 border-t-[3px] border-dashed"
                :style="{ borderColor: 'var(--chart-2)' }"
              />
              投入本金
            </span>
          </div>
        </div>
      </div>

      <!-- 图表区，点击切换视图；桌面端随卡片剩余高度自适应拉伸，保底 240px 防止极端矮窗口压没；移动端保底 380px -->
      <div
        role="button"
        aria-label="点击切换图表视图"
        class="mt-2 block min-h-[380px] w-full flex-1 cursor-pointer lg:min-h-[240px]"
        @click="toggleMode"
      >
        <VChart
          class="h-full w-full"
          :option="option"
          autoresize
          :update-options="{ notMerge: true }"
        />
      </div>

      <!-- 底部统计条 -->
      <div
        class="mt-4 grid shrink-0 grid-cols-2 gap-x-4 gap-y-3 rounded-2xl bg-brand/5 p-4 sm:grid-cols-3"
      >
        <div class="flex items-center gap-3">
          <BarChart3 class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">开始时间</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ stats.startLabel }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Calendar class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">已投资时长</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ stats.years }}年{{ stats.remMonths }}个月
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Wallet class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">累计投入本金</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ formatCNY(stats.principal) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <TrendingUp class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">当前总持仓</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ formatCNY(stats.total) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Coins class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">累计收益</p>
            <p
              class="mt-0.5 font-mono text-sm font-semibold tabular-nums"
              :style="{ color: positive ? 'var(--gain)' : 'var(--loss)' }"
            >
              {{ signedCNY(stats.profit) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Percent class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">收益率</p>
            <p
              class="mt-0.5 font-mono text-sm font-semibold tabular-nums"
              :style="{ color: positive ? 'var(--gain)' : 'var(--loss)' }"
            >
              {{ formatPct(stats.rate) }}
            </p>
          </div>
        </div>
      </div>

      <!-- 页脚注释 -->
      <div
        class="mt-3 flex shrink-0 flex-wrap items-center justify-between gap-2 pb-1 text-xs text-muted-foreground"
      >
        <p>注：数据为历史持仓估算值，仅供参考，不构成投资建议。</p>
        <p class="flex items-center gap-1.5">
          最后更新：2025-01-01 10:24
          <RefreshCw class="size-3.5" />
        </p>
      </div>
      </div>
    </Card>
  </div>
</template>
