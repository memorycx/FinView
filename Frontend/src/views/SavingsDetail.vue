<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { ArrowLeft, Check, Edit, Loader2, Plus, MoreHorizontal } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Button from '@/components/ui/Button.vue'
import PageFooter from '@/components/PageFooter.vue'
import { isDark } from '@/composables/useTheme'
import { useRequest } from '@/composables/useApi'
import { getPlanCandidates, getSavingsPlan, linkPlanAsset, unlinkPlanAsset } from '@/api'
import type { SavingsPlanCandidate } from '@/api/types'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import {
  formatCNY,
  formatPct,
  profit,
  savingsEstimatedDate,
  savingsHasReturn,
  savingsHasTarget,
  savingsProgress,
  savingsRemaining,
  savingsReturnRate,
} from '@/lib/finance'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])

const route = useRoute()
const router = useRouter()

const planId = computed(() => String(route.params.id ?? ''))

const { data, loading, reload } = useRequest(() => getSavingsPlan(planId.value))

// 路由参数变化时（如从"新建计划"跳回具体计划）重新加载
watch(planId, () => reload())

const plan = computed(() => data.value ?? null)

/** 总资产计划默认包含全部未归档资产，没有「添加/移除」这回事 */
const isAssetPlan = computed(() => plan.value?.type === 'asset')

function goBack() {
  router.push({ name: 'savings' })
}

/* ==================== 「添加资产」面板（生活保障 / 愿望专用） ==================== */

const pickerOpen = ref(false)
const candidates = ref<SavingsPlanCandidate[]>([])
const candidatesLoading = ref(false)
/** 面板内的错误提示（与页面级的加载错误分开：这里是关联/解除失败了） */
const pickerError = ref('')
/** 正在切换的那一行的资产 id：切的时候禁用其它行，避免连点 */
const togglingAsset = ref<string | null>(null)

async function loadCandidates() {
  candidatesLoading.value = true
  pickerError.value = ''
  try {
    candidates.value = await getPlanCandidates(planId.value)
  } catch (e) {
    pickerError.value = e instanceof Error ? e.message : '候选资产加载失败'
  } finally {
    candidatesLoading.value = false
  }
}

async function togglePicker() {
  pickerOpen.value = !pickerOpen.value
  if (pickerOpen.value) await loadCandidates()
}

/**
 * 点一行：已关联 → 解除，未关联 → 关联。
 * 两个接口都直接返回更新后的计划详情，写回 data 就行，不用再 GET 一次；
 * 候选列表跟着重载（linked 标记要跟着变）。
 */
async function toggleLink(c: SavingsPlanCandidate) {
  if (togglingAsset.value || (!c.linked && c.archived)) return
  togglingAsset.value = c.id
  pickerError.value = ''
  try {
    data.value = c.linked
      ? await unlinkPlanAsset(planId.value, c.id)
      : await linkPlanAsset(planId.value, c.id)
    await loadCandidates()
  } catch (e) {
    pickerError.value = e instanceof Error ? e.message : '操作失败，请稍后重试'
  } finally {
    togglingAsset.value = null
  }
}

/** 圆环进度 */
const RING_R = 58
const RING_CIRC = 2 * Math.PI * RING_R
/**
 * 圆环里画多少：设了目标 = 完成度；没目标 = 这组资产的整体收益率（负的夹到 0、超过 100% 夹到满环，
 * 数字本身照实显示）。
 */
const ringFill = computed(() => {
  if (!plan.value) return 0
  if (savingsHasTarget(plan.value)) return savingsProgress(plan.value)
  return Math.min(1, Math.max(0, savingsReturnRate(plan.value)))
})

const ringOffset = computed(() => RING_CIRC * (1 - ringFill.value))

/** 计划整体的累计收益（只算非现金资产）：收益率旁边那个金额 */
const planProfit = computed(() => (plan.value?.returnStats ? profit(plan.value.returnStats) : 0))

