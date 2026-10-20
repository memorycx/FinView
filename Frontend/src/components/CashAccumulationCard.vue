<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import { updateMonthlyInflowOfMonth } from '@/api'
import { isDark } from '@/composables/useTheme'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY } from '@/lib/finance'
import type { CashFlowForecast, CashFlowMonth } from '@/api/types'

/**
 * 「现金积累预测」卡片（定投分析页的第二张）：月度收支柱状图 + 月末余额折线图。
 *
 * 口径见后端 CashFlowService：柱状图 = 每月固定新增（柱子上段，--flow-in）与当月定投支出（柱子下段，--flow-out）；
 * 折线 = 各月月末余额（首点「当前」是今天的现金，末点「12个月后」= 卡片上的预计现金）。
 * 只负责画后端给的 months[]，不在这里做任何口径换算。
 *
 * **点柱子可以单独改那个月的「每月新增现金」**（贴柱弹出小输入框，保存后由父级把重算好的
 * 整份预测塞回来；「恢复默认」= 删掉单月覆盖、回到卡片 1 的默认值）。
 *
 * 三处容易看错、按设计稿刻意为之的地方：
 * - 柱子是「浮」在轴上的：浅蓝柱上叠一个透明垫片、深蓝柱再叠在垫片上，靠两个 6% / 5% 的
 *   透明堆叠系列造出设计稿「浅蓝下深蓝上、中间留缝」的样子（数值本身仍是各自的真实金额）；
 * - 柱子里两色的可见高度按当月两金额的比例分配、每月总高一致，绝对值看 tooltip；
 * - 折线只在两端标「当前 / 12个月后」，中间月份不标，与设计稿一致。
 */
const props = defineProps<{ forecast?: CashFlowForecast }>()

const emit = defineEmits<{
  /** 单月覆盖保存成功：参数是后端重算后的整份预测 */
  saved: [forecast: CashFlowForecast]
}>()

use([CanvasRenderer, BarChart, LineChart, GridComponent, TooltipComponent])

/** 主题切换后重新读 CSS 变量（各图表组件的统一约定） */
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

const months = computed(() => props.forecast?.months ?? [])
const isEmpty = computed(() => months.value.length === 0)

/* ==================== 点柱子改单月新增 ==================== */

/** 弹层的固定尺寸（px）：定位要按它夹在图表范围内，别让它跑出卡片 */
const EDITOR_W = 264
const EDITOR_H = 124

const chartWrap = ref<HTMLDivElement | null>(null)
const popoverEl = ref<HTMLDivElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

/** 打开的编辑器（位置是相对图表容器的 px）；null = 没在编辑 */
const editor = ref<{ month: string; overridden: boolean; x: number; y: number } | null>(null)
/** type=number 的 v-model 会自动转成 number（空值时是空串），所以两种类型都要接得住 */
const draft = ref<string | number>('')
const saving = ref(false)
const error = ref<string | null>(null)

/** 默认值文案（「恢复默认」会回到它） */
const defaultLabel = computed(() => {
  const base = props.forecast?.monthlyInflow
  return base == null ? '未设置（0）' : formatCNY(base)
})

/** 图表点击：只有柱子（含垫片/整列热区）会触发，点空白不响应 */
function onBarClick(params: unknown) {
  const p = params as { dataIndex?: number; event?: { offsetX?: number; offsetY?: number } }
  const month = months.value[p.dataIndex ?? -1]
  if (!month) return
  openEditor(month, p.event?.offsetX ?? 0, p.event?.offsetY ?? 0)
}

function openEditor(month: CashFlowMonth, x: number, y: number) {
  const box = chartWrap.value
  const width = box?.clientWidth ?? 400
  const height = box?.clientHeight ?? 280
  // 贴柱弹：左右夹在容器内，上方放不下就翻到点击点下方
  const left = Math.min(Math.max(x - EDITOR_W / 2, 8), Math.max(8, width - EDITOR_W - 8))
  const top = y > EDITOR_H + 24
    ? y - EDITOR_H - 12
    : Math.min(y + 16, Math.max(8, height - EDITOR_H - 8))
  editor.value = { month: month.month, overridden: month.overridden, x: left, y: top }
  draft.value = String(month.inflow)
  error.value = null
  nextTick(() => {
    window.addEventListener('pointerdown', onOutside, true)
    inputEl.value?.focus()
    inputEl.value?.select()
  })
}

