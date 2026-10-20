<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { ArrowLeft, Check, Edit, Loader2, Plus } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Button from '@/components/ui/Button.vue'
import PageFooter from '@/components/PageFooter.vue'
import SavingsInflowCard from '@/components/SavingsInflowCard.vue'
import { isDark } from '@/composables/useTheme'
import { useRequest } from '@/composables/useApi'
import { getPlanCandidates, getSavingsPlan, linkPlanAsset, unlinkPlanAsset } from '@/api'
import type { SavingsAsset, SavingsPlanCandidate } from '@/api/types'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import {
  formatCNY,
  formatPct,
  frequencyLabel,
  profit,
  returnRate,
  savingsHasReturn,
  savingsHasTarget,
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
  // 已归档、或已经被别的计划占用（一一对应规则）时不能关联；解除关联不受限
  if (togglingAsset.value || (!c.linked && (c.archived || !!c.linkedPlanName))) return
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

/** 计划整体的累计收益（只算非现金资产）：收益率旁边那个金额 */
const planProfit = computed(() => (plan.value?.returnStats ? profit(plan.value.returnStats) : 0))

/** 带正负号的金额（收益/亏损显示用）；负数由 formatCNY 自动带 -，这里只补正号 */
function signedCNY(n: number) {
  return n > 0 ? `+${formatCNY(n)}` : formatCNY(n)
}

/** 资产行左侧「定投」徽标的 hover 文案：当前在投 · 周投 · 每期 ¥100.00 */
function dcaTitle(a: SavingsAsset) {
  const freq =
    a.dcaFrequency === 'daily' || a.dcaFrequency === 'weekly' || a.dcaFrequency === 'monthly'
      ? frequencyLabel(a.dcaFrequency)
      : '定投'
  const amount = a.dcaAmount == null ? '' : ` · 每期 ${formatCNY(a.dcaAmount)}`
  return `当前在投 · ${freq}${amount}`
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
      textStyle: { color: foreground, fontSize: 14, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const p = (Array.isArray(params) ? params[0] : params) as {
          axisValue: string
          value: number
        }
        return `<div style="font-weight:600;font-size:14px;color:${foreground}">${p.axisValue}</div>
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
        fontSize: 14,
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

    <div v-else-if="plan" class="mx-auto flex w-full max-w-[1100px] flex-1 flex-col">
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
      <!-- 添加资产入口（总资产计划默认全含，不显示） -->
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
      <Button variant="ghost" size="sm" class="gap-1.5">
        <Edit class="size-4" />
        编辑
      </Button>
    </div>

    <!-- 主内容 -->
    <div class="flex flex-col gap-5">
        <!-- 头部：两个并排小卡（计划摘要 + 计划概览），替代原先的 banner + 圆环 + 三统计 -->
        <div class="grid gap-4 sm:grid-cols-2">
          <!-- 计划摘要：名称作标签 + 总市值大数字 + 收益率/收益金额 + 累计投入 -->
          <Card class="gap-0 p-0">
            <div class="p-5">
              <h2 class="truncate text-lg font-bold">{{ plan.name }}</h2>
              <p class="mt-2 font-mono text-3xl font-bold tracking-tight tabular-nums">
                {{ formatCNY(plan.currentAmount) }}
              </p>
              <!-- 收益率 + 收益金额（没买入记录时算不出来，不显示这一行） -->
              <div
                v-if="savingsHasReturn(plan)"
                class="mt-2 flex items-baseline gap-3"
              >
                <span
                  class="font-mono text-base font-bold tabular-nums"
                  :style="{ color: savingsReturnRate(plan) >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                >
                  {{ formatPct(savingsReturnRate(plan)) }}
                </span>
                <span
                  class="font-mono text-base font-bold tabular-nums"
                  :style="{ color: planProfit >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                >
                  {{ signedCNY(planProfit) }}
                </span>
              </div>
              <div class="mt-4 border-t border-border pt-3 text-sm text-muted-foreground">
                累计投入
                <span class="ml-2 font-mono text-base font-bold tabular-nums text-foreground">
                  {{ formatCNY(plan.returnStats?.invested ?? 0) }}
                </span>
              </div>
            </div>
          </Card>

          <!-- 计划概览：资产数 + 开始日期 + 目标金额（或未设置） -->
          <Card class="gap-0 p-0">
            <div class="p-5">
              <h2 class="text-lg font-bold">计划概览</h2>
              <p class="mt-2 text-2xl font-bold tabular-nums">
                {{ plan.assets.length }}
                <span class="ml-1 text-sm font-normal text-muted-foreground">项资产</span>
              </p>
              <div class="mt-4 space-y-2 text-sm text-muted-foreground">
                <div class="flex items-center justify-between gap-2">
                  <span>开始日期</span>
                  <span class="font-mono font-semibold tabular-nums text-foreground">{{ plan.startDate }}</span>
                </div>
                <div class="flex items-center justify-between gap-2">
                  <span>目标金额</span>
                  <span
                    v-if="savingsHasTarget(plan)"
                    class="font-mono font-semibold tabular-nums text-foreground"
                  >
                    {{ formatCNY(plan.targetAmount ?? 0) }}
                  </span>
                  <span v-else class="text-muted-foreground">尚未设置</span>
                </div>
              </div>
            </div>
          </Card>
        </div>

        <!-- 资产配置 -->
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div class="flex items-baseline gap-2">
              <h2 class="text-lg font-bold">资产配置</h2>
              <span class="ml-1 text-sm text-muted-foreground">{{ plan.assets.length }} 项</span>
            </div>
          </CardHeader>

          <!-- 候选资产面板：点一行切换关联/解除；已关联的排在前面 -->
          <CardContent v-if="pickerOpen" class="px-6 pt-3">
            <div class="rounded-xl border border-border bg-muted/20 p-3">
              <p v-if="candidatesLoading" class="px-1 py-2 text-sm text-muted-foreground">加载中…</p>
              <p v-else-if="!candidates.length" class="px-1 py-2 text-sm text-muted-foreground">
                名下还没有资产，先到投资看板点「添加资产」记一笔。
              </p>
              <ul v-else class="max-h-[280px] space-y-1 overflow-y-auto">
                <li v-for="c in candidates" :key="c.id">
                  <button
                    type="button"
                    :disabled="togglingAsset !== null || (!c.linked && (c.archived || !!c.linkedPlanName))"
                    class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                    :title="
                      c.archived
                        ? '已归档的资产不计入计划，先取消归档再关联'
                        : c.linked
                          ? '点一下从计划里移除'
                          : c.linkedPlanName
                            ? `已在「${c.linkedPlanName}」里，一个资产只能属于一个分类`
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
                    <span class="min-w-0 flex-1 truncate text-base">
                      {{ c.name }}
                      <span class="ml-1 font-mono text-sm text-muted-foreground">{{ c.code }}</span>
                    </span>
                    <span v-if="c.archived" class="shrink-0 text-sm text-muted-foreground">已归档</span>
                    <span v-else-if="!c.linked && c.linkedPlanName" class="shrink-0 text-sm text-muted-foreground">
                      已在「{{ c.linkedPlanName }}」
                    </span>
                    <span class="shrink-0 font-mono text-base font-semibold tabular-nums text-muted-foreground">
                      {{ formatCNY(c.amount) }}
                    </span>
                    <Loader2 v-if="togglingAsset === c.id" class="size-4 shrink-0 animate-spin text-muted-foreground" />
                  </button>
                </li>
              </ul>
              <p v-if="pickerError" class="mt-2 px-1 text-sm text-destructive">{{ pickerError }}</p>
            </div>
          </CardContent>

          <CardContent class="px-6 pb-6 pt-2">
            <!-- 空列表要有个说法，否则看起来像没加载出来 -->
            <p
              v-if="!plan.assets.length"
              class="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground"
            >
              这个计划还没有资产。点右上角「添加」把想计入的基金选进来，进度就会跟着它们的市值走。
            </p>
            <!-- 行间以分割线分隔（不再每项套边框），与图中样式一致 -->
            <ul v-else class="divide-y divide-border">
              <!--
                移动端：名称一行、三个数据列换行铺满；桌面端（sm+）并成一行靠右。
                累计收益 / 收益率取后端算好的 returnStats（与计划整体、基金页同口径）；
                现金没有收益口径（returnStats 为 null），两列显示「—」。
              -->
              <!-- 表头：仅显示一次，与数据列对齐（sm+ 才显示，移动端各列换行铺满，表头无意义） -->
              <li class="hidden items-center gap-x-3 gap-y-2 py-3 first:pt-0 sm:flex">
                <!-- 与数据行左侧「定投」徽标同宽，保证名称列对齐 -->
                <div class="w-14 shrink-0"></div>
                <div class="min-w-0 flex-1"></div>
                <div class="flex shrink-0 items-center justify-end gap-8">
                  <div class="text-right sm:w-32">
                    <p class="text-sm text-muted-foreground">累计收益</p>
                  </div>
                  <div class="text-right sm:w-24">
                    <p class="text-sm text-muted-foreground">收益率</p>
                  </div>
                  <div class="text-right sm:w-32">
                    <p class="text-sm text-muted-foreground">金额</p>
                  </div>
                </div>
              </li>
              <li
                v-for="a in plan.assets"
                :key="a.id"
                class="flex flex-wrap items-center gap-x-3 gap-y-2 py-3 first:pt-0 last:pb-0"
              >
                <!-- 定投标识：点的颜色与图表里该资产的柱子同色；后端已把定投中的排在前面 -->
                <div class="flex w-14 shrink-0 items-center">
                  <span
                    v-if="a.dcaActive"
                    class="inline-flex items-center gap-1 rounded-full bg-brand/10 px-2 py-0.5 text-xs font-medium text-brand"
                    :title="dcaTitle(a)"
                  >
                    <span
                      class="size-1.5 shrink-0 rounded-full"
                      :style="{ backgroundColor: `var(${a.colorVar || '--chart-1'})` }"
                    />
                    定投
                  </span>
                </div>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">
                    {{ a.name }}
                    <span class="ml-1 font-mono text-xs font-normal text-muted-foreground">{{ a.code }}</span>
                  </p>
                </div>
                <div
                  class="flex w-full items-center justify-between gap-4 sm:w-auto sm:shrink-0 sm:justify-end sm:gap-8"
                >
                  <div class="text-right sm:w-32">
                    <p
                      class="font-mono text-base font-bold tabular-nums"
                      :class="a.returnStats ? '' : 'text-muted-foreground'"
                      :style="
                        a.returnStats
                          ? { color: profit(a.returnStats) >= 0 ? 'var(--gain)' : 'var(--loss)' }
                          : undefined
                      "
                    >
                      {{ a.returnStats ? signedCNY(profit(a.returnStats)) : '—' }}
                    </p>
                  </div>
                  <div class="text-right sm:w-24">
                    <p
                      class="font-mono text-base font-bold tabular-nums"
                      :class="a.returnStats ? '' : 'text-muted-foreground'"
                      :style="
                        a.returnStats
                          ? { color: returnRate(a.returnStats) >= 0 ? 'var(--gain)' : 'var(--loss)' }
                          : undefined
                      "
                    >
                      {{ a.returnStats ? formatPct(returnRate(a.returnStats)) : '—' }}
                    </p>
                  </div>
                  <div class="text-right sm:w-32">
                    <p class="font-mono text-base font-bold tabular-nums">
                      {{ formatCNY(a.amount) }}
                    </p>
                  </div>
                </div>
              </li>
            </ul>
          </CardContent>
        </Card>

        <!-- 未来资金流入：定投计划未来 12 个月的现金流预测（柱状图 + 累计折线） -->
        <SavingsInflowCard v-if="plan.inflowForecast" :forecast="plan.inflowForecast" />

        <!-- 资产金额变化 -->
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-col gap-1 px-6 pt-6">
            <div class="flex flex-row items-center justify-between">
              <p class="text-lg font-bold">趋势</p>
              <div class="inline-flex rounded-xl border border-border bg-background p-1 text-sm">
                <button
                  v-for="opt in ['近3个月', '近6个月', '近1年']"
                  :key="opt"
                  type="button"
                  class="rounded-lg px-3 py-1 text-sm font-medium transition-colors"
                  :class="opt === '近6个月' ? 'bg-brand text-brand-foreground' : 'text-muted-foreground hover:text-foreground'"
                >
                  {{ opt }}
                </button>
              </div>
            </div>
            <h2 class="text-sm font-medium text-muted-foreground">资产金额变化</h2>
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
              <p class="text-sm font-medium text-muted-foreground">记录</p>
              <h2 class="text-lg font-bold">计划备注</h2>
            </div>
            <Button variant="ghost" size="sm" class="gap-1.5">
              <Edit class="size-4" />
              编辑
            </Button>
          </CardHeader>
          <CardContent class="space-y-3 px-6 pb-6 pt-2">
            <p class="text-base leading-relaxed text-muted-foreground">{{ plan.note }}</p>
            <img
              v-if="plan.noteImage"
              :src="plan.noteImage"
              alt="备注图片"
              class="h-40 w-full rounded-xl object-cover"
            />
          </CardContent>
        </Card>
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