/** 带正负号的金额（收益/亏损显示用）；负数由 formatCNY 自动带 -，这里只补正号 */
function signedCNY(n: number) {
  return n > 0 ? `+${formatCNY(n)}` : formatCNY(n)
}

/** 资产金额变化图表 */
const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

const chartOption = computed<EChartsOption>(() => {
  void themeTick.value
  if (!plan.value) return {}
  const months = plan.value.series.map((s) => s.month)
  const values = plan.value.series.map((s) => s.amount)

  const axisLabelColor = themeColor('--muted-foreground')
  const foreground = themeColor('--foreground')
  const gridColor = themeColor('--border', 0.5)
  const lineColor = themeColor('--chart-1')

  return {
    grid: { left: 8, right: 8, top: 20, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: foreground, fontSize: 13, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const p = (Array.isArray(params) ? params[0] : params) as {
          axisValue: string
          value: number
        }
        return `<div style="font-weight:600;font-size:13px;color:${foreground}">${p.axisValue}</div>
          <div style="margin-top:4px;font-family:${MONO_FONT};font-weight:600;color:${lineColor}">${formatCNY(p.value)}</div>`
      },
    },
    xAxis: {
      type: 'category',
      data: months,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: axisLabelColor,
        fontSize: 12,
        fontFamily: SANS_FONT,
      },
    },
    yAxis: {
      type: 'value',
      show: false,
    },
    series: [
      {
        name: '资产金额',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: true,
        data: values,
        lineStyle: { width: 3, color: lineColor },
        itemStyle: { color: lineColor, borderColor: themeColor('--card'), borderWidth: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: themeColor('--chart-1', 0.25) },
              { offset: 1, color: themeColor('--chart-1', 0.02) },
            ],
          },
        },
      },
    ],
  }
})

function pctOf(amount: number, total: number) {
  return total === 0 ? '0' : ((amount / total) * 100).toFixed(1)
}

/**
 * 「比目标提前几个月」。
 * 原来它是模板里的一串行内日期运算，`targetDate` 为空串（后端对 null 日期的兜底）时
 * `new Date("")` 是 NaN，页面会直接显示「比目标提前 NaN 个月」。放到这里算并返回 null，
 * 模板就整个徽章不渲染。新建的计划日期必填，这条只对历史上可能存在的空日期行生效。
 */
const monthsAhead = computed<number | null>(() => {
  if (!plan.value?.targetDate) return null
  const target = new Date(plan.value.targetDate).getTime()
  const estimated = new Date(`${savingsEstimatedDate(plan.value)}-01`).getTime()
  if (Number.isNaN(target) || Number.isNaN(estimated)) return null
  return Math.max(0, Math.round((target - estimated) / (1000 * 60 * 60 * 24 * 30)))
})
</script>

