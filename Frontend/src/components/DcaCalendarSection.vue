<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { ChevronLeft, ChevronRight, X } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import { getCashFlowCalendar } from '@/api'
import { useRequest } from '@/composables/useApi'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY } from '@/lib/finance'
import type { CashFlowCalendar } from '@/api/types'

/**
 * 「定投日历」区块（定投分析页的第三块）：月历热力 + 本月总览 / 基金月度金额 /
 * 定投金额分布（一行按钮切换「每周 = 当月周一~周日」与「年度 = 当年 1~12 月」两个视图）。
 *
 * 数据全部来自 GET /cashflow/calendar（口径见后端 CashFlowService.calendar）：
 * 金额 = 当天周期扣款划走的钱（申购费从里面扣），当天执行的计划数用圆点表示；
 * 整月按当前事件流重算，本月剩余与未来月份是计划值（脚注里说明）。
 *
 * 有定投的日子统一用淡蓝底色标记，不按金额分档（每天的具体金额仍标在格子里）。
 */
const { data: calendar, error, reload } = useRequest(() => getCashFlowCalendar(month.value))

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent])

/* ==================== 月份导航 ==================== */

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 今天的 YYYY-MM-DD（本地时区，用来给日历格子挂「今天」的圈） */
const today = (() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
})()

const month = ref(currentMonth())
/** 选中的日期（YYYY-MM-DD）：点格子选中、再点一次取消，右侧出「当日定投明细」 */
const selectedDay = ref<string | null>(null)
// 换月重新拉：日历 / 总览 / 基金表 / 每周分布都是这一个接口；选中的是上个月的日期，顺手清掉
watch(month, () => {
  selectedDay.value = null
  void reload()
})

/** 导航上下界由后端给：下界 = 最早有记录的月份，上界 = 预测窗口末月 */
const minMonth = computed(() => calendar.value?.minMonth ?? month.value)
const maxMonth = computed(() => calendar.value?.maxMonth ?? month.value)
const canPrev = computed(() => month.value > minMonth.value)
const canNext = computed(() => month.value < maxMonth.value)

/** 可选月份列表（含上下界），形如 2026年10月 */
const monthOptions = computed(() => {
  const [minY, minM] = minMonth.value.split('-').map(Number)
  const [maxY, maxM] = maxMonth.value.split('-').map(Number)
  const options: { value: string; label: string }[] = []
  for (let y = minY, m = minM; y < maxY || (y === maxY && m <= maxM); ) {
    options.push({ value: `${y}-${String(m).padStart(2, '0')}`, label: `${y}年${m}月` })
    m += 1
    if (m > 12) {
      m = 1
      y += 1
    }
    if (options.length > 120) break
  }
  return options
})

const monthLabel = computed(() => {
  const [y, m] = month.value.split('-').map(Number)
  return `${y}年${m}月`
})

