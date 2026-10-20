<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { CalendarClock } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY } from '@/lib/finance'
import type { SavingsInflowForecast } from '@/api/types'

/**
 * 存钱计划详情 →「未来资金流入」卡片。
 *
 * 数据来自计划详情响应里的 inflowForecast（后端 CashFlowService.planDca）：
 * 下月起 12 个完整自然月、只算周期定投（action=1/2）、金额 = amount + fee、
 * 按实际扣款日归月（遇非交易日顺延到哪天就记在哪个自然月）。
 *
 * 视觉约定（用户明确要求）：
 * - 柱状图：每期按资产堆叠，不同颜色代表不同资产，**右轴**；
 * - 折线：窗口内累计流入，**左轴**；
 * - 横轴默认按月，右上角可切换按年（12 个月按自然年聚合，首尾年份可能不完整）。
 *
 * 双 Y 轴本身容易误读，所以轴名写清「累计 / 每期」，tooltip 里两个口径都给出；
 * 图例不可点击隐藏，避免隐藏某只资产后累计线与柱子对不上。
 *
 * 顶部三个数（12 个月合计 / 月均 / 单月最高）与自制图例（颜色 + 资产名 + 12 个月合计）
 * 都是整个窗口的口径，切换按月/按年时保持不变；按年只改变图表的聚合粒度，
 * 不完整的年份在横轴第二行标出实际月份范围。
 */
const props = defineProps<{ forecast: SavingsInflowForecast }>()

use([CanvasRenderer, BarChart, LineChart, GridComponent, TooltipComponent])

const VIEWS = [
  { key: 'month' as const, label: '按月' },
  { key: 'year' as const, label: '按年' },
] as const

const view = ref<'month' | 'year'>('month')

/** 主题切换后重算 canvas 颜色（与页内其他 ECharts 同一套做法） */
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

function round2(n: number) {
  return Math.round(n * 100) / 100
}

interface Bucket {
  /** 2026-11 或 2026 */
  key: string
  /** 横轴标签：26/11 或 2026年 */
  label: string
  /** tooltip 标题：2026-11 / 2026年（11-12月） */
  tip: string
  /** 按年视图的横轴第二行：窗口内不完整的年份标出实际月份范围 */
  subLabel?: string
  total: number
  byAsset: Record<string, number>
}

/** 按月 / 按年聚合；累计线也在这里按同一组桶累加 */
const buckets = computed<Bucket[]>(() => {
  if (view.value === 'month') {
    return props.forecast.months.map((month) => ({
      key: month.month,
      label: month.month.slice(2).replace('-', '/'),
      tip: month.month,
      total: round2(month.total),
      byAsset: month.byAsset,
    }))
  }

  const byYear = new Map<string, { total: number; byAsset: Record<string, number>; months: string[] }>()
  for (const month of props.forecast.months) {
    const year = month.month.slice(0, 4)
    const bucket = byYear.get(year) ?? { total: 0, byAsset: {}, months: [] }
    bucket.total += month.total
    bucket.months.push(month.month)
    for (const [code, amount] of Object.entries(month.byAsset)) {
      bucket.byAsset[code] = round2((bucket.byAsset[code] ?? 0) + amount)
    }
    byYear.set(year, bucket)
  }
  return [...byYear.entries()].map(([year, bucket]) => {
    const first = Number(bucket.months[0]?.slice(5) ?? 1)
    const last = Number(bucket.months[bucket.months.length - 1]?.slice(5) ?? 12)
    return {
      key: year,
      label: `${year}年`,
      // 窗口从下月开始，按年时首尾年份往往是残缺的：tooltip 与横轴都如实标出月份范围
      tip: bucket.months.length === 12 ? `${year}年` : `${year}年（${first}-${last}月）`,
      subLabel: bucket.months.length === 12 ? undefined : `${first}-${last}月`,
      total: round2(bucket.total),
      byAsset: bucket.byAsset,
    }
  })
})

const cumulative = computed(() => {
  let sum = 0
  return buckets.value.map((bucket) => round2((sum += bucket.total)))
})

const hasInflow = computed(() => props.forecast.months.some((month) => month.total > 0))
const totalInflow = computed(() =>
  round2(props.forecast.months.reduce((sum, month) => sum + month.total, 0)),
)
const monthlyAverage = computed(() => round2(totalInflow.value / 12))

/** 单月最高：窗口恒为 12 个完整月，直接按月份桶取最大 */
const peakMonth = computed(() => {
  let best = props.forecast.months[0]
  for (const month of props.forecast.months) {
    if (month.total > best.total) best = month
  }
  return best
})

