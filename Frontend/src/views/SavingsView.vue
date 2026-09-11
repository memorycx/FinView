<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  BarChart3,
  Calendar,
  ChevronRight,
  Clock,
  Database,
  Heart,
  Plus,
  Target,
  TrendingUp,
} from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import {
  formatCNY,
  savingsPlans,
  savingsProgress,
  savingsRemaining,
  type SavingsPlan,
  type SavingsPlanType,
} from '@/lib/data'

const router = useRouter()

type TabKey = 'all' | SavingsPlanType
const activeTab = ref<TabKey>('all')

const tabs = computed<{ key: TabKey; label: string; count: number }[]>(() => {
  const total = savingsPlans.length
  const asset = savingsPlans.filter((p) => p.type === 'asset').length
  const wish = savingsPlans.filter((p) => p.type === 'wish').length
  return [
    { key: 'all', label: '全部', count: total },
    { key: 'asset', label: '总资产', count: asset },
    { key: 'wish', label: '小愿望', count: wish },
  ]
})

const filteredPlans = computed(() => {
  if (activeTab.value === 'all') return savingsPlans
  return savingsPlans.filter((p) => p.type === activeTab.value)
})

/** KPI 统计 */
const kpi = computed(() => {
  const totalPlans = savingsPlans.length
  const totalSaved = savingsPlans.reduce((s, p) => s + p.currentAmount, 0)
  const totalTarget = savingsPlans.reduce((s, p) => s + p.targetAmount, 0)
  const totalRemaining = Math.max(0, totalTarget - totalSaved)
  return { totalPlans, totalSaved, totalTarget, totalRemaining }
})

/** 总资产计划 */
const assetPlan = computed(() => savingsPlans.find((p) => p.type === 'asset'))

function goDetail(id: string) {
  router.push({ name: 'savings-detail', params: { id } })
}