function shiftMonth(delta: number) {
  const [y, m] = month.value.split('-').map(Number)
  const total = y * 12 + (m - 1) + delta
  month.value = `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}

/* ==================== 日历格子 ==================== */

/** 周一开头的一周；首尾补上相邻月份的日期（灰显，只有日期号） */
const cells = computed(() => {
  const data = calendar.value
  if (!data) return []
  const [year, m] = data.month.split('-').map(Number)
  const daysInMonth = new Date(year, m, 0).getDate()
  const prevMonthDays = new Date(year, m - 1, 0).getDate()
  const leading = (new Date(year, m - 1, 1).getDay() + 6) % 7
  const rows = Math.ceil((leading + daysInMonth) / 7)
  const byDay = new Map(data.days.map((d) => [d.day, d]))
  return Array.from({ length: rows * 7 }, (_, i) => {
    const dayNum = i - leading + 1
    // 首尾补上相邻月份的日期：灰显、只有日期号（和设计稿一致）
    if (dayNum < 1) {
      return { key: `p${i}`, dayNum: prevMonthDays + dayNum, inMonth: false, amount: 0, plans: 0, isToday: false }
    }
    if (dayNum > daysInMonth) {
      return { key: `n${i}`, dayNum: dayNum - daysInMonth, inMonth: false, amount: 0, plans: 0, isToday: false }
    }
    const iso = `${data.month}-${String(dayNum).padStart(2, '0')}`
    const row = byDay.get(iso)
    return { key: iso, dayNum, inMonth: true, amount: row?.amount ?? 0, plans: row?.plans ?? 0, isToday: iso === today }
  })
})

/* ==================== 选中某一天（右侧出当日明细） ==================== */

/** 点格子选中 / 取消（相邻月份的灰格子不响应） */
function selectCell(cell: { key: string; inMonth: boolean }) {
  if (!cell.inMonth) return
  selectedDay.value = selectedDay.value === cell.key ? null : cell.key
}

const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 选中那天的明细：当天的各基金金额，名称与配色按 code 去本月 fund 列表里取 */
const selectedDetail = computed(() => {
  const data = calendar.value
  const iso = selectedDay.value
  if (!data || !iso) return null
  const day = data.days.find((d) => d.day === iso)
  if (!day) return null
  const meta = new Map(data.funds.map((fund) => [fund.code, fund]))
  const [year, monthValue, dayValue] = iso.split('-').map(Number)
  const weekday = WEEKDAY_LABELS[(new Date(year, monthValue - 1, dayValue).getDay() + 6) % 7]
  return {
    label: `${monthValue}月${dayValue}日 ${weekday}`,
    amount: day.amount,
    funds: day.funds.map((fund) => ({
      code: fund.code,
      amount: fund.amount,
      name: meta.get(fund.code)?.name ?? fund.code,
      colorVar: meta.get(fund.code)?.colorVar ?? '--chart-1',
    })),
  }
})

/* ==================== 主题色（主题切换要跟着重算） ==================== */

const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

/** 有定投的日期：统一淡蓝底色（不按金额分档）；选中的那天加深一档 */
function dcaCellStyle(hasDca: boolean, selected: boolean) {
  void themeTick.value
  if (selected) return { backgroundColor: themeColor('--chart-1', 0.34) }
  return hasDca ? { backgroundColor: themeColor('--chart-1', 0.16) } : {}
}

/* ==================== 分布小图（按周 / 按年切换） ==================== */

/** 分布视图：按周 = 当月周一~周日；按年 = 所选月份那一年的 1~12 月 */
const distributionView = ref<'weekday' | 'year'>('weekday')

/**
 * 切到「年度」时如果手里的响应没有 monthsOfYear（页面是后端升级前打开的，切视图不会重新拉数据），
 * 补拉一次；正常情况这个字段都在，watcher 什么都不做。
 */
watch(distributionView, (view) => {
  if (view === 'year' && !calendar.value?.monthsOfYear) {
    void reload()
  }
})

const distributionTitle = computed(() =>
  distributionView.value === 'year' ? '年度定投金额分布' : '每周定投金额分布',
)

/** 右上角小标签：按周看是月份、按年看是年份 */
const distributionScope = computed(() =>
  distributionView.value === 'year' ? `${month.value.slice(0, 4)}年` : monthLabel.value,
)

/* ==================== 总览卡（与分布视图联动） ==================== */

/** 点「年度」时总览卡切到本年口径：标题、右上角标签、金额都跟着变 */
const overviewIsYear = computed(() => distributionView.value === 'year')
const overviewTitle = computed(() => (overviewIsYear.value ? '本年定投总览' : '本月定投总览'))
const overviewScope = computed(() =>
  overviewIsYear.value ? `${month.value.slice(0, 4)}年` : monthLabel.value,
)
const overviewTotal = computed(() => {
  if (!calendar.value) return null
  // 年度总额直接用后端的 yearTotal（与年度日均是同一个分子），不再自己加 monthsOfYear
  return overviewIsYear.value ? calendar.value.yearTotal : calendar.value.total
})
// 下面三项统计也随视图切口径（年度的字段由后端 yearExecutedDays / yearFundCount / yearDailyAverage 给）
const overviewExecutedDays = computed(() =>
  overviewIsYear.value ? (calendar.value?.yearExecutedDays ?? 0) : (calendar.value?.executedDays ?? 0),
)
const overviewFundCount = computed(() =>
  overviewIsYear.value ? (calendar.value?.yearFundCount ?? 0) : (calendar.value?.fundCount ?? 0),
)
const overviewDailyAverage = computed(() =>
  overviewIsYear.value ? calendar.value?.yearDailyAverage : calendar.value?.dailyAverage,
)

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const MONTH_NAMES = Array.from({ length: 12 }, (_, i) => `${i + 1}月`)

const distributionOption = computed<EChartsOption>(() => {
  void themeTick.value
  const isYear = distributionView.value === 'year'
  const values = (isYear ? calendar.value?.monthsOfYear : calendar.value?.weekdays) ?? []
  const categories = isYear ? MONTH_NAMES : WEEKDAY_NAMES
  const barColor = themeColor('--chart-1')
  // 按年时把「当前正在看的那个月」点亮，其余降一档透明度，一眼看出这根柱子对应上面的日历
  const focusIndex = isYear ? Number(month.value.slice(5)) - 1 : -1
  const data = values.map((value, index) => ({
    value,
    itemStyle: {
      color: isYear && index !== focusIndex ? themeColor('--chart-1', 0.4) : barColor,
      borderRadius: [4, 4, 0, 0] as [number, number, number, number],
    },
  }))
  return {
    animationDuration: 400,
    grid: { left: 4, right: 4, top: 22, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: themeColor('--muted', 0.4) } },
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [8, 12] as [number, number],
      textStyle: { color: themeColor('--foreground'), fontSize: 13, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const arr = (Array.isArray(params) ? params : [params]) as Array<{ dataIndex: number; value: number }>
        const p = arr[0]
        if (!p) return ''
        const title = isYear ? `${month.value.slice(0, 4)}年${categories[p.dataIndex]}` : categories[p.dataIndex]
        return `<div style="font-weight:600">${title}</div>
          <div style="margin-top:4px;color:${themeColor('--muted-foreground')}">定投金额
          <span style="font-family:${MONO_FONT};font-weight:600;color:${themeColor('--foreground')};margin-left:12px">${formatCNY(p.value ?? 0)}</span></div>`
      },
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: themeColor('--muted-foreground'), fontFamily: SANS_FONT, fontSize: 12 },
      axisTick: { show: false },
      axisLine: { show: true, lineStyle: { color: themeColor('--border', 0.7) } },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        color: themeColor('--muted-foreground'),
        fontFamily: MONO_FONT,
        fontSize: 11,
        formatter: (v: number) => `¥${v}`,
      },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: true, lineStyle: { type: 'dashed', color: themeColor('--border', 0.6) } },
      splitNumber: 2,
    },
    series: [
      {
        name: '定投金额',
        type: 'bar',
        // 按年 12 根柱子比按周 7 根窄一档，标签放不下时由 labelLayout 自动隐掉
        barWidth: isYear ? 14 : 22,
        data,
        label: {
          show: true,
          position: 'top',
          formatter: (p) => (Number(p.value) > 0 ? `¥${Number(p.value)}` : ''),
          color: themeColor('--foreground'),
          fontFamily: MONO_FONT,
          fontSize: isYear ? 10 : 11,
        },
        labelLayout: { hideOverlap: true },
      },
    ],
  }
})

/** 基金表里那根小占比条：按当月最大占比归一，最大的那只占满 */
const maxShare = computed(() => Math.max(0.0001, ...(calendar.value?.funds ?? []).map((f) => f.share)))

function pct(share: number) {
  return `${(share * 100).toFixed(1)}%`
}

function fundBarWidth(share: number) {
  return `${Math.max(4, Math.round((share / maxShare.value) * 100))}%`
}
</script>

<template>
  <section class="mt-5">
    <!-- 区块标题 + 月份导航（‹ 月份选择 › 今天） -->
    <div class="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
      <div>
        <h2 class="text-2xl font-bold tracking-tight">定投日历</h2>
        <p class="mt-1 text-sm text-muted-foreground">查看每天的定投金额分布，有定投的日子用淡蓝底色标记</p>
      </div>
      <div class="flex items-center gap-2">
        <Button variant="outline" size="icon-sm" :disabled="!canPrev" aria-label="上一个月" @click="shiftMonth(-1)">
          <ChevronLeft />
        </Button>
        <select
          v-model="month"
          aria-label="选择月份"
          class="h-7 rounded-lg border border-border bg-background px-2 text-sm font-medium text-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <option v-for="option in monthOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <Button variant="outline" size="icon-sm" :disabled="!canNext" aria-label="下一个月" @click="shiftMonth(1)">
          <ChevronRight />
        </Button>
        <Button variant="outline" size="sm" @click="month = currentMonth()">今天</Button>
      </div>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,2.1fr)_minmax(0,1fr)]">
      <!-- 左：总览 + 月历 -->
      <div class="flex flex-col gap-5">
        <!-- 定投总览（随下方「每周/年度」切换联动口径） -->
        <Card class="gap-0 p-5">
          <div class="flex items-baseline justify-between gap-3">
            <h3 class="text-base font-bold">{{ overviewTitle }}</h3>
            <span class="text-xs text-muted-foreground">{{ overviewScope }}</span>
          </div>
          <p class="mt-3 font-mono text-3xl font-bold tracking-tight tabular-nums">
            {{ overviewTotal !== null ? formatCNY(overviewTotal) : '—' }}
          </p>
          <p class="mt-1 text-sm text-muted-foreground">{{ overviewIsYear ? '本年定投总金额' : '本月定投总金额' }}</p>
          <!-- 三项统计随视图切口径：按年用后端给的 yearExecutedDays / yearFundCount / yearDailyAverage -->
          <div class="mt-4 grid grid-cols-3 divide-x divide-border border-t border-border pt-4 text-left">
            <div class="px-1">
              <p class="font-mono text-xl font-bold tabular-nums">
                {{ overviewExecutedDays }}<span class="text-sm font-normal">天</span>
              </p>
              <p class="mt-1 text-xs text-muted-foreground">定投执行天数</p>
            </div>
            <div class="px-1">
              <p class="font-mono text-xl font-bold tabular-nums">
                {{ overviewFundCount }}<span class="text-sm font-normal">个</span>
              </p>
              <p class="mt-1 text-xs text-muted-foreground">涉及基金数量</p>
            </div>
            <div class="px-1">
              <p class="font-mono text-xl font-bold tabular-nums">
                {{ overviewDailyAverage != null ? formatCNY(overviewDailyAverage) : '—' }}
              </p>
              <p class="mt-1 text-xs text-muted-foreground">日均定投金额</p>
            </div>
          </div>
        </Card>

        <!-- 月历 -->
        <Card class="gap-0 p-4 sm:p-5">
          <div class="grid grid-cols-7 gap-1.5 text-center text-sm text-muted-foreground">
            <div v-for="w in ['一', '二', '三', '四', '五', '六', '日']" :key="w" class="py-1">周{{ w }}</div>
          </div>

          <div class="mt-1 grid grid-cols-7 gap-1.5">
            <div
              v-for="cell in cells"
              :key="cell.key"
              :data-day="cell.inMonth ? cell.key : undefined"
              class="min-h-[74px] rounded-lg p-2 transition-shadow"
              :class="[
                cell.inMonth ? 'cursor-pointer hover:ring-2 hover:ring-brand/40' : 'bg-muted/30',
                cell.isToday ? 'ring-2 ring-brand' : '',
                selectedDay === cell.key ? 'ring-2 ring-brand' : '',
              ]"
              :style="cell.inMonth ? dcaCellStyle(cell.amount > 0, selectedDay === cell.key) : {}"
              @click="selectCell(cell)"
            >
              <!-- 相邻月份只留一个灰日期号 -->
              <p v-if="!cell.inMonth" class="text-sm tabular-nums text-muted-foreground/50">{{ cell.dayNum }}</p>
              <template v-else>
                <p
                  class="text-sm tabular-nums"
                  :class="selectedDay === cell.key ? 'font-bold text-brand' : 'text-foreground/80'"
                >
                  {{ cell.dayNum }}
                </p>
                <p v-if="cell.amount > 0" class="mt-0.5 truncate font-mono text-sm font-semibold tabular-nums">
                  ¥{{ cell.amount }}
                </p>
                <!-- 当日执行的定投计划数：最多画 4 个点，更多的用「+」 -->
                <div v-if="cell.plans > 0" class="mt-1 flex items-center gap-0.5" :title="`${cell.plans} 只基金执行定投`">
                  <span
                    v-for="i in Math.min(cell.plans, 4)"
                    :key="i"
                    class="size-1.5 rounded-full"
                    :style="{ backgroundColor: 'var(--brand)' }"
                  />
                  <span v-if="cell.plans > 4" class="text-[10px] leading-none" :style="{ color: 'var(--brand)' }">+</span>
                </div>
              </template>
            </div>
          </div>

          <!-- 图例：定投计划数圆点 -->
          <div class="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border pt-3 text-xs text-muted-foreground">
            <span class="flex items-center gap-1.5">
              <span class="size-3 rounded-sm" :style="{ backgroundColor: themeColor('--chart-1', 0.16) }" />
              有定投的日子
            </span>
            <span class="flex items-center gap-1.5">
              <span class="size-1.5 rounded-full" :style="{ backgroundColor: 'var(--brand)' }" />
              当日执行的定投计划数量
            </span>
          </div>

          <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
            当日金额 = 当天周期扣款划走的钱（申购费在扣款金额里扣收）；今天及以后的日期按当前定投计划推算。
            点某一天，右侧会列出当天的定投明细。
          </p>
          <p v-if="error" class="mt-2 text-xs text-destructive">日历加载失败：{{ error.message }}</p>
        </Card>
      </div>

      <!-- 右：选中日期的明细 / 基金 / 每周分布 -->
      <div class="flex flex-col gap-5">
        <!-- 当日定投明细：点日历格子后出现，✕ 或再点同一天收起 -->
        <Card v-if="selectedDetail" class="gap-0 p-5">
          <div class="flex items-start justify-between gap-3">
            <div>
              <h3 class="text-base font-bold">当日定投明细</h3>
              <p class="mt-0.5 text-xs text-muted-foreground">{{ selectedDetail.label }}</p>
            </div>
            <Button variant="ghost" size="icon-xs" aria-label="关闭当日明细" @click="selectedDay = null">
              <X />
            </Button>
          </div>

          <div
            v-if="selectedDetail.funds.length > 0"
            class="mt-3 grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 text-xs text-muted-foreground"
          >
            <span>基金名称</span>
            <span class="text-right">当日金额（元）</span>
          </div>
          <ul v-if="selectedDetail.funds.length > 0" class="mt-1 divide-y divide-border">
            <li
              v-for="fund in selectedDetail.funds"
              :key="fund.code"
              class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 py-2.5 text-sm"
            >
              <span class="flex min-w-0 items-center gap-2">
                <span
                  class="size-2.5 shrink-0 rounded-full"
                  :style="{ backgroundColor: `var(${fund.colorVar})` }"
                />
                <span class="truncate" :title="fund.name">{{ fund.name }}</span>
              </span>
              <span class="font-mono tabular-nums">{{ fund.amount }}</span>
            </li>
          </ul>
          <p v-else class="mt-3 text-sm text-muted-foreground">这一天没有定投扣款</p>

          <div
            v-if="selectedDetail.funds.length > 0"
            class="mt-1 grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 border-t border-border pt-2.5 text-sm font-medium"
          >
            <span>合计</span>
            <span class="font-mono tabular-nums">{{ selectedDetail.amount }}</span>
          </div>
        </Card>

        <!-- 基金月度定投金额 -->
        <Card class="gap-0 p-5">
          <div class="flex items-baseline justify-between gap-3">
            <h3 class="text-base font-bold">基金月度定投金额</h3>
            <span class="text-xs text-muted-foreground">{{ monthLabel }}</span>
          </div>
          <div class="mt-3 grid grid-cols-[minmax(0,1fr)_auto_5.5rem] gap-x-3 text-xs text-muted-foreground">
            <span>基金名称</span>
            <span class="text-right">本月金额（元）</span>
            <span class="text-right">占比</span>
          </div>
          <ul class="mt-1 divide-y divide-border">
            <li
              v-for="fund in calendar?.funds ?? []"
              :key="fund.code"
              class="grid grid-cols-[minmax(0,1fr)_auto_5.5rem] items-center gap-x-3 py-2.5 text-sm"
            >
              <span class="flex min-w-0 items-center gap-2">
                <span
                  class="size-2.5 shrink-0 rounded-full"
                  :style="{ backgroundColor: `var(${fund.colorVar ?? '--chart-1'})` }"
                />
                <span class="truncate" :title="fund.name">{{ fund.name }}</span>
              </span>
              <span class="font-mono tabular-nums">{{ fund.amount }}</span>
              <span class="flex items-center justify-end gap-2">
                <span class="h-1.5 w-8 shrink-0 overflow-hidden rounded-full bg-muted">
                  <span
                    class="block h-full rounded-full"
                    :style="{ width: fundBarWidth(fund.share), backgroundColor: `var(${fund.colorVar ?? '--chart-1'})` }"
                  />
                </span>
                <span class="w-10 text-right text-xs text-muted-foreground tabular-nums">{{ pct(fund.share) }}</span>
              </span>
            </li>
            <li
              v-if="!calendar || calendar.funds.length === 0"
              class="py-3 text-center text-sm text-muted-foreground"
            >
              本月没有定投扣款
            </li>
          </ul>
          <div
            v-if="calendar && calendar.funds.length > 0"
            class="mt-1 grid grid-cols-[minmax(0,1fr)_auto_5.5rem] items-center gap-x-3 border-t border-border pt-2.5 text-sm font-medium"
          >
            <span>合计</span>
            <span class="font-mono tabular-nums">{{ calendar.total }}</span>
            <span class="text-right text-xs text-muted-foreground">100%</span>
          </div>
        </Card>

        <!-- 定投金额分布：按周（当月周一~周日）/ 按年（当年 1~12 月）切换 -->
        <Card class="gap-0 p-5">
          <div class="flex items-baseline justify-between gap-3">
            <h3 class="text-base font-bold">{{ distributionTitle }}</h3>
            <span class="text-xs text-muted-foreground">{{ distributionScope }}</span>
          </div>

          <!-- 视图切换：一行按钮 -->
          <div class="mt-3 flex items-center gap-1.5">
            <button
              v-for="tab in [
                { key: 'weekday' as const, label: '每周' },
                { key: 'year' as const, label: '年度' },
              ]"
              :key="tab.key"
              type="button"
              class="rounded-lg px-2.5 py-1 text-xs font-medium transition-colors"
              :class="
                distributionView === tab.key
                  ? 'bg-brand text-brand-foreground'
                  : 'bg-muted/60 text-muted-foreground hover:text-foreground'
              "
              @click="distributionView = tab.key"
            >
              {{ tab.label }}
            </button>
          </div>

          <div class="mt-3 h-[180px] w-full">
            <VChart
              v-if="calendar"
              class="h-full w-full"
              :option="distributionOption"
              autoresize
              :update-options="{ notMerge: true }"
            />
            <div v-else class="flex h-full items-center justify-center text-sm text-muted-foreground">数据加载中…</div>
          </div>
        </Card>
      </div>
    </div>
  </section>
</template>
