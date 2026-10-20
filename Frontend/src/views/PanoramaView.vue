<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { Play, RotateCcw, X } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import PageFooter from '@/components/PageFooter.vue'
import PanoramaScene from '@/components/PanoramaScene.vue'
import { useRequest } from '@/composables/useApi'
import { getPanorama } from '@/api'
import { formatCNY, formatWan } from '@/lib/finance'
import { panoramaColor } from '@/lib/panorama'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { isDark } from '@/composables/useTheme'

/**
 * 「资金全景」（3D 塔视图，路由 /panorama）。
 *
 * 左边 3D 场景：一座塔 = 一个展示桶（股基 / 债基 / 货基 / …），塔里每一截 = 一只资产。
 * 点塔 → 其它塔淡出、这一座移到左侧，右侧出**该桶每只资产的未来累计投入折线**。
 * 「演变」按钮：把 12 个月逐个走一遍，每只资产按定投计划的累计投入长高（**只算投入、不含涨跌**）。
 *
 * 口径见后端 PanoramaService：窗口 = 本月剩余 + 往后 11 个自然月；现金没有定投、不参与演变。
 * 这一页是**固定深色场景**（参考设计稿就是暗底辉光），所以场景与右侧折线都用写死的暗色值，
 * 不跟主题切换（取值集中在 lib/panorama.ts）。
 */
const { data: panorama, error } = useRequest(() => getPanorama())

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, MarkLineComponent])

/** 切主题后重新读 CSS 变量（各图表组件的统一约定） */
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

const selected = ref<string | null>(null)
/** 0 = 今天；1..12 = 演变到第 i 个月末 */
const evolveMonth = ref(0)
let timer: number | undefined

const buckets = computed(() => panorama.value?.buckets ?? [])
const months = computed(() => panorama.value?.months ?? [])
const selectedBucket = computed(() => buckets.value.find((b) => b.key === selected.value) ?? null)

/**
 * 选中的是不是现金桶：现金那条 cumulative 是**余额相对今天的净变化**（每月固定新增 − 当月定投，
 * 可以为负，见后端 PanoramaService.cashSeries），所以面板标题 / 那行小字 / 折线标题都要换文案。
 */
const isCashBucket = computed(() => selectedBucket.value?.key === 'cash')

/** 选中的桶在这个演变月里相对今天的变化（基金 = 累计投入，现金 = 余额变化，可正可负） */
const investedThisMonth = computed(() => {
  const bucket = selectedBucket.value
  if (!bucket || evolveMonth.value === 0) return 0
  return bucket.assets.reduce((sum, asset) => sum + (asset.cumulative[evolveMonth.value - 1] ?? 0), 0)
})

/** 现金那行「较今天」的带符号金额（¥ 后面不带正负号，符号写在前面） */
const cashDeltaLabel = computed(
  () => (investedThisMonth.value >= 0 ? '+' : '−') + formatCNY(Math.abs(investedThisMonth.value)),
)

function toggleEvolve() {
  if (evolveMonth.value > 0) {
    resetEvolve()
    return
  }
  evolveMonth.value = 1
  timer = window.setInterval(() => {
    if (evolveMonth.value >= 12) {
      stopTimer()
      return
    }
    evolveMonth.value += 1
  }, 260)
}

function stopTimer() {
  window.clearInterval(timer)
  timer = undefined
}

function resetEvolve() {
  stopTimer()
  evolveMonth.value = 0
}

onBeforeUnmount(stopTimer)

const monthLabel = computed(() =>
  evolveMonth.value === 0 ? '今天' : `${months.value[evolveMonth.value - 1] ?? ''} 月末`,
)