/** 目标日期 → "2026 年 12 月" */
function formatTargetMonth(d: string) {
  const [y, m] = d.split('-')
  return `${y} 年 ${Number(m)} 月`
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1400px] px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
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
      <Button class="gap-2" @click="goDetail('new')">
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
          <p class="text-sm text-muted-foreground">累计存入</p>
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

          <!-- 线性进度条 -->
          <div class="space-y-2">
            <div class="relative h-2.5 w-full overflow-hidden rounded-full bg-muted">
              <span
                class="block h-full rounded-full bg-brand transition-[width] duration-700"
                :style="{ width: `${savingsProgress(assetPlan) * 100}%` }"
              />
            </div>
            <div class="flex items-baseline justify-between">
              <p class="font-mono text-lg font-bold tabular-nums">
                {{ formatCNY(assetPlan.currentAmount) }}
                <span class="text-sm font-normal text-muted-foreground">
                  &nbsp;/ {{ formatCNY(assetPlan.targetAmount) }}
                </span>
              </p>
              <p class="font-mono text-lg font-bold tabular-nums text-brand">
                {{ (savingsProgress(assetPlan) * 100).toFixed(1) }}%
              </p>
            </div>
          </div>

          <!-- 底部小标签 -->
          <div class="flex flex-wrap items-center gap-3 pt-1">
            <span class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground">
              <Calendar class="size-3.5" />
              预计 {{ assetPlan.targetDate.slice(0, 7) }} 完成
            </span>
            <span class="inline-flex items-center gap-1.5 rounded-lg bg-muted/60 px-3 py-1.5 text-xs text-muted-foreground">
              <TrendingUp class="size-3.5" />
              比计划快 8.2%
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
            <p class="text-sm text-muted-foreground">还差</p>
            <p class="font-mono text-4xl font-bold tabular-nums text-foreground">
              {{ formatCNY(savingsRemaining(assetPlan)) }}
            </p>
            <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
              慢慢来，<br />你会到达想去的地方。
            </p>
          </div>
        </div>

        <!-- 右侧：还差金额（移动端 fallback） -->
        <div class="flex lg:hidden min-w-[240px] flex-col items-start gap-1 border-t border-border pt-4">
          <p class="text-sm text-muted-foreground">还差</p>
          <p class="font-mono text-4xl font-bold tabular-nums text-foreground">
            {{ formatCNY(savingsRemaining(assetPlan)) }}
          </p>
          <p class="mt-3 text-xs leading-relaxed text-muted-foreground">
            慢慢来，<br />你会到达想去的地方。
          </p>
        </div>
      </div>
    </Card>

    <!-- 小愿望区域标题 -->
    <div class="mb-4 space-y-1">
      <h2 class="text-xl font-bold">我的小愿望</h2>
      <p class="text-sm text-muted-foreground">一些具体而美好的目标，让生活更有期待。</p>
    </div>

    <!-- 卡片网格 -->
    <div class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
      <!-- 小愿望卡片 -->
      <Card
        v-for="p in filteredPlans.filter((x) => x.type === 'wish')"
        :key="p.id"
        class="group flex h-full cursor-pointer flex-col gap-4 p-5 transition-all hover:-translate-y-0.5 hover:shadow-lg"
        @click="goDetail(p.id)"
      >
        <!-- 头部：封面图 + 标题描述 + 徽章 -->
        <div class="flex items-start gap-4">
          <img
            :src="p.image"
            :alt="p.name"
            class="size-[88px] shrink-0 rounded-2xl object-cover"
            loading="lazy"
          />
          <div class="flex min-w-0 flex-1 flex-col gap-2">
            <div class="flex items-start justify-between gap-2">
              <h3 class="truncate text-2xl font-bold">{{ p.name }}</h3>
              <span
                class="inline-flex shrink-0 items-center gap-1 rounded-full bg-brand/10 px-3 py-1.5 text-sm font-medium text-brand"
              >
                <Heart class="size-4 fill-current" />
                小愿望
              </span>
            </div>
            <p class="text-sm text-muted-foreground">{{ p.description }}</p>
          </div>
        </div>

        <!-- 金额 + 进度百分比 + 进度条 + 底部栏（整体推到底） -->
        <div class="mt-auto flex flex-col gap-4">
          <div class="flex items-end justify-between gap-4">
            <p class="font-mono text-2xl font-bold leading-none tracking-tight tabular-nums">
              {{ formatCNY(p.currentAmount) }}
              <span class="text-lg font-medium text-muted-foreground">
                / {{ formatCNY(p.targetAmount) }}
              </span>
            </p>
            <p
              class="shrink-0 font-mono text-2xl font-bold leading-none tabular-nums text-brand"
            >
              {{ (savingsProgress(p) * 100).toFixed(0) }}%
            </p>
          </div>

          <!-- 进度条 -->
          <div class="relative h-3 w-full overflow-hidden rounded-full bg-muted">
            <span
              class="block h-full rounded-full bg-brand transition-[width] duration-500"
              :style="{ width: `${savingsProgress(p) * 100}%` }"
            />
          </div>

          <!-- 底部：预计完成 + 箭头 -->
          <div
            class="flex items-center justify-between rounded-xl bg-muted/40 px-4 py-3 transition-colors group-hover:bg-muted/60"
          >
            <div class="flex items-center gap-2.5">
              <Calendar class="size-5 text-brand" />
              <p class="text-base font-semibold">预计 {{ p.targetDate.slice(0, 7) }} 完成</p>
            </div>
            <ChevronRight class="size-5 text-muted-foreground" />
          </div>
        </div>
      </Card>

      <!-- 新建计划占位卡 -->
      <Card
        v-if="activeTab === 'wish' || activeTab === 'all'"
        class="flex cursor-pointer flex-col items-center justify-center gap-3 border-dashed p-6 text-center transition-colors hover:border-brand/40 hover:bg-brand/5"
        @click="goDetail('new')"
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
  </div>
</template>