<template>
  <!-- min-h-screen + flex-col：三种状态（加载中 / 详情 / 未找到）都把脚注压在界面底部 -->
  <div class="flex min-h-screen flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 加载中 -->
    <div
      v-if="loading"
      class="flex flex-1 flex-col items-center justify-center gap-3 text-sm text-muted-foreground"
    >
      数据加载中…
    </div>

    <div v-else-if="plan" class="mx-auto flex w-full max-w-[1400px] flex-1 flex-col">
    <!-- 占位：对齐返回按钮区 -->
    <div class="mb-4 shrink-0 lg:mb-3 flex items-center gap-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>

    <!-- 顶部操作栏 -->
    <div class="mb-6 flex flex-wrap items-center gap-3">
      <Button variant="ghost" size="sm" class="gap-1.5" @click="goBack">
        <ArrowLeft class="size-4" />
        返回
      </Button>
      <div class="flex-1" />
      <Button variant="ghost" size="sm" class="gap-1.5">
        <Edit class="size-4" />
        编辑
      </Button>
    </div>

    <!-- 主内容：左侧详情 / 右侧进度分析 -->
    <div class="grid gap-5 xl:grid-cols-[1.6fr_1fr]">
      <!-- 左列 -->
      <div class="flex flex-col gap-5">
        <!-- 头部卡片：基本信息 + 圆环 -->
        <Card class="gap-0 p-0 overflow-hidden">
          <!-- 头图（没填封面图的新计划退回落款字占位块，不然顶部会缺一块） -->
          <div
            v-if="plan.image"
            class="h-40 w-full overflow-hidden bg-muted"
          >
            <img
              :src="plan.image"
              :alt="plan.name"
              class="h-full w-full object-cover opacity-80"
            />
          </div>
          <div
            v-else
            class="flex h-40 w-full items-center justify-center bg-gradient-to-br from-brand/15 via-brand/5 to-transparent"
            aria-hidden="true"
          >
            <span class="font-mono text-5xl font-bold text-brand/60">{{ plan.name.slice(0, 1) }}</span>
          </div>

          <div class="p-6">
            <div class="flex flex-wrap items-start gap-6">
              <!-- 左侧：图标名称 -->
              <div class="flex items-start gap-4">
                <div>
                  <h1 class="text-2xl font-bold tracking-tight">{{ plan.name }}</h1>
                  <p class="mt-1 text-sm text-muted-foreground">{{ plan.description }}</p>
                  <div class="mt-2 flex items-center gap-2">
                    <span
                      class="rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground"
                    >
                      {{
                        plan.type === 'asset'
                          ? '总资产'
                          : plan.type === 'life'
                            ? '生活保障'
                            : '愿望'
                      }}
                    </span>
                    <span
                      v-if="savingsHasTarget(plan)"
                      class="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground"
                    >
                      目标日期 {{ plan.targetDate.slice(0, 7) }}
                    </span>
                    <span
                      v-else
                      class="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground"
                    >
                      未设定目标
                    </span>
                  </div>
                </div>
              </div>

              <!-- 右侧：圆环进度 -->
              <div class="ml-auto flex items-center gap-4 sm:gap-8">
                <div class="relative shrink-0">
                  <svg width="160" height="160" viewBox="0 0 160 160">
                    <circle
                      cx="80"
                      cy="80"
                      :r="RING_R"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="12"
                      class="text-muted/70"
                    />
                    <circle
                      cx="80"
                      cy="80"
                      :r="RING_R"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="12"
                      stroke-linecap="round"
                      :stroke-dasharray="RING_CIRC"
                      :stroke-dashoffset="ringOffset"
                      transform="rotate(-90 80 80)"
                      class="text-brand transition-[stroke-dashoffset] duration-700"
                    />
                  </svg>
                  <div class="absolute inset-0 flex flex-col items-center justify-center">
                    <!-- 有目标画完成度；没目标画这组资产的整体收益率（超过 100% 夹到满环） -->
                    <span
                      class="font-mono text-2xl font-bold tabular-nums"
                      :style="
                        savingsHasTarget(plan)
                          ? undefined
                          : { color: savingsReturnRate(plan) >= 0 ? 'var(--gain)' : 'var(--loss)' }
                      "
                    >
                      {{ (ringFill * 100).toFixed(0) }}
                      <span class="text-base text-muted-foreground">%</span>
                    </span>
                    <span class="text-xs text-muted-foreground">
                      {{ savingsHasTarget(plan) ? '完成度' : '总体收益率' }}
                    </span>
                  </div>
                </div>
                <div class="space-y-2 text-right">
                  <div class="flex items-baseline gap-1">
                    <span class="font-mono text-2xl font-bold tabular-nums">{{ formatCNY(plan.currentAmount) }}</span>
                    <span v-if="savingsHasTarget(plan)" class="text-sm text-muted-foreground">
                      / {{ formatCNY(plan.targetAmount ?? 0) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 三个统计：有目标看进度，没目标看这组资产的收益 -->
            <div class="mt-6 grid grid-cols-3 gap-3 rounded-2xl bg-muted/30 p-3 sm:gap-4 sm:p-4">
              <template v-if="savingsHasTarget(plan)">
                <div>
                  <p class="text-xs text-muted-foreground">还差金额</p>
                  <p
                    class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg"
                    :style="{ color: savingsRemaining(plan) > 0 ? 'var(--loss)' : 'var(--gain)' }"
                  >
                    {{ formatCNY(savingsRemaining(plan)) }}
                  </p>
                </div>
                <div>
                  <p class="text-xs text-muted-foreground">预计完成时间</p>
                  <p class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg">
                    {{ savingsEstimatedDate(plan) }}
                  </p>
                </div>
                <div>
                  <p class="text-xs text-muted-foreground">每月计划存入</p>
                  <p class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg">
                    {{ formatCNY(plan.monthlyPlanAmount ?? 0) }}
                  </p>
                </div>
              </template>
              <template v-else>
                <div>
                  <p class="text-xs text-muted-foreground">当前市值</p>
                  <p class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg">
                    {{ formatCNY(plan.currentAmount) }}
                  </p>
                </div>
                <div>
                  <p class="text-xs text-muted-foreground">累计收益</p>
                  <p
                    class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg"
                    :style="{ color: planProfit >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                  >
                    {{ savingsHasReturn(plan) ? signedCNY(planProfit) : '—' }}
                  </p>
                </div>
                <div>
                  <p class="text-xs text-muted-foreground">累计投入</p>
                  <p class="mt-1 font-mono text-base font-bold tabular-nums sm:text-lg">
                    {{ formatCNY(plan.returnStats?.invested ?? 0) }}
                  </p>
                </div>
              </template>
            </div>

            <!-- 励志语 -->
            <div
              v-if="plan.quote"
              class="mt-4 rounded-xl border-l-4 border-brand/60 bg-brand/5 px-4 py-3"
            >
              <p class="text-sm italic text-brand/80">"{{ plan.quote }}"</p>
            </div>
          </div>
        </Card>

        <!-- 包含的资产 -->
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div>
              <p class="text-xs font-medium text-muted-foreground">配置</p>
              <h2 class="text-lg font-bold">
                包含的资产
                <span class="ml-2 text-sm font-normal text-muted-foreground">({{ plan.assets.length }})</span>
              </h2>
            </div>
            <!-- 总资产计划默认全含，没有「添加」这回事；生活保障 / 愿望才给面板入口 -->
            <Button
              v-if="!isAssetPlan"
              variant="ghost"
              size="sm"
              class="gap-1.5"
              :disabled="togglingAsset !== null"
              @click="togglePicker"
            >
              <Plus class="size-4" />
              {{ pickerOpen ? '收起' : '添加资产' }}
            </Button>
            <span v-else class="text-xs text-muted-foreground">默认包含全部未归档资产</span>
          </CardHeader>

          <!-- 候选资产面板：点一行切换关联/解除；已关联的排在前面 -->
          <CardContent v-if="pickerOpen" class="px-6 pt-3">
            <div class="rounded-xl border border-border bg-muted/20 p-3">
              <p v-if="candidatesLoading" class="px-1 py-2 text-sm text-muted-foreground">加载中…</p>
              <p v-else-if="!candidates.length" class="px-1 py-2 text-sm text-muted-foreground">
                名下还没有资产，先到「录入数据」记一笔。
              </p>
              <ul v-else class="max-h-[280px] space-y-1 overflow-y-auto">
                <li v-for="c in candidates" :key="c.id">
                  <button
                    type="button"
                    :disabled="togglingAsset !== null || (!c.linked && c.archived)"
                    class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                    :title="
                      c.archived
                        ? '已归档的资产不计入计划，先取消归档再关联'
                        : c.linked
                          ? '点一下从计划里移除'
                          : '点一下加入计划'
                    "
                    @click="toggleLink(c)"
                  >
                    <span
                      class="flex size-5 shrink-0 items-center justify-center rounded-full border text-xs"
                      :class="c.linked ? 'border-brand bg-brand text-brand-foreground' : 'border-border text-transparent'"
                    >
                      <Check v-if="c.linked" class="size-3.5" />
                    </span>
                    <span class="min-w-0 flex-1 truncate text-sm">
                      {{ c.name }}
                      <span class="ml-1 font-mono text-xs text-muted-foreground">{{ c.code }}</span>
                    </span>
                    <span v-if="c.archived" class="shrink-0 text-xs text-muted-foreground">已归档</span>
                    <span class="shrink-0 font-mono text-sm tabular-nums text-muted-foreground">
                      {{ formatCNY(c.amount) }}
                    </span>
                    <Loader2 v-if="togglingAsset === c.id" class="size-4 shrink-0 animate-spin text-muted-foreground" />
                  </button>
                </li>
              </ul>
              <p v-if="pickerError" class="mt-2 px-1 text-xs text-destructive">{{ pickerError }}</p>
            </div>
          </CardContent>

          <CardContent class="px-6 pb-6 pt-4">
            <!-- 空列表要有个说法，否则看起来像没加载出来 -->
            <p
              v-if="!plan.assets.length"
              class="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground"
            >
              这个计划还没有资产。点右上角「添加资产」把想计入的基金选进来，进度就会跟着它们的市值走。
            </p>
            <ul v-else class="space-y-3">
              <li
                v-for="a in plan.assets"
                :key="a.id"
                class="flex items-center gap-3 rounded-xl border border-border/60 px-4 py-3 transition-colors hover:border-border hover:bg-muted/30"
              >
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold">
                    {{ a.name }}
                    <span class="ml-1 font-mono text-xs font-normal text-muted-foreground">{{ a.code }}</span>
                  </p>
                  <div class="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                    <span
                      class="block h-full rounded-full bg-brand"
                      :style="{ width: `${pctOf(a.amount, plan.currentAmount)}%` }"
                    />
                  </div>
                </div>
                <div class="text-right">
                  <p class="font-mono text-sm font-semibold tabular-nums">
                    {{ formatCNY(a.amount) }}
                  </p>
                  <p class="text-xs text-muted-foreground">
                    占比 {{ pctOf(a.amount, plan.currentAmount) }}%
                  </p>
                </div>
              </li>
            </ul>
          </CardContent>
        </Card>

        <!-- 资产金额变化 -->
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div>
              <p class="text-xs font-medium text-muted-foreground">趋势</p>
              <h2 class="text-lg font-bold">资产金额变化</h2>
            </div>
            <div class="inline-flex rounded-xl border border-border bg-background p-1 text-sm">
              <button
                v-for="opt in ['近3个月', '近6个月', '近1年']"
                :key="opt"
                type="button"
                class="rounded-lg px-3 py-1 text-xs font-medium transition-colors"
                :class="opt === '近6个月' ? 'bg-brand text-brand-foreground' : 'text-muted-foreground hover:text-foreground'"
              >
                {{ opt }}
              </button>
            </div>
          </CardHeader>
          <CardContent class="px-6 pb-6 pt-2">
            <div class="h-[240px] w-full">
              <VChart
                class="h-full w-full"
                :option="chartOption"
                autoresize
                :update-options="{ notMerge: true }"
              />
            </div>
          </CardContent>
        </Card>

        <!-- 计划备注 -->
        <Card v-if="plan.note || plan.noteImage" class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div>
              <p class="text-xs font-medium text-muted-foreground">记录</p>
              <h2 class="text-lg font-bold">计划备注</h2>
            </div>
            <Button variant="ghost" size="sm" class="gap-1.5">
              <Edit class="size-4" />
              编辑
            </Button>
          </CardHeader>
          <CardContent class="space-y-3 px-6 pb-6 pt-2">
            <p class="text-sm leading-relaxed text-muted-foreground">{{ plan.note }}</p>
            <img
              v-if="plan.noteImage"
              :src="plan.noteImage"
              alt="备注图片"
              class="h-40 w-full rounded-xl object-cover"
            />
          </CardContent>
        </Card>
      </div>

      <!-- 右列：有目标 = 目标进度分析；没目标 = 收益分析（口径与基金列表一致） -->
      <div class="flex flex-col gap-5">
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div>
              <p class="text-xs font-medium text-muted-foreground">分析</p>
              <h2 class="text-lg font-bold">
                {{ savingsHasTarget(plan) ? '目标进度分析' : '收益分析' }}
              </h2>
            </div>
            <Button variant="ghost" size="icon" class="size-8">
              <MoreHorizontal class="size-4" />
            </Button>
          </CardHeader>
          <CardContent class="px-6 pb-6 pt-4">
            <ul class="space-y-4 text-sm">
              <template v-if="savingsHasTarget(plan)">
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">目标金额</span>
                  <span class="font-mono font-semibold tabular-nums">{{ formatCNY(plan.targetAmount ?? 0) }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">当前金额</span>
                  <span class="font-mono font-semibold tabular-nums">{{ formatCNY(plan.currentAmount) }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">还差金额</span>
                  <span
                    class="font-mono font-semibold tabular-nums"
                    :style="{ color: savingsRemaining(plan) > 0 ? 'var(--loss)' : 'var(--gain)' }"
                  >
                    {{ formatCNY(savingsRemaining(plan)) }}
                  </span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">完成度</span>
                  <span class="font-mono font-semibold tabular-nums" style="color: var(--gain)">
                    {{ formatPct(savingsProgress(plan)) }}
                  </span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">开始日期</span>
                  <span class="font-mono tabular-nums">{{ plan.startDate }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">目标日期</span>
                  <span class="font-mono tabular-nums">{{ plan.targetDate }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">预计完成</span>
                  <span
                    v-if="monthsAhead !== null"
                    class="rounded-full bg-brand/10 px-2 py-0.5 text-xs font-medium text-brand"
                  >
                    比目标提前 {{ monthsAhead }} 个月
                  </span>
                  <span v-else class="font-mono tabular-nums">{{ savingsEstimatedDate(plan) }}</span>
                </li>
              </template>
              <template v-else>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">当前市值</span>
                  <span class="font-mono font-semibold tabular-nums">{{ formatCNY(plan.currentAmount) }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">累计投入</span>
                  <span class="font-mono font-semibold tabular-nums">
                    {{ formatCNY(plan.returnStats?.invested ?? 0) }}
                  </span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">累计收益</span>
                  <span
                    class="font-mono font-semibold tabular-nums"
                    :style="{ color: planProfit >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                  >
                    {{ savingsHasReturn(plan) ? signedCNY(planProfit) : '—' }}
                  </span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">总体收益率</span>
                  <span
                    class="font-mono font-semibold tabular-nums"
                    :style="{ color: savingsReturnRate(plan) >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                  >
                    {{ savingsHasReturn(plan) ? formatPct(savingsReturnRate(plan)) : '—' }}
                  </span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">开始日期</span>
                  <span class="font-mono tabular-nums">{{ plan.startDate }}</span>
                </li>
                <li class="flex items-center justify-between">
                  <span class="text-muted-foreground">包含资产</span>
                  <span class="font-mono tabular-nums">{{ plan.assets.length }} 项</span>
                </li>
              </template>
            </ul>
          </CardContent>
        </Card>
      </div>
    </div>
    <!-- /v-else-if="plan"：关闭第 151 行的容器，让下面的 v-else 成为同级（原缺失，构建报 missing end tag） -->
    </div>

    <!-- 未找到 -->
    <div v-else class="flex flex-1 flex-col items-center justify-center gap-3 text-center">
      <p class="text-lg font-bold">未找到该存钱计划</p>
      <p class="text-sm text-muted-foreground">该计划可能已被删除或 ID 不正确</p>
      <Button @click="goBack">返回列表</Button>
    </div>

    <PageFooter />
  </div>
</template>
