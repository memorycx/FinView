<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft } from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'
import PerformanceChart from '@/components/PerformanceChart.vue'
import PlanPanel from '@/components/PlanPanel.vue'
import PlanTimeline from '@/components/PlanTimeline.vue'
import PageFooter from '@/components/PageFooter.vue'
import Button from '@/components/ui/Button.vue'
import { getFunds, getPortfolioSeries } from '@/api'
import { useRequest } from '@/composables/useApi'

const route = useRoute()
const router = useRouter()

const { data: funds, reload: reloadFunds } = useRequest(() => getFunds({ status: 'all' }))
const { data: portfolio, reload: reloadPortfolio } = useRequest(() => getPortfolioSeries())

// 支持从「我的资产」排行榜跳转携带 ?fund=xxx&tab=active|archived 预选基金
const selectedId = ref<string | null>(
  typeof route.query.fund === 'string' ? route.query.fund : null,
)
const tab = ref<'active' | 'archived'>(
  route.query.tab === 'archived' ? 'archived' : 'active',
)
const view = ref<'list' | 'detail'>('list')

onMounted(() => {
  if (route.query.fund) router.replace({ path: '/' })
})

const selectedFund = computed(
  () => funds.value?.find((f) => f.id === selectedId.value) ?? null,
)

const chartSeries = computed(() =>
  selectedFund.value ? selectedFund.value.series : portfolio.value?.series ?? [],
)
/** 年化收益率（XIRR）：选中基金时用它自己的，组合总览时用组合的；null 由组件显示「—」 */
const chartAnnualizedRate = computed(() =>
  selectedFund.value ? selectedFund.value.annualizedRate : portfolio.value?.annualizedRate ?? null,
)
/** 折线图页脚「最后更新」：选中基金时用它自己 asset 行的时间，组合总览用后端给的全部资产 MAX */
const chartLastUpdate = computed(() =>
  selectedFund.value ? selectedFund.value.lastUpdateTime : portfolio.value?.lastUpdateTime ?? null,
)
const chartTitle = computed(() =>
  selectedFund.value ? selectedFund.value.name : '投资组合总览',
)
const chartSubtitle = computed(() =>
  selectedFund.value
    ? `${selectedFund.value.code} · 本金与市值走势`
    : '全部持有中资产 · 本金与市值走势',
)

/** 组合起始时间 = 持有中（未归档）基金里最早的定投日期 */
const portfolioStart = computed(() =>
  [...(funds.value ?? [])]
    .filter((f) => !f.archived)
    .map((f) => f.startDate)
    .sort()[0],
)
const chartStartDate = computed(() =>
  selectedFund.value ? selectedFund.value.startDate : portfolioStart.value,
)

/** 时间轴未选中具体计划时，按当前 tab 汇总该范围内全部计划 */
const timelineFunds = computed(() =>
  (funds.value ?? []).filter((f) => (tab.value === 'active' ? !f.archived : f.archived)),
)

function handleSelect(id: string) {
  selectedId.value = selectedId.value === id ? null : id
  view.value = 'list'
}

function handleTabChange(t: 'active' | 'archived') {
  tab.value = t
  selectedId.value = null
  view.value = 'list'
}

/** 序列在详情页重新生成后：组合走势汇总的就是各 code 的 asset_series，两份数据都要重拉 */
function handleGenerated() {
  reloadFunds()
  reloadPortfolio()
}
</script>

<template>
  <!-- 桌面端锁定一屏（h-screen + overflow-hidden），卡片内部滚动；视口高度不足时整页滚动 -->
  <div class="dashboard-scroll flex w-full flex-col px-4 py-4 sm:px-6 sm:py-6 lg:px-6 lg:py-4">
    <!-- 返回入口：块常驻占位，未选中基金时仅 invisible 隐藏（visibility 保留高度，避免图表区上下跳动） -->
    <div class="mb-4 shrink-0 lg:mb-3">
      <Button
        variant="ghost"
        size="sm"
        :class="{ invisible: !selectedFund }"
        :aria-hidden="!selectedFund"
        :tabindex="selectedFund ? 0 : -1"
        @click="selectedId = null"
      >
        <ArrowLeft class="size-4" />
        返回组合总览
      </Button>
    </div>

    <!-- 主区域：桌面端占满剩余高度，内部卡片各自滚动 -->
    <div class="ds-flex lg:min-h-0 lg:flex-1">
      <!-- xl 以上双列：左绩效图表（视觉核心）/ 右计划列表+时间轴；中等屏单列堆叠 -->
      <div class="ds-flex grid gap-4 lg:h-full lg:gap-5 xl:grid-cols-[1.7fr_1fr]">
        <!--
          始终渲染图表卡：没有数据（新用户 / 加载中）时也保持基本框架，
          数值位显示「—」，左右两张卡片的高度才不会一边空一块。
        -->
        <PerformanceChart
          :series="chartSeries"
          :title="chartTitle"
          :subtitle="chartSubtitle"
          :start-date="chartStartDate"
          :annualized-rate="chartAnnualizedRate"
          :last-update-time="chartLastUpdate"
          @generated="handleGenerated"
        />
        <!-- 右列：顶部加不可见占位标题块，与左侧 PerformanceChart 卡片顶部对齐 -->
        <div class="ds-flex flex h-full min-h-0 flex-col">
          <div
            class="mb-5 shrink-0 invisible hidden lg:block"
            aria-hidden="true"
          >
            <h1 class="text-3xl font-bold tracking-tight text-foreground">&nbsp;</h1>
            <p class="mt-1.5 text-xs text-muted-foreground/80">&nbsp;</p>
          </div>
          <div class="ds-flex flex flex-col gap-4 lg:min-h-0 lg:gap-5 lg:flex-1">
            <PlanPanel
              :funds="funds ?? []"
              :tab="tab"
              :selected-id="selectedId"
              :view="view"
              @tab-change="handleTabChange"
              @select="handleSelect"
              @view-change="view = $event"
              @generated="handleGenerated"
            />
            <!-- <PlanTimeline class="lg:shrink-0" :fund="selectedFund" :funds="timelineFunds" /> -->
          </div>
        </div>
      </div>
    </div>

    <PageFooter />
  </div>
</template>
