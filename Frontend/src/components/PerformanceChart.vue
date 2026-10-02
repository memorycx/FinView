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
import { generateAllFundSeries } from '@/api'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY, formatDateTime, formatPct } from '@/lib/finance'
import type { DayPoint } from '@/api/types'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, MarkLineComponent])

type Mode = 'value' | 'profit'
type Range = '1' | '3' | '5' | 'all'

const props = defineProps<{
  series: DayPoint[]
  title: string
  subtitle: string
  /** 开始时间 YYYY-MM，用于「开始时间 / 已投资时长」展示 */
  startDate?: string
  /**
   * 后端算的 XIRR 年化收益率（0.08 = 8%）：按每笔投入实际在场的天数折年，
   * 与上面的「收益率」（期末简单收益率）不是一回事。
   * null = 持有不足 30 天或无法计算，显示「—」
   */
  annualizedRate?: number | null
  /**
   * 页脚「最后更新时间」（后端 asset.last_update_time，ISO 8601）：单只基金是它自己
   * 最后一次生成序列的时刻，组合总览是全部资产里最新的一个；null = 还没有 asset 行，显示「—」
   */
  lastUpdateTime?: string | null
}>()

const emit = defineEmits<{
  /** 序列重算完成，父级重新拉计划列表与组合走势 */
  generated: []
}>()

/**
 * 页脚「最后更新」旁的刷新图标：按调整记录整段重算全部资产的每日序列。
 * 进行中锁点击、图标旋转；结果/错误在图标前短暂提示，4 秒后自动消失。
 */
const refreshing = ref(false)
const refreshMessage = ref<string | null>(null)
let refreshTimer: ReturnType<typeof setTimeout> | undefined

async function handleRefreshAll() {
  if (refreshing.value) return
  refreshing.value = true
  refreshMessage.value = null
  try {
    const count = await generateAllFundSeries()
    refreshMessage.value = count > 0 ? `已更新 ${count} 只资产的序列` : '没有可更新的资产'
    emit('generated')
  } catch (e) {
    refreshMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    refreshing.value = false
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => (refreshMessage.value = null), 4000)
  }
}

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
  clearTimeout(refreshTimer)
})

// 主题切换后等待 .dark class 应用完成，再重算颜色
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

// watch(
//   () => props.series,
//   (newSeries, oldSeries) => {
//     console.log('props.series changed:', newSeries)
//     console.log('old series:', oldSeries)
//     console.log('last:', newSeries[newSeries.length - 1])
//     console.log('======================================')

//     console.log('chartSeries', chartSeries.value)
//   },
//   { immediate: true, deep: true }
// )

const last = computed<DayPoint | undefined>(() => props.series[props.series.length - 1])

/**
 * 有没有数据：新用户 / 空看板时 series 为空数组，整个组件必须照常渲染出**框架**，
 * 只把需要数据的位置换成「—」（模板里统一走 cny / signed / pct 这几个包装）。
 */
const hasData = computed(() => !!last.value)

/** 空看板的占位：与年化收益率的 null 占位（—）保持同一套约定 */
const cny = (n: number) => (hasData.value ? formatCNY(n) : '—')
const signed = (n: number) => (hasData.value ? signedCNY(n) : '—')
const pct = (n: number) => (hasData.value ? formatPct(n) : '—')

/** 截至当日的累计手续费：按全量 series 累加（与时间范围筛选无关，否则区间内的收益会漏掉更早的手续费） */
const feeCumulative = computed(() => {
  const map = new Map<string, number>()
  let cum = 0
  for (const p of props.series) {
    cum += p.fee
    map.set(p.day, cum)
  }
  return map
})
const cumFee = (day: string) => feeCumulative.value.get(day) ?? 0

/** 累计收益 = 市值 − 本金 − 累计手续费（本金是净投入，卖出已冲减，所以跨清仓连续） */
const lastFee = computed(() => (last.value ? cumFee(last.value.day) : 0))
const totalProfit = computed(() =>
  last.value ? last.value.total - last.value.principal - lastFee.value : 0,
)