/** 右侧折线：该桶每只资产的累计投入（纵轴），横轴 = 12 个自然月 */
const chartOption = computed<EChartsOption>(() => {
  void themeTick.value
  const bucket = selectedBucket.value
  if (!bucket) return {}
  const axisPointerIndex = evolveMonth.value > 0 ? evolveMonth.value - 1 : 0
  return {
    animationDuration: 400,
    grid: { left: 6, right: 14, top: 16, bottom: 2, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [10, 14] as [number, number],
      textStyle: { color: themeColor('--foreground'), fontSize: 13, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:12px;box-shadow:0 10px 24px rgba(0,0,0,0.18);',
      formatter: (params: unknown) => {
        const arr = (Array.isArray(params) ? params : [params]) as Array<{
          seriesName: string
          dataIndex: number
        }>
        if (!arr.length) return ''
        const index = arr[0].dataIndex
        const head = `<div style="font-weight:600">${months.value[index] ?? ''}</div>`
        const rows = bucket.assets
          .map((asset) => {
            const dot = `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${panoramaColor(asset.colorVar)};margin-right:8px"></span>`
            return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;margin-top:6px">
              <span style="color:${themeColor('--muted-foreground')}">${dot}${asset.name}</span>
              <span style="font-family:${MONO_FONT};font-weight:600">${formatCNY(asset.cumulative[index] ?? 0)}</span>
            </div>`
          })
          .join('')
        return head + rows
      },
    },
    axisPointer: { type: 'line', lineStyle: { color: themeColor('--border') } },
    xAxis: {
      type: 'category',
      data: months.value.map((month) => `${Number(month.slice(5))}月`),
      axisLabel: { color: themeColor('--muted-foreground'), fontFamily: SANS_FONT, fontSize: 11 },
      axisTick: { show: false },
      axisLine: { show: true, lineStyle: { color: themeColor('--border') } },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        color: themeColor('--muted-foreground'),
        fontFamily: MONO_FONT,
        fontSize: 11,
        formatter: (value: number) => formatWan(value),
      },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: themeColor('--border', 0.6), type: 'dashed' } },
    },
    series: bucket.assets.map((asset, seriesIndex) => ({
      name: asset.name,
      type: 'line' as const,
      data: asset.cumulative,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2, color: panoramaColor(asset.colorVar) },
      itemStyle: { color: panoramaColor(asset.colorVar) },
      // 演变中的那条竖线只画一次，挂在第一条线上
      markLine:
        seriesIndex === 0 && evolveMonth.value > 0
          ? {
              silent: true,
              symbol: 'none',
              label: { show: false },
              lineStyle: { color: themeColor('--foreground', 0.45), type: 'dashed' },
              data: [{ xAxis: axisPointerIndex }],
            }
          : undefined,
    })),
  }
})
</script>

<template>
  <div class="mx-auto flex min-h-screen w-full max-w-[1400px] flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 与投资看板的返回按钮行对齐 -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>

    <header class="mb-5 flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
      <div>
        <h1 class="text-3xl font-bold tracking-tight">资金全景</h1>
        <p class="mt-1.5 text-sm text-muted-foreground">
          每座塔是一类资产，点塔看它每只持仓的未来资金变化；按「演变」看资金按定投计划逐月长起来。
        </p>
      </div>
      <div class="flex items-center gap-2">
        <span v-if="evolveMonth > 0" class="mr-1 font-mono text-xs text-muted-foreground tabular-nums">
          {{ monthLabel }}
        </span>
        <Button variant="outline" size="sm" :disabled="!panorama" @click="toggleEvolve">
          <RotateCcw v-if="evolveMonth > 0" />
          <Play v-else />
          {{ evolveMonth > 0 ? '回到今天' : '演变' }}
        </Button>
      </div>
    </header>

    <div class="relative overflow-hidden rounded-xl bg-card ring-1 ring-foreground/10 lg:mt-6">
      <PanoramaScene
        :buckets="buckets"
        :evolve-month="evolveMonth"
        :selected="selected"
        class="h-[560px] w-full lg:h-[720px]"
        @select="selected = $event"
      />

      <!-- 空状态：没有任何可展示的桶时别让人对着空场景猜 -->
      <div
        v-if="panorama && buckets.length === 0"
        class="absolute inset-x-0 bottom-6 text-center text-sm text-muted-foreground"
      >
        名下还没有资产，先到投资看板点「添加资产」记一笔。
      </div>

      <!-- 右上角图例：桶 + 当前（或演变后）金额 -->
      <div class="pointer-events-none absolute left-5 top-5 space-y-2">
        <p class="text-xs tracking-wide text-muted-foreground">
          共 {{ buckets.length }} 类资产 · {{ evolveMonth > 0 ? `演变至 ${monthLabel}` : '今天' }}
        </p>
      </div>

      <!-- 选中桶的右侧面板：每只资产的累计投入折线 -->
      <transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="translate-x-6 opacity-0"
        leave-active-class="transition duration-200 ease-in"
        leave-to-class="translate-x-6 opacity-0"
      >
        <div
          v-if="selectedBucket"
          class="absolute bottom-4 right-4 top-4 flex w-[46%] min-w-[320px] flex-col overflow-hidden rounded-xl border border-border bg-popover/95 p-4 backdrop-blur-sm"
        >
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="text-sm font-semibold text-foreground">{{ selectedBucket.label }}</p>
              <p class="mt-1 font-mono text-2xl font-bold tabular-nums text-foreground">
                {{ formatCNY(selectedBucket.value + investedThisMonth) }}
              </p>
              <p
                v-if="isCashBucket ? investedThisMonth !== 0 : investedThisMonth > 0"
                class="mt-1 text-xs text-muted-foreground"
              >
                {{ isCashBucket ? '较今天' : '其中未来投入' }}
                <span class="font-mono">{{
                  isCashBucket ? cashDeltaLabel : `+${formatCNY(investedThisMonth)}`
                }}</span>
              </p>
            </div>
            <button
              type="button"
              class="rounded-lg p-1.5 text-muted-foreground transition-colors hover:text-foreground"
              aria-label="关闭"
              @click="selected = null"
            >
              <X class="size-4" />
            </button>
          </div>

          <p class="mt-4 text-xs text-muted-foreground">
            {{ isCashBucket ? '现金余额（元）' : '每只持仓的累计投入（元）' }}
          </p>
          <div class="mt-1 min-h-0 flex-1">
            <VChart
              class="h-full w-full"
              :option="chartOption"
              autoresize
              :update-options="{ notMerge: true }"
            />
          </div>
        </div>
      </transition>
    </div>

    <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
      塔高 = 当前市值；点「演变」后每只资产按定投计划长高，只算投入（含申购费）、不含未来涨跌。
      现金塔按「定投分析」那张现金卡的算式走：每月 = 上月 + 每月固定新增 − 当月定投扣款
      （本月只减不加，新增是整月假设），支出超过余额时余额为负，塔高按 0 画。
      窗口为 {{ panorama?.asOf ?? '—' }} 起 12 个自然月（本月只算剩余的扣款日）。
      点某座塔查看该类的逐只明细，点空白处回到全景。镜头可自由摆位：拖拽旋转、滚轮缩放，
      右键（或 Shift + 拖拽）平移，摆歪了用左下角「复位视角」回到默认机位。
    </p>
    <p v-if="error" class="mt-2 text-sm text-destructive">加载失败：{{ error.message }}</p>

    <PageFooter />
  </div>
</template>