const peakMonthLabel = computed(() => {
  const [year, month] = (peakMonth.value?.month ?? '').split('-')
  return year && month ? `${year}年${Number(month)}月` : ''
})

/**
 * 自制图例：每只资产的颜色 + 窗口内 12 个月合计（按金额降序）。
 * ECharts 自带图例只能放名字，既挤又读不出贡献；这里放在图表上方、两列对齐，
 * 颜色与图表、计划资产行的圆点同源（colorVar）。图例不可点击隐藏——
 * 累计线依赖全部资产，隐藏任意一根柱子都会让两条口径对不上。
 */
const assetTotals = computed(() => {
  const totals = new Map<string, number>()
  for (const month of props.forecast.months) {
    for (const [code, amount] of Object.entries(month.byAsset)) {
      totals.set(code, round2((totals.get(code) ?? 0) + amount))
    }
  }
  return props.forecast.assets
    .map((asset) => ({ ...asset, total: totals.get(asset.code) ?? 0 }))
    .filter((asset) => asset.total > 0)
    .sort((a, b) => b.total - a.total)
})

/** 图例里的金额用紧凑写法，免得四只资产的名字 + 完整金额排不下 */
function compactCNY(n: number) {
  if (Math.abs(n) >= 10000) {
    return `¥${(n / 10000).toLocaleString('zh-CN', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 2,
    })}万`
  }
  return `¥${Math.round(n).toLocaleString('zh-CN')}`
}

/* ==================== tooltip / 轴格式化 ==================== */

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/** 资产名是用户输入，拼进 tooltip 的 HTML 前必须转义 */
function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (ch) => HTML_ESCAPES[ch] ?? ch)
}

function tipRow(label: string, value: string, color?: string) {
  const dot = color
    ? `<span style="width:7px;height:7px;border-radius:50%;background:${color};flex:none"></span>`
    : ''
  return `<div style="display:flex;align-items:center;justify-content:space-between;gap:20px;margin-top:5px;width:100%">
    <span style="display:inline-flex;align-items:center;gap:6px;color:${themeColor('--muted-foreground')}">${dot}${escapeHtml(label)}</span>
    <span style="font-family:${MONO_FONT};font-weight:600;font-variant-numeric:tabular-nums;color:${themeColor('--foreground')}">${value}</span>
  </div>`
}

/**
 * 纵轴单位：按当前轴上的最大量级自动选 元 / 万 / 亿（左右轴各自判断），
 * 单位写进轴名；0 不显示成「0.00万」。
 */
function moneyAxis(max: number) {
  const peak = Math.abs(max)
  const [factor, unit] = peak >= 1e8 ? [1e8, '亿'] : peak >= 1e4 ? [1e4, '万'] : [1, '元']
  const scaled = peak / factor
  const digits = scaled >= 100 ? 0 : scaled >= 10 ? 1 : 2
  return {
    unit,
    format: (value: number) => {
      const n = Number(value) || 0
      if (n === 0) return '0'
      return (n / factor).toLocaleString('zh-CN', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      })
    },
  }
}

/* ==================== 图表 ==================== */