/** 当日的累计投入：后端给 invested；mock 的手写序列没有它，按「本金 + 当日累计手续费」兜底 */
const investedOn = (p: DayPoint) => p.invested ?? p.principal + cumFee(p.day)

/**
 * 收益率 = 累计收益 / 累计投入。
 * 分母不用「本金 + 手续费」：清过仓的基金本金会变成负数，那样会算出 +297% 这种假数字
 * （口径见 CLAUDE.md；没清过仓时两个分母相等）
 */
const rate = computed(() => {
  if (!last.value) return 0
  const invested = investedOn(last.value)
  return invested === 0 ? 0 : totalProfit.value / invested
})
const positive = computed(() => totalProfit.value >= 0)

/** 累计收益 / 收益率的涨跌配色；空看板显示「—」时用灰字，别把占位符染成红绿 */
const gainStyle = computed(() => ({
  color: !hasData.value
    ? 'var(--muted-foreground)'
    : positive.value
      ? 'var(--gain)'
      : 'var(--loss)',
}))

/**
 * 整段序列的市值都等于本金 = 这只基金还没有抓到过净值（估值兜底为 1）：
 * 图表是两条重叠的平线、收益恒 0，和「真的零收益」长得一样，给个提示避免误判成没数据。
 */
const navPending = computed(
  () =>
    props.series.length > 0 &&
    props.series.every((p) => Math.abs(p.total - p.principal) < 0.005),
)



/** 时间范围过滤后的图表数据 */
const chartSeries = computed(() => {
  if (range.value === 'all' || !last.value) return props.series
  const [ly, lm] = last.value.day.split('-').map(Number)
  const minDay = `${ly - Number(range.value)}-${String(lm).padStart(2, '0')}-01`
  return props.series.filter((p) => p.day >= minDay)
})



const profitData = computed(() =>
  chartSeries.value.map((p) => ({
    day: p.day,
    profit: p.total - p.principal - cumFee(p.day),
  })),
)

/**
 * 纵坐标单位：按**当前图上实际的金额量级**自动选 元 / 万 / 亿。
 *
 * 单位要跟着图上的数走，不能写死：一只几千块的小基金按「万元」画，刻度全是 0.00；
 * 而上了亿按万元画又会变成 12345.67 这种长数字。收益走势按收益的量级单独判断
 * （收益通常比市值小两个数量级，跟着市值走会让刻度又变回 0.00）。
 *
 * 换算后上百就取整、上十保留一位、更小保留两位，保证刻度短且整根轴位数一致。
 */
const unitSteps = [
  { label: '亿', factor: 1e8 },
  { label: '万', factor: 1e4 },
  { label: '元', factor: 1 },
] as const

const axisUnit = computed(() => {
  const values =
    mode.value === 'value'
      ? chartSeries.value.flatMap((p) => [p.total, p.principal])
      : profitData.value.map((p) => p.profit)
  const peak = values.reduce((max, v) => Math.max(max, Math.abs(v)), 0)

  const step = unitSteps.find((s) => peak >= s.factor) ?? unitSteps[unitSteps.length - 1]
  const scaled = peak / step.factor
  return { ...step, digits: scaled >= 100 ? 0 : scaled >= 10 ? 1 : 2 }
})

/** 纵轴刻度：换算到当前单位，按 unit.digits 固定小数位（不带单位后缀，单位写在轴名里） */
function formatAxisValue(v: number, unit: { factor: number; digits: number }) {
  return (v / unit.factor).toLocaleString('zh-CN', {
    minimumFractionDigits: unit.digits,
    maximumFractionDigits: unit.digits,
  })
}

/** 汇总统计（始终基于完整区间）；空看板时全是占位值，由模板的 cny / signed / pct / duration 转成「—」 */
const stats = computed(() => {
  if (!last.value) {
    return {
      startLabel: '—',
      startShort: '—',
      years: 0,
      remMonths: 0,
      principal: 0,
      total: 0,
      profit: 0,
      rate: 0,
    }
  }
  const startRaw = props.startDate ?? props.series[0].day
  const startDay = startRaw.length === 7 ? `${startRaw}-01` : startRaw
  const [sy, sm] = startDay.split('-').map(Number)
  const [ey, em] = last.value.day.split('-').map(Number)
  const months = (ey - sy) * 12 + (em - sm)
  const years = Math.max(0, Math.floor(months / 12))
  const remMonths = Math.max(0, months % 12)
  return {
    startLabel: startDay,
    startShort: startDay.slice(0, 7),
    years,
    remMonths,
    principal: last.value.principal,
    total: last.value.total,
    profit: totalProfit.value,
    rate: rate.value,
  }
})