function closeEditor() {
  editor.value = null
  error.value = null
  window.removeEventListener('pointerdown', onOutside, true)
}

/** 点弹层外面 = 取消（点别的柱子则先关再在新位置打开，见 pointerdown 先于 click） */
function onOutside(event: PointerEvent) {
  const box = popoverEl.value
  if (box && event.target instanceof Node && box.contains(event.target)) return
  closeEditor()
}

onBeforeUnmount(() => window.removeEventListener('pointerdown', onOutside, true))

/** 保存这个月的覆盖值；空输入按 0 处理（= 这个月收入为零），要回到默认值走「恢复默认」 */
async function save() {
  if (saving.value || !editor.value) return
  const raw = String(draft.value ?? '').trim()
  const parsed = raw === '' ? 0 : Number(raw)
  if (!Number.isFinite(parsed) || parsed < 0) {
    error.value = '请输入不小于 0 的金额'
    return
  }
  saving.value = true
  error.value = null
  try {
    emit('saved', await updateMonthlyInflowOfMonth(editor.value.month, parsed))
    closeEditor()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

/** 恢复默认：删掉这个月的覆盖行（传 null），回到卡片 1 的默认值 */
async function resetToDefault() {
  if (saving.value || !editor.value) return
  saving.value = true
  error.value = null
  try {
    emit('saved', await updateMonthlyInflowOfMonth(editor.value.month, null))
    closeEditor()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

function baseTooltip() {
  return {
    backgroundColor: themeColor('--popover'),
    borderColor: themeColor('--border', 0.5),
    borderWidth: 1,
    padding: [10, 14] as [number, number],
    textStyle: { color: themeColor('--foreground'), fontSize: 14, fontFamily: SANS_FONT },
    extraCssText: 'border-radius:12px;box-shadow:0 10px 24px rgba(10,37,64,0.15);min-width:200px;',
  }
}

function tipRow(label: string, value: string, color?: string) {
  return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;margin-top:6px;width:100%">
    <span style="color:${themeColor('--muted-foreground')}">${label}</span>
    <span style="font-family:${MONO_FONT};font-weight:600;font-variant-numeric:tabular-nums;${color ? `color:${color}` : ''}">${value}</span>
  </div>`
}

const barOption = computed<EChartsOption>(() => {
  void themeTick.value

  const rows = months.value
  const inflow = rows.map((m) => m.inflow)
  const outlay = rows.map((m) => m.outlay)
  const peak = Math.max(1, ...inflow, ...outlay)
  // 设计稿里柱子浮在轴上方：透明垫片（6%）+ 浅蓝柱 + 透明缝隙（5%）+ 深蓝柱，自下而上堆叠
  const base = peak * 0.06
  const gap = peak * 0.05
  // 两色柱子的可见高度按各自金额占该月两金额之和的比例分配，统一以 peak 为基准：
  // 所有月份柱子总高一致，每根柱子里两色占比直接反映该月金额比例（绝对值差异看 tooltip）
  const inflowBars = rows.map((m) => {
    const sum = m.inflow + m.outlay
    return sum > 0 ? (m.inflow / sum) * peak : 0
  })
  const outlayBars = rows.map((m) => {
    const sum = m.inflow + m.outlay
    return sum > 0 ? (m.outlay / sum) * peak : 0
  })
  const flowIn = themeColor('--flow-in')
  const flowOut = themeColor('--flow-out')
  const gridColor = themeColor('--border', 0.7)

  return {
    animationDuration: 400,
    grid: { left: 8, right: 8, top: 12, bottom: 0, containLabel: true },
    tooltip: {
      ...baseTooltip(),
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: themeColor('--muted', 0.4) } },
      formatter: (params: unknown) => {
        const arr = (Array.isArray(params) ? params : [params]) as Array<{ dataIndex: number }>
        const month = rows[arr[0]?.dataIndex ?? 0]
        if (!month) return ''
        // 被单独调整过的月份标出来（弹层里可以「恢复默认」）
        const title = month.month + (month.overridden ? ' · 已单独调整' : '')
        return `<div style="font-weight:600;margin-bottom:2px">${title}</div>`
          + tipRow('每月新增现金', formatCNY(month.inflow), flowIn)
          + tipRow('每月定投支出', formatCNY(month.outlay), flowOut)
      },
    },
    xAxis: {
      type: 'category',
      data: rows.map((m) => m.month),
      axisLabel: {
        color: themeColor('--muted-foreground'),
        fontFamily: SANS_FONT,
        fontSize: 13,
        interval: 0,
        // 稀疏标签：每 3 个月一个（设计稿的 1月 / 4月 / 7月 / 10月）
        formatter: (_value: string, index: number) =>
          index % 3 === 0 ? `${Number(rows[index].month.slice(5))}月` : '',
      },
      axisTick: { show: false },
      axisLine: { show: true, lineStyle: { color: gridColor } },
      splitLine: { show: true, lineStyle: { color: gridColor } },
    },
    yAxis: {
      type: 'value',
      max: peak * 1.18,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: false },
    },
    series: [
      // 透明垫片 / 缝隙：不参与 tooltip，也不响应鼠标
      {
        name: 'base',
        type: 'bar',
        stack: 'flow',
        silent: true,
        tooltip: { show: false },
        data: rows.map(() => base),
        itemStyle: { color: 'transparent' },
      },
      {
        name: 'outlay',
        type: 'bar',
        stack: 'flow',
        barWidth: 20,
        cursor: 'pointer',
        data: outlayBars,
        itemStyle: { color: flowOut, borderRadius: 4 },
      },
      {
        name: 'gap',
        type: 'bar',
        stack: 'flow',
        silent: true,
        tooltip: { show: false },
        data: rows.map(() => gap),
        itemStyle: { color: 'transparent' },
      },
      {
        name: 'inflow',
        type: 'bar',
        stack: 'flow',
        barWidth: 20,
        cursor: 'pointer',
        data: inflowBars,
        itemStyle: { color: flowIn, borderRadius: 4 },
      },
      // 整列热区：透明高柱自成一组、用 barGap: '-100%' 与柱组重叠，这样点「哪个月」都好点，
      // 不用去瞄 20px 宽的柱子；只有点击事件，tooltip 不参与
      {
        name: 'hit',
        type: 'bar',
        stack: 'hit',
        barGap: '-100%',
        barWidth: 44,
        cursor: 'pointer',
        silent: false,
        emphasis: { disabled: true },
        tooltip: { show: false },
        data: rows.map(() => peak * 1.18),
        itemStyle: { color: 'transparent' },
      },
    ],
  }
})

const lineOption = computed<EChartsOption>(() => {
  void themeTick.value

  const forecast = props.forecast
  // 13 个点：当前现金 + 12 个月末余额（首点标「当前」、末点标「12个月后」）
  const values = forecast ? [forecast.currentCash, ...forecast.months.map((m) => m.endBalance)] : []
  const last = values.length - 1
  const lineColor = themeColor('--chart-1')
  const cardColor = themeColor('--card')

  return {
    animationDuration: 400,
    grid: { left: 8, right: 14, top: 16, bottom: 4, containLabel: true },
    tooltip: {
      ...baseTooltip(),
      trigger: 'axis',
      formatter: (params: unknown) => {
        const arr = (Array.isArray(params) ? params : [params]) as Array<{ dataIndex: number }>
        const index = arr[0]?.dataIndex ?? 0
        const label = index === 0
          ? '当前'
          : index === last
            ? '12个月后'
            : `${forecast?.months[index - 1]?.month ?? ''}月末`
        return `<div style="font-weight:600;margin-bottom:2px">${label}</div>`
          + tipRow('现金余额', formatCNY(values[index] ?? 0), lineColor)
      },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: values.map((_, index) => (index === 0 ? '当前' : index === last ? '12个月后' : '')),
      axisLabel: {
        color: themeColor('--muted-foreground'),
        fontFamily: SANS_FONT,
        fontSize: 13,
        interval: 0,
      },
      axisTick: { show: false },
      axisLine: { show: false },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: true, lineStyle: { type: 'dashed', color: themeColor('--border', 0.7) } },
      splitNumber: 4,
    },
    series: [
      {
        name: 'balance',
        type: 'line',
        symbol: 'circle',
        showSymbol: true,
        data: values.map((value, index) => ({
          value,
          symbolSize: index === last ? 10 : 5,
          itemStyle: { color: lineColor, borderColor: cardColor, borderWidth: 2 },
        })),
        lineStyle: { width: 3, color: lineColor },
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
      },
    ],
  }
})
</script>

<template>
  <Card class="gap-0 p-5 sm:p-7">
    <div class="flex items-start justify-between gap-4">
      <div>
        <h2 class="text-2xl font-bold tracking-tight">现金积累预测</h2>
        <p class="mt-1 text-sm text-muted-foreground">单位：人民币 · 月度</p>
      </div>
      <span class="shrink-0 rounded-full bg-brand/10 px-3 py-1 text-xs font-medium text-brand">模拟数据</span>
    </div>

    <!-- 大屏（lg+）两张图左右并排，小屏纵向堆叠；autoresize 会按各自新宽度重绘 -->
    <div class="mt-6 grid grid-cols-1 gap-x-8 gap-y-6 lg:grid-cols-2">
      <div>
        <p class="text-sm text-muted-foreground">月度收支（柱状图）</p>
        <div ref="chartWrap" class="relative mt-3 h-[240px] w-full sm:h-[280px]">
          <VChart
            v-if="!isEmpty"
            class="h-full w-full"
            :option="barOption"
            autoresize
            :update-options="{ notMerge: true }"
            @click="onBarClick"
          />
          <div v-else class="flex h-full items-center justify-center text-sm text-muted-foreground">数据加载中…</div>

          <!-- 贴柱弹出的小编辑框：改这个月的「每月新增现金」（空输入 = 0；「恢复默认」= 回到默认值） -->
          <div
            v-if="editor"
            ref="popoverEl"
            class="absolute z-20 w-[264px] rounded-xl border border-border bg-popover p-3 shadow-xl"
            :style="{ left: `${editor.x}px`, top: `${editor.y}px` }"
          >
            <p class="text-xs text-muted-foreground">{{ editor.month }} · 每月新增现金</p>
            <div class="mt-2 flex items-center gap-1.5">
              <input
                ref="inputEl"
                v-model="draft"
                type="number"
                inputmode="decimal"
                step="0.01"
                min="0"
                class="h-8 w-full rounded-lg border border-border bg-background px-2 font-mono text-sm tabular-nums outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                @keyup.enter="save"
                @keyup.esc="closeEditor"
              />
              <Button size="xs" :disabled="saving" @click="save">保存</Button>
            </div>
            <div class="mt-2 flex items-center justify-between gap-2 text-xs text-muted-foreground">
              <span>默认 {{ defaultLabel }}</span>
              <button
                v-if="editor.overridden"
                type="button"
                class="text-brand hover:underline disabled:opacity-50"
                :disabled="saving"
                @click="resetToDefault"
              >
                恢复默认
              </button>
            </div>
            <p v-if="error" class="mt-1 text-xs text-destructive">{{ error }}</p>
          </div>
        </div>
      </div>

      <div>
        <p class="text-sm text-muted-foreground">月末现金余额（折线图）</p>
        <div class="mt-3 h-[220px] w-full sm:h-[260px] lg:h-[280px]">
          <VChart
            v-if="!isEmpty"
            class="h-full w-full"
            :option="lineOption"
            autoresize
            :update-options="{ notMerge: true }"
          />
          <div v-else class="flex h-full items-center justify-center text-sm text-muted-foreground">数据加载中…</div>
        </div>
      </div>
    </div>

    <p class="mt-6 border-t border-border pt-4 text-xs leading-relaxed text-muted-foreground">
      柱状图 = 每月固定新增（柱子上段）与当月定投支出（柱子下段，申购费从扣款金额里扣收），点柱子可单独调整该月的新增（改过可「恢复默认」）；
      折线 = 各月月末余额（上月末余额 + 当月新增 − 当月支出），首点为当前现金、末点为 12 个月后预计现金。
      均为按当前定投节奏的预测值，不构成投资建议。
    </p>
  </Card>
</template>
