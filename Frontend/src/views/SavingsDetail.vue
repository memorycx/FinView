<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import { ArrowLeft, Edit, Plus, MoreHorizontal } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Button from '@/components/ui/Button.vue'
import { isDark } from '@/composables/useTheme'
import { useRequest } from '@/composables/useApi'
import { getSavingsPlan } from '@/api'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import {
  formatCNY,
  formatPct,
  savingsEstimatedDate,
  savingsProgress,
  savingsRemaining,
} from '@/lib/finance'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])

const route = useRoute()
const router = useRouter()

const planId = computed(() => String(route.params.id ?? ''))

const { data, loading, reload } = useRequest(() => getSavingsPlan(planId.value))

// 路由参数变化时（如从"新建计划"跳回具体计划）重新加载
watch(planId, () => reload())

const plan = computed(() => data.value ?? null)

function goBack() {
  router.push({ name: 'savings' })
}

/** 圆环进度 */
const RING_R = 58
const RING_CIRC = 2 * Math.PI * RING_R
const ringOffset = computed(() => {
  if (!plan.value) return 0
  return RING_CIRC * (1 - savingsProgress(plan.value))
})

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
</script>

<template>
  <!-- 加载中 -->
  <div
    v-if="loading"
    class="flex min-h-[50vh] flex-col items-center justify-center gap-3 text-sm text-muted-foreground"
  >
    数据加载中…
  </div>

  <div v-else-if="plan" class="mx-auto w-full max-w-[1400px] px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
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
          <!-- 头图 -->
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
                      {{ plan.type === 'asset' ? '总资产' : '小愿望' }}
                    </span>
                    <span class="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                      目标日期 {{ plan.targetDate.slice(0, 7) }}
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
                    <span class="font-mono text-2xl font-bold tabular-nums">
                      {{ (savingsProgress(plan) * 100).toFixed(0) }}
                      <span class="text-base text-muted-foreground">%</span>
                    </span>
                    <span class="text-xs text-muted-foreground">完成度</span>
                  </div>
                </div>
                <div class="space-y-2 text-right">
                  <div class="flex items-baseline gap-1">
                    <span class="font-mono text-2xl font-bold tabular-nums">{{ formatCNY(plan.currentAmount) }}</span>
                    <span class="text-sm text-muted-foreground">/ {{ formatCNY(plan.targetAmount) }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 三个统计 -->
            <div class="mt-6 grid grid-cols-3 gap-3 rounded-2xl bg-muted/30 p-3 sm:gap-4 sm:p-4">
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
                  {{ formatCNY(plan.monthlyPlanAmount) }}
                </p>
              </div>
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
            <Button variant="ghost" size="sm" class="gap-1.5">
              <Plus class="size-4" />
              添加资产
            </Button>
          </CardHeader>
          <CardContent class="px-6 pb-6 pt-4">
            <ul class="space-y-3">
              <li
                v-for="a in plan.assets"
                :key="a.name"
                class="flex items-center gap-3 rounded-xl border border-border/60 px-4 py-3 transition-colors hover:border-border hover:bg-muted/30"
              >
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold">{{ a.name }}</p>
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

      <!-- 右列：目标进度分析 -->
      <div class="flex flex-col gap-5">
        <Card class="gap-0 p-0">
          <CardHeader class="flex flex-row items-center justify-between px-6 pt-6">
            <div>
              <p class="text-xs font-medium text-muted-foreground">分析</p>
              <h2 class="text-lg font-bold">目标进度分析</h2>
            </div>
            <Button variant="ghost" size="icon" class="size-8">
              <MoreHorizontal class="size-4" />
            </Button>
          </CardHeader>
          <CardContent class="px-6 pb-6 pt-4">
            <ul class="space-y-4 text-sm">
              <li class="flex items-center justify-between">
                <span class="text-muted-foreground">目标金额</span>
                <span class="font-mono font-semibold tabular-nums">{{ formatCNY(plan.targetAmount) }}</span>
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
                  class="rounded-full bg-brand/10 px-2 py-0.5 text-xs font-medium text-brand"
                >
                  比目标提前 {{
                    Math.max(
                      0,
                      Math.round(
                        (new Date(plan.targetDate).getTime() -
                          new Date(`${savingsEstimatedDate(plan)}-01`).getTime()) /
                          (1000 * 60 * 60 * 24 * 30),
                      ),
                    )
                  }} 个月
                </span>
              </li>
            </ul>
          </CardContent>
        </Card>
      </div>
    </div>
  </div>

  <!-- 未找到 -->
  <div v-else class="flex min-h-[50vh] flex-col items-center justify-center gap-3 text-center">
    <p class="text-lg font-bold">未找到该存钱计划</p>
    <p class="text-sm text-muted-foreground">该计划可能已被删除或 ID 不正确</p>
    <Button @click="goBack">返回列表</Button>
  </div>
</template>
