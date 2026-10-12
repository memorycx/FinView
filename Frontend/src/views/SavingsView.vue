<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  BarChart3,
  Calendar,
  Clock,
  Database,
  Plus,
  Target,
  TrendingUp,
} from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import PageFooter from '@/components/PageFooter.vue'
import { useRequest } from '@/composables/useApi'
import { getSavingsPlans } from '@/api'
import {
  formatCNY,
  formatPct,
  savingsHasReturn,
  savingsHasTarget,
  savingsProgress,
  savingsRemaining,
  savingsReturnRate,
} from '@/lib/finance'
import type { SavingsPlan, SavingsPlanType } from '@/api/types'

const router = useRouter()

const { data: savingsPlans } = useRequest(() => getSavingsPlans())

/** 计划类型的中文标签，列表徽章与 tab 共用（后端 type 的三取值） */
const TYPE_LABELS: Record<SavingsPlanType, string> = {
  asset: '总资产',
  life: '生活保障',
  wish: '愿望',
}

type TabKey = 'all' | SavingsPlanType
const activeTab = ref<TabKey>('all')

const tabs = computed<{ key: TabKey; label: string; count: number }[]>(() => {
  const plans = savingsPlans.value ?? []
  const total = plans.length
  const asset = plans.filter((p) => p.type === 'asset').length
  const life = plans.filter((p) => p.type === 'life').length
  const wish = plans.filter((p) => p.type === 'wish').length
  return [
    { key: 'all', label: '全部', count: total },
    { key: 'asset', label: '总资产', count: asset },
    { key: 'life', label: TYPE_LABELS.life, count: life },
    { key: 'wish', label: TYPE_LABELS.wish, count: wish },
  ]
})

const filteredPlans = computed(() => {
  const plans = savingsPlans.value ?? []
  if (activeTab.value === 'all') return plans
  return plans.filter((p) => p.type === activeTab.value)
})

/**
 * KPI 统计。
 *
 * 「当前市值」（原来叫「累计存入」）**不能把各计划的金额直接相加**：2026-10-05 起
 * 计划的 currentAmount 是按关联资产的最新市值现算的，而总资产计划包含全部未归档资产、
 * 生活保障 / 愿望又各自包含自己关联的那些 —— 同一只基金会出现在两个计划里，相加会重复计。
 * 所以：有总资产计划就用它一个（它已经全含）；没有就把它们关联的资产**按资产 id 去重**再合计。
 * （口径变化之前 currentAmount 是手工存的「已存金额」，相加是对的，别照旧逻辑改回去。）
 */
const kpi = computed(() => {
  const plans = savingsPlans.value ?? []
  const totalPlans = plans.length

  // 总目标金额只统计**设了目标的**计划（无目标的计划 targetAmount 为 null，贡献 0）
  const totalTarget = plans.reduce((sum, p) => sum + (p.targetAmount ?? 0), 0)

  const assetPlan = plans.find((p) => p.type === 'asset')
  const totalSaved = assetPlan
    ? assetPlan.currentAmount
    : [...new Map(plans.flatMap((p) => p.assets.map((a) => [a.id, a.amount] as const))).values()]
        .reduce((sum, amount) => sum + amount, 0)

  // 距离目标 = **各计划自己的缺口之和**，不能再写成「总目标 − 当前市值」：
  // 无目标计划里的市值不属于任何目标，用减法会把别的计划的缺口凭空填掉。
  const totalRemaining = plans.reduce(
    (sum, p) => sum + (savingsHasTarget(p) ? savingsRemaining(p) : 0),
    0,
  )
  return { totalPlans, totalSaved, totalTarget, totalRemaining }
})

/** 总资产计划 */
const assetPlan = computed(() =>
  (savingsPlans.value ?? []).find((p) => p.type === 'asset'),
)

/**
 * 「比计划快 / 慢」小标签的数值：**金额进度 − 时间进度**，单位是百分点。
 * - 时间进度 = 今天在 [startDate, targetDate] 之间走到哪儿（越界夹到 0~1）；
 * - 金额进度 = currentAmount / targetAmount（currentAmount 是关联资产的最新市值，后端算好的）。
 * 正数 = 攒得比时间表快，负数 = 落后。
 *
 * 这几个数是 2026-10-05 换掉写死的「比计划快 8.2%」时定的口径：原来那串是编的，
 * 现在金额是真的市值，就按真实的两条进度比。日期缺失 / 目标金额为 0 / 日期倒挂时返回 null，
 * 模板里整个标签不渲染（不显示没意义的数）。
 */