const chartOption = computed<EChartsOption>(() => {
  void themeTick.value

  const rows = buckets.value
  const assets = props.forecast.assets
  const cumulativeValues = cumulative.value
  const barPeak = Math.max(1, ...rows.map((bucket) => bucket.total))
  const cumulativePeak = Math.max(1, ...cumulativeValues)
  const barAxis = moneyAxis(barPeak)
  const cumulativeAxis = moneyAxis(cumulativePeak)

  const axisText = themeColor('--muted-foreground')
  const foreground = themeColor('--foreground')
  const gridColor = themeColor('--border', 0.6)
  const cardColor = themeColor('--card')
  // 资产配色用 --chart-1/3/4/5；折线固定用保留的 --chart-2，避免和柱子撞色
  const lineColor = themeColor('--chart-2')
  const barWidth = view.value === 'year' ? 64 : 28
  // 每根柱子最上面那段做 4px 圆角（数据端），下面与基线相接的段保持直角
  const topCode = rows.map((bucket) => {
    for (let i = assets.length - 1; i >= 0; i--) {
      if ((bucket.byAsset[assets[i].code] ?? 0) > 0) return assets[i].code
    }
    return null
  })

  const barSeries = assets.map((asset) => ({
    name: `${asset.name} · ${asset.code}`,
    type: 'bar' as const,
    stack: 'inflow',
    yAxisIndex: 1,
    barMaxWidth: barWidth,
    itemStyle: {
      color: themeColor(asset.colorVar || '--chart-1'),
      // 2px 卡片底色描边 = 堆叠段之间的缝隙，颜色相近时也能分开
      borderColor: cardColor,
      borderWidth: 2,
    },
    emphasis: { focus: 'series' as const },
    data: rows.map((bucket, index) => {
      const value = round2(bucket.byAsset[asset.code] ?? 0)
      return topCode[index] === asset.code
        ? { value, itemStyle: { borderRadius: [4, 4, 0, 0] } }
        : value
    }),
  }))

  const lineSeries = {
    name: '累计流入（左轴）',
    type: 'line' as const,
    yAxisIndex: 0,
    data: cumulativeValues,
    smooth: false,
    symbol: 'circle',
    symbolSize: 8,
    showSymbol: true,
    lineStyle: { width: 2, color: lineColor },
    itemStyle: { color: lineColor, borderColor: cardColor, borderWidth: 2 },
    z: 10,
  }

  return {
    animationDuration: 400,
    grid: { left: 8, right: 8, top: 36, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: themeColor('--muted', 0.35) } },
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [10, 14],
      textStyle: { color: foreground, fontSize: 14, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:12px;box-shadow:0 10px 24px rgba(10,37,64,0.15);min-width:230px;',
      formatter: (raw: unknown) => {
        const params = (Array.isArray(raw) ? raw : [raw]) as Array<{ dataIndex: number }>
        const index = params[0]?.dataIndex ?? 0
        const bucket = rows[index]
        if (!bucket) return ''
        const contributors = assets
          .map((asset) => ({ asset, value: round2(bucket.byAsset[asset.code] ?? 0) }))
          .filter((item) => item.value > 0)
          .sort((a, b) => b.value - a.value)
        const rowsHtml = contributors
          .map((item) =>
            tipRow(
              `${item.asset.name} · ${item.asset.code}`,
              formatCNY(item.value),
              themeColor(item.asset.colorVar || '--chart-1'),
            ),
          )
          .join('')
        return `<div style="font-weight:600;font-size:14px">${escapeHtml(bucket.tip)}</div>`
          + (rowsHtml || tipRow('本期无定投', formatCNY(0)))
          + `<div style="margin-top:8px;padding-top:8px;border-top:1px solid ${themeColor('--border', 0.5)}">`
          + tipRow(view.value === 'year' ? '年度合计' : '当月合计', formatCNY(bucket.total))
          + tipRow('累计流入（左轴）', formatCNY(cumulativeValues[index] ?? 0), lineColor)
          + '</div>'
      },
    },
    xAxis: {
      type: 'category',
      data: rows.map((bucket) => bucket.label),
      axisTick: { show: false },
      axisLine: { show: true, lineStyle: { color: gridColor } },
      axisLabel: {
        color: axisText,
        fontFamily: SANS_FONT,
        fontSize: 13,
        lineHeight: 16,
        interval: 0,
        hideOverlap: true,
        // 按年时首尾年份往往只有几个月，横轴第二行直接标出实际范围，避免误读成整年
        formatter: (value: string, index: number) => {
          const sub = rows[index]?.subLabel
          return sub ? `${value}\n${sub}` : value
        },
      },
    },
    yAxis: [
      {
        type: 'value',
        position: 'left',
        name: `累计流入（${cumulativeAxis.unit}）`,
        nameLocation: 'end',
        nameGap: 8,
        nameTextStyle: { color: axisText, fontSize: 12, fontFamily: SANS_FONT },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: axisText,
          fontFamily: MONO_FONT,
          fontSize: 12,
          formatter: cumulativeAxis.format,
        },
        splitLine: { show: false },
      },
      {
        type: 'value',
        position: 'right',
        name: `每期流入（${barAxis.unit}）`,
        nameLocation: 'end',
        nameGap: 8,
        nameTextStyle: { color: axisText, fontSize: 12, fontFamily: SANS_FONT },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: axisText,
          fontFamily: MONO_FONT,
          fontSize: 12,
          formatter: barAxis.format,
        },
        splitLine: { show: true, lineStyle: { color: gridColor, type: 'dashed' } },
      },
    ],
    series: [...barSeries, lineSeries],
  }
})
</script>