/** 已投资时长文案；空看板显示「—」（0年0个月 会看起来像真有数据） */
const durationLabel = computed(() =>
  hasData.value ? `${stats.value.years}年${stats.value.remMonths}个月` : '—',
)

function toggleMode() {
  mode.value = mode.value === 'value' ? 'profit' : 'value'
}

/** 年化收益率：后端 XIRR，null（持有不足 1 个月 / 无法计算）显示「—」；负数自带负号 */
function formatAnnualized(n: number | null | undefined) {
  return n == null ? '—' : `${(n * 100).toFixed(1)}%`
}

function signedCNY(n: number) {
  return (n >= 0 ? '+' : '-') + '¥' + Math.abs(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
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

  // 根据时间跨度决定 X 轴标签显示 YYYY-MM / YYYY
const axisLabels = computed(() => {
  const dates = chartSeries.value.map((p) => p.day)

  if (dates.length < 2) return dates

  const start = new Date(`${dates[0]}T00:00:00`)
  const end = new Date(`${dates[dates.length - 1]}T00:00:00`)

  const monthsDiff =
    (end.getFullYear() - start.getFullYear()) * 12 +
    (end.getMonth() - start.getMonth()) +  1

  if (monthsDiff < 5) {
    return dates.map((day, index) => {
      const currentMonth = day.slice(0, 7)
      if (index === 0) {
        return currentMonth
      }
      const previousMonth = dates[index - 1].slice(0, 7)
      return currentMonth !== previousMonth ? currentMonth : ''
    })
  }

  return dates.map((day, index) => {
    const currentYear = day.slice(0, 4)

    if (index === 0) {
      return currentYear
    }
    const previousYear = dates[index - 1].slice(0, 4)
    return currentYear !== previousYear ? currentYear : ''
  })
})

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
        formatter: (_value: string, index: number) => axisLabels.value[index] ?? '',
        // 日度数据点密集，交由 ECharts 自动抽稀，标签展示完整 YYYY-MM-DD
        hideOverlap: true,
      },
    },
    yAxis: {
      type: 'value' as const,
      scale: true,
      // 单位随量级自适应，见 axisUnit；收益走势单独标「收益」
      name: `${mode.value === 'value' ? '金额' : '收益'}（${axisUnit.value.label}）`,
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
        formatter: (v: number) => formatAxisValue(v, axisUnit.value),
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
          const day = arr[0]?.axisValue ?? ''
          const point = chartSeries.value.find((p) => p.day === day)
          const total = arr.find((p) => p.seriesName === 'total')?.value ?? 0
          const principal = arr.find((p) => p.seriesName === 'principal')?.value ?? 0
          const fee = cumFee(day)
          const profit = total - principal - fee
          const invested = point ? investedOn(point) : principal + fee
          const r = invested === 0 ? 0 : profit / invested
          return `<div style="min-width:200px">
            <div style="font-weight:700;font-size:14px;color:${foreground};margin-bottom:4px">${arr[0]?.axisValue ?? ''}</div>
            <div style="margin-top:8px"><span style="color:${axisLabelColor}">${tipDot(colorTotal)}</span><span style="color:${axisLabelColor}">总持仓</span></div>
            ${tipRow('', formatCNY(total))}
            <div style="margin-top:8px"><span style="color:${axisLabelColor}">${tipDot(colorPrincipal)}</span><span style="color:${axisLabelColor}">投入本金</span></div>
            ${tipRow('', formatCNY(principal))}
            <div style="border-top:1px solid ${gridColor};margin:10px 0 2px"></div>
            ${tipRow('累计收益', signedCNY(profit), gainColor)}
            ${tipRow('累计手续费', formatCNY(fee))}
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
          <div style="font-weight:700;font-size:14px;color:${foreground};margin-bottom:4px">${p?.axisValue ?? ''}</div>
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
              {{ cny(stats.total) }}
            </p>
            <p class="text-xs text-muted-foreground">
              本金 {{ cny(stats.principal) }} · 手续费 {{ cny(lastFee) }}
            </p>
          </div>
          <div class="space-y-1 sm:px-8">
            <p class="text-xs font-medium text-muted-foreground">累计收益</p>
            <p
              class="font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums"
              :style="gainStyle"
            >
              {{ signed(stats.profit) }}
            </p>
            <p
              class="flex items-center gap-1 text-xs font-semibold"
              :style="gainStyle"
            >
              {{ pct(stats.rate) }}
              <Info class="size-3.5 opacity-70" />
            </p>
          </div>
          <div class="space-y-1 sm:pl-8">
            <p class="text-xs font-medium text-muted-foreground">年化收益率</p>
            <p
              class="font-mono text-4xl font-bold leading-tight tracking-tight tabular-nums text-foreground"
              :title="annualizedRate == null ? '持有不足 1 个月或现金流无法求解，暂不显示年化' : undefined"
            >
              {{ formatAnnualized(annualizedRate) }}
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
          <span
            v-if="navPending"
            class="rounded-full border border-border bg-muted/60 px-3 py-1 text-xs text-muted-foreground"
            title="该基金还没有抓到净值：市值暂按本金计，登录时会自动补抓"
          >
            净值待更新 · 市值暂按本金计
          </span>
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
        class="relative mt-2 block min-h-[380px] w-full flex-1 cursor-pointer lg:min-h-[240px]"
        @click="toggleMode"
      >
        <VChart
          class="h-full w-full"
          :option="option"
          autoresize
          :update-options="{ notMerge: true }"
        />
        <!-- 空看板：坐标轴框架留着，中间给一句「暂无数据」，别让空网格看起来像加载失败 -->
        <p
          v-if="!hasData"
          class="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-muted-foreground"
        >
          暂无数据
        </p>
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
              {{ durationLabel }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Wallet class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">累计投入本金</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ cny(stats.principal) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <TrendingUp class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">当前总持仓</p>
            <p class="mt-0.5 font-mono text-base font-semibold tabular-nums">
              {{ cny(stats.total) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Coins class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">累计收益</p>
            <p
              class="mt-0.5 font-mono text-sm font-semibold tabular-nums"
              :style="gainStyle"
            >
              {{ signed(stats.profit) }}
            </p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Percent class="size-6 shrink-0 text-brand" />
          <div class="min-w-0">
            <p class="text-xs text-muted-foreground">收益率</p>
            <p
              class="mt-0.5 font-mono text-sm font-semibold tabular-nums"
              :style="gainStyle"
            >
              {{ pct(stats.rate) }}
            </p>
          </div>
        </div>
      </div>

      <!-- 页脚注释 -->
      <div
        class="mt-3 flex shrink-0 flex-wrap items-center justify-between gap-2 pb-1 text-xs text-muted-foreground"
      >
        <p>注：数据为历史持仓估算值，仅供参考，不构成投资建议。</p>
        <div class="flex items-center gap-2">
          <p v-if="refreshMessage" class="text-brand">
            {{ refreshMessage }}
          </p>
          <p class="flex items-center gap-1.5">
            最后更新：{{ formatDateTime(lastUpdateTime) ?? '—' }}
            <button
              type="button"
              class="inline-flex items-center rounded transition-colors hover:text-foreground disabled:opacity-50"
              :disabled="refreshing"
              :title="
                refreshMessage
                  ?? '按调整记录整段重算全部资产的每日序列（asset_series），净值在后台异步补'
              "
              :aria-label="refreshMessage ?? '更新全部资产序列'"
              @click="handleRefreshAll"
            >
              <RefreshCw class="size-3.5" :class="refreshing && 'animate-spin'" />
            </button>
          </p>
        </div>
      </div>
      </div>
    </Card>
  </div>
</template>