function scheduleDelta(plan: SavingsPlan): number | null {
  if (!savingsHasTarget(plan) || !plan.startDate || !plan.targetDate) return null
  const start = new Date(plan.startDate).getTime()
  const target = new Date(plan.targetDate).getTime()
  if (Number.isNaN(start) || Number.isNaN(target) || target <= start) return null
  const timeProgress = Math.min(1, Math.max(0, (Date.now() - start) / (target - start)))
  return (savingsProgress(plan) - timeProgress) * 100
}

/** 总资产计划的进度差；null = 不显示那个标签 */
const assetSchedule = computed(() =>
  assetPlan.value ? scheduleDelta(assetPlan.value) : null,
)

function goDetail(id: string) {
  router.push({ name: 'savings-detail', params: { id } })
}

/** 新建计划走独立命名路由：以前是 goDetail('new')，URL 一样但挂的是详情组件 → 必然 404 */
function goNew() {
  router.push({ name: 'savings-new' })
}

/** 目标日期 → "2026 年 12 月" */
function formatTargetMonth(d: string) {
  const [y, m] = d.split('-')
  return `${y} 年 ${Number(m)} 月`
}
</script>

<template>
  <!-- min-h-screen + flex-col：脚注永远落在界面底部 -->
  <div
    class="mx-auto flex min-h-screen w-full max-w-[1400px] flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4"
  >
    <!-- 占位：与投资看板的返回按钮区对齐 -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>

    <!-- 顶部标题 + 新建按钮 -->
    <div class="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div class="space-y-1.5">
        <h1 class="text-3xl font-bold tracking-tight">存钱计划</h1>
        <p class="text-sm text-muted-foreground">把钱和目标联系起来，让每一份努力都有意义。</p>
      </div>
      <Button class="gap-2" @click="goNew">
        <Plus class="size-4" />
        新建计划
      </Button>
    </div>

    <!-- KPI 统计 -->
    <div class="mb-10 grid grid-cols-2 gap-4 sm:grid-cols-4">
      <Card class="flex-row items-center gap-4 p-4">
        <div class="flex items-center justify-center rounded-full bg-brand/10 p-3">
          <Database class="size-5 text-brand" />
        </div>
        <div class="space-y-1">
          <p class="text-sm text-muted-foreground">计划总数</p>
          <p class="font-mono text-xl font-bold tabular-nums">{{ kpi.totalPlans }} 个</p>
        </div>
      </Card>
      <Card class="flex-row items-center gap-4 p-4">
        <div class="flex items-center justify-center rounded-full bg-brand/10 p-3">
          <BarChart3 class="size-5 text-brand" />
        </div>
        <div class="space-y-1">
          <p class="text-sm text-muted-foreground">当前市值</p>
          <p class="font-mono text-xl font-bold tabular-nums">{{ formatCNY(kpi.totalSaved) }}</p>
        </div>
      </Card>
      <Card class="flex-row items-center gap-4 p-4">
        <div class="flex items-center justify-center rounded-full bg-brand/10 p-3">
          <Target class="size-5 text-brand" />
        </div>
        <div class="space-y-1">
          <p class="text-sm text-muted-foreground">总目标金额</p>
          <p class="font-mono text-xl font-bold tabular-nums">{{ formatCNY(kpi.totalTarget) }}</p>
        </div>
      </Card>
      <Card class="flex-row items-center gap-4 p-4">
        <div class="flex items-center justify-center rounded-full bg-brand/10 p-3">
          <Clock class="size-5 text-brand" />
        </div>
        <div class="space-y-1">
          <p class="text-sm text-muted-foreground">距离目标</p>
          <p class="font-mono text-xl font-bold tabular-nums text-foreground">{{ formatCNY(kpi.totalRemaining) }}</p>
        </div>
      </Card>
    </div>

    <!-- 总资产大卡（横跨整行） -->
    <Card
      v-if="assetPlan"
      class="relative mb-6 cursor-pointer overflow-hidden p-6 lg:p-0"
      @click="goDetail(assetPlan.id)"
    >
      <!-- 全卡片统一装饰背景（大小屏都显示） -->
      <div
        class="pointer-events-none absolute inset-0 bg-cover bg-center opacity-15"
        style="background-image: url('/bg_01.svg')"
        aria-hidden="true"
      />
      <div class="relative z-10 flex flex-col gap-6 lg:flex-row lg:items-stretch">
        <!-- 左侧：信息区 -->
        <div class="flex-1 space-y-4 p-0 lg:p-8 lg:pr-6">
          <!-- 标签 -->
          <span class="inline-flex items-center rounded-full bg-brand/10 px-3 py-1 text-xs font-medium text-brand">
            总资产
          </span>

          <!-- 标题 + 描述 -->
          <div>
            <h1 class="text-3xl font-bold">{{ assetPlan.name }}</h1>
            <p class="mt-1 text-sm text-muted-foreground">时间会给坚持的人更好的答案。</p>
          </div>

          <!-- 线性进度条（只有设了目标的计划才有分母） -->
          <div class="space-y-2">
            <div
              v-if="savingsHasTarget(assetPlan)"
              class="relative h-2.5 w-full overflow-hidden rounded-full bg-muted"
            >
              <span
                class="block h-full rounded-full bg-brand transition-[width] duration-700"
                :style="{ width: `${savingsProgress(assetPlan) * 100}%` }"
              />
            </div>
            <div class="flex items-baseline justify-between">
              <p class="font-mono text-lg font-bold tabular-nums">
                {{ formatCNY(assetPlan.currentAmount) }}
                <span v-if="savingsHasTarget(assetPlan)" class="text-sm font-normal text-muted-foreground">
                  &nbsp;/ {{ formatCNY(assetPlan.targetAmount ?? 0) }}
                </span>
              </p>
              <!-- 有目标看完成度，没目标看这组资产的整体收益率 -->
              <p
                v-if="savingsHasTarget(assetPlan)"
                class="font-mono text-lg font-bold tabular-nums text-brand"
              >
                {{ (savingsProgress(assetPlan) * 100).toFixed(1) }}%
              </p>
              <p
                v-else
                class="font-mono text-lg font-bold tabular-nums"
                :style="{ color: savingsReturnRate(assetPlan) >= 0 ? 'var(--gain)' : 'var(--loss)' }"
                title="这一组资产的整体收益率（口径与基金列表一致）"
              >
                {{ savingsHasReturn(assetPlan) ? formatPct(savingsReturnRate(assetPlan)) : '—' }}
              </p>
            </div>
          </div>

          <!-- 底部小标签 -->
          <div class="flex flex-wrap items-center gap-3 pt-1">
            <span
              v-if="savingsHasTarget(assetPlan)"
              class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground"
            >
              <Calendar class="size-3.5" />
              预计 {{ assetPlan.targetDate.slice(0, 7) }} 完成
            </span>
            <span
              v-else
              class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground"
            >
              <Target class="size-3.5" />
              未设定目标 · 只看整体收益率
            </span>
            <span
              v-if="assetSchedule !== null"
              class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground"
              title="金额进度 − 时间进度：今天的时间表应该攒到多少，对比实际市值攒到多少"
            >
              <TrendingUp class="size-3.5" />
              <template v-if="Math.abs(assetSchedule) < 0.05">与计划同步</template>
              <template v-else>
                比计划{{ assetSchedule > 0 ? '快' : '慢' }} {{ Math.abs(assetSchedule).toFixed(1) }}%
              </template>
            </span>
            <span class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground">
              <Database class="size-3.5" />
              包含 {{ assetPlan.assets.length }} 类资产
            </span>
          </div>
        </div>

        <!-- 右侧：渐变遮罩 + 还差金额（仅大屏） -->
        <div
          class="relative hidden min-h-[280px] w-[40%] lg:flex lg:flex-col lg:justify-end lg:p-8"
        >
          <!-- 渐变遮罩，让右侧背景更柔和、文字更清晰 -->
          <div
            class="absolute inset-0 bg-gradient-to-r from-card/80 via-card/60 to-transparent"
            aria-hidden="true"
          />
          <div class="relative z-10 max-w-[280px]">
            <p class="text-sm text-muted-foreground">
              {{ savingsHasTarget(assetPlan) ? '还差' : '当前市值' }}
            </p>
            <p class="font-mono text-4xl font-bold tabular-nums text-foreground">
              {{
                formatCNY(
                  savingsHasTarget(assetPlan)
                    ? savingsRemaining(assetPlan)
                    : assetPlan.currentAmount,
                )
              }}
            </p>
            <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
              慢慢来，<br />你会到达想去的地方。
            </p>
          </div>
        </div>

        <!-- 右侧：还差金额（移动端 fallback） -->
        <div class="flex lg:hidden min-w-[240px] flex-col items-start gap-1 border-t border-border pt-4">
          <p class="text-sm text-muted-foreground">
            {{ savingsHasTarget(assetPlan) ? '还差' : '当前市值' }}
          </p>
          <p class="font-mono text-4xl font-bold tabular-nums text-foreground">
            {{
              formatCNY(
                savingsHasTarget(assetPlan) ? savingsRemaining(assetPlan) : assetPlan.currentAmount,
              )
            }}
          </p>
          <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
            慢慢来，<br />你会到达想去的地方。
          </p>
        </div>
      </div>
    </Card>

    <!-- 生活保障 / 愿望区域标题 -->
    <div class="mb-4 space-y-1">
      <h2 class="text-xl font-bold">资产分类</h2>
      <p class="text-sm text-muted-foreground">一些具体而美好的目标，让生活更有期待。</p>
    </div>

    <!-- 卡片网格 -->
    <div class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
      <!-- 生活保障 / 愿望卡片 -->
      <Card
        v-for="p in filteredPlans.filter((x) => x.type !== 'asset')"
        :key="p.id"
        class="group flex h-full cursor-pointer flex-col gap-4 p-5 transition-all hover:-translate-y-0.5 hover:shadow-lg"
        @click="goDetail(p.id)"
      >
        <!-- 头部：标题描述 + 徽章（封面图与占位块已按要求移除） -->
        <div class="flex items-start gap-4">
          <div class="flex min-w-0 flex-1 flex-col gap-2">
            <div class="flex items-start justify-between gap-2">
              <h3 class="truncate text-2xl font-bold">{{ p.name }}</h3>
              <span
                class="inline-flex shrink-0 items-center rounded-full bg-brand/10 px-3 py-1.5 text-sm font-medium text-brand"
              >
                {{ TYPE_LABELS[p.type] }}
              </span>
            </div>
            <p class="text-sm text-muted-foreground">{{ p.description }}</p>
          </div>
        </div>

        <!-- 金额 + 进度百分比 + 进度条 + 底部标签（整体推到底） -->
        <div class="mt-auto flex flex-col gap-4">
          <div class="flex items-end justify-between gap-4">
            <p class="font-mono text-2xl font-bold leading-none tracking-tight tabular-nums">
              {{ formatCNY(p.currentAmount) }}
              <span v-if="savingsHasTarget(p)" class="text-lg font-medium text-muted-foreground">
                / {{ formatCNY(p.targetAmount ?? 0) }}
              </span>
            </p>
            <!-- 有目标看完成度（大数字）；没目标的收益率改到下方以 span 小标签呈现 -->
            <p
              v-if="savingsHasTarget(p)"
              class="shrink-0 font-mono text-2xl font-bold leading-none tabular-nums text-brand"
            >
              {{ (savingsProgress(p) * 100).toFixed(0) }}%
            </p>
          </div>

          <!-- 进度条（没目标就没有分母，不画） -->
          <div
            v-if="savingsHasTarget(p)"
            class="relative h-3 w-full overflow-hidden rounded-full bg-muted"
          >
            <span
              class="block h-full rounded-full bg-brand transition-[width] duration-500"
              :style="{ width: `${savingsProgress(p) * 100}%` }"
            />
          </div>

          <!-- 底部小标签：无目标时的总体收益率 + 资产项数量（均为 span 圆角小标签） -->
          <div class="flex flex-wrap items-center gap-3">
            <span
              v-if="!savingsHasTarget(p)"
              class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground"
            >
              <TrendingUp class="size-3.5" />
              总体收益率
              <span
                class="font-mono font-semibold tabular-nums"
                :style="{ color: savingsReturnRate(p) >= 0 ? 'var(--gain)' : 'var(--loss)' }"
              >
                {{ savingsHasReturn(p) ? formatPct(savingsReturnRate(p)) : '—' }}
              </span>
            </span>
            <span class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground">
              <Database class="size-3.5" />
              包含 {{ p.assets.length }} 类资产
            </span>
          </div>
        </div>
      </Card>

      <!-- 新建计划占位卡：非「总资产」tab 常显；「总资产」tab 在没有总资产计划时也要能从这里建
           （否则那个 tab 在 0 计划时整页空白） -->
      <Card
        v-if="activeTab !== 'asset' || !assetPlan"
        class="flex cursor-pointer flex-col items-center justify-center gap-3 border-dashed p-6 text-center transition-colors hover:border-brand/40 hover:bg-brand/5"
        @click="goNew"
      >
        <div class="flex size-12 items-center justify-center rounded-full bg-muted">
          <Plus class="size-6 text-muted-foreground" />
        </div>
        <div class="space-y-1">
          <h3 class="text-base font-bold">新建存钱计划</h3>
          <p class="text-xs text-muted-foreground">设定一个目标</p>
          <p class="text-xs text-muted-foreground">让存钱更有动力</p>
        </div>
      </Card>
    </div>

    <PageFooter />
  </div>
</template>