<template>
  <Card class="gap-0 p-0">
    <CardHeader class="px-6 pt-6">
      <!-- CardHeader 本身是 grid，直接往上加 flex 会被 grid 覆盖，所以包一层 flex -->
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="space-y-1">
          <h2 class="text-lg font-bold">未来资金流入</h2>
          <p class="text-sm text-muted-foreground">
            {{ forecast.fromMonth }} ~ {{ forecast.toMonth }} · 只算定投扣款，不含涨跌
          </p>
        </div>
        <div class="inline-flex rounded-xl border border-border bg-background p-1 text-sm">
          <button
            v-for="tab in VIEWS"
            :key="tab.key"
            type="button"
            class="rounded-lg px-3 py-1 text-sm font-medium transition-colors"
            :aria-pressed="view === tab.key"
            :class="
              view === tab.key
                ? 'bg-brand text-brand-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            @click="view = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
    </CardHeader>

    <CardContent class="px-6 pb-6 pt-2">
      <!-- 没有定投流入时不要画空坐标系，直接把原因说清楚 -->
      <div
        v-if="!hasInflow"
        class="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border px-4 py-10 text-center"
      >
        <span class="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
          <CalendarClock class="size-5" />
        </span>
        <p class="text-sm font-medium text-foreground">未来 12 个月没有定投扣款</p>
        <p class="max-w-md text-xs leading-relaxed text-muted-foreground">
          给计划内的资产添加「定投开始」记录后，这里会显示每月扣款预测。
        </p>
      </div>

      <template v-else>
        <!--
          关键数字排成一行：窗口合计最显眼，月均与单月峰值补充节奏。
          三列必须用 minmax(0, …)，否则 fr 轨道的 min-content（金额不换行）会把窄容器撑破；
          字号到 xl 才放大 —— 1024 下卡片内容区只有 ~610px（正文列有 max-width），
          三列用大号字会横向溢出。
        -->
        <div
          class="mb-5 grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)] xl:gap-x-8"
        >
          <div class="col-span-2 min-w-0 sm:col-span-1">
            <p class="text-xs font-medium text-muted-foreground">未来 12 个月合计</p>
            <p class="mt-1 font-mono text-2xl font-bold tracking-tight tabular-nums xl:text-3xl">
              {{ formatCNY(totalInflow) }}
            </p>
          </div>
          <div class="min-w-0 sm:border-l sm:border-border sm:pl-6">
            <p class="text-xs font-medium text-muted-foreground">月均流入</p>
            <p class="mt-1 font-mono text-lg font-bold tracking-tight tabular-nums sm:text-xl xl:text-2xl">
              {{ formatCNY(monthlyAverage) }}
            </p>
          </div>
          <div class="min-w-0 sm:border-l sm:border-border sm:pl-6">
            <p class="text-xs font-medium text-muted-foreground">单月最高</p>
            <p class="mt-1 font-mono text-lg font-bold tracking-tight tabular-nums sm:text-xl xl:text-2xl">
              {{ formatCNY(peakMonth.total) }}
            </p>
            <p class="mt-0.5 text-xs text-muted-foreground">{{ peakMonthLabel }}</p>
          </div>
        </div>

        <!--
          自制图例：颜色 + 资产名 + 12 个月合计（按金额降序、两列对齐），
          颜色与计划资产行的圆点同源（colorVar）。折线只有一条，轴名已说明口径，不再占图例。
        -->
        <div class="mb-2 grid grid-cols-1 gap-x-8 gap-y-1.5 sm:grid-cols-2">
          <div
            v-for="item in assetTotals"
            :key="item.code"
            class="flex min-w-0 items-center gap-2"
            :title="`${item.name} · ${item.code}`"
          >
            <span
              class="size-2.5 shrink-0 rounded-full ring-1 ring-foreground/15"
              :style="{ backgroundColor: `var(${item.colorVar || '--chart-1'})` }"
            />
            <span class="min-w-0 flex-1 truncate text-xs text-muted-foreground">{{ item.name }}</span>
            <span class="shrink-0 font-mono text-xs font-semibold tabular-nums">
              {{ compactCNY(item.total) }}
            </span>
          </div>
        </div>

        <div class="h-[280px] w-full sm:h-[320px]">
          <VChart
            class="h-full w-full"
            :option="chartOption"
            autoresize
            :update-options="{ notMerge: true }"
          />
        </div>
        <p class="mt-2 text-xs leading-relaxed text-muted-foreground">
          柱为每{{ view === 'month' ? '月' : '年' }}定投流入（按资产堆叠，右轴），折线为累计流入（左轴）。
          按实际扣款日归月，遇非交易日顺延，跨月则记在次月。
        </p>
      </template>
    </CardContent>
  </Card>
</template>
