<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Component } from 'vue'
import { BarChart3, PieChart, ShieldCheck } from '@lucide/vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart as EChartsPieChart } from 'echarts/charts'
import { TooltipComponent, TitleComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import Card from '@/components/ui/card/Card.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import { isDark } from '@/composables/useTheme'
import { useRequest } from '@/composables/useApi'
import { getAssetSummary, getFunds } from '@/api'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY, formatWan } from '@/lib/finance'
import type { AllocationBucket } from '@/api/types'

use([CanvasRenderer, EChartsPieChart, TooltipComponent, TitleComponent])

/**
 * 资产配置结构。信息层级：头部（标题 + 三组 KPI）→ 主体（环形图 + 资产构成列表）→ 底部一句名言。
 *
 * 几条布局约定：
 * - 三组 KPI（资产集中度 / 安全资金 / 最大资产）的排版参考投资看板的折线图卡片：
 *   标签 / 大数字 / 小注，三栏竖线分隔、两端对齐；顺序按「项数多的在左」排（见 insights）。
 * - 卡片在 lg 起跟右侧排行榜**等高**（grid 拉伸），多余高度由 mt-auto 收在名言上方。
 * - 环形图只占主体左侧、尺寸克制；分类列表是「名称 / 占比 / 金额」三列对齐的紧凑列表，
 *   金额比占比重。点有基金的分类会筛选右侧排行榜（只给轻微的可交互暗示，不做按钮）。
 */
const props = defineProps<{
  /** 当前选中的分类（父级持有；再点一次同一个即取消筛选） */
  activeBucket?: AllocationBucket | null
}>()

const emit = defineEmits<{
  /** null = 取消筛选 */
  select: [bucket: AllocationBucket | null]
}>()

const { data: summary } = useRequest(() => getAssetSummary())
// status=active 在后端就是「未归档」：列表与总览用同一批资产
const { data: currentFunds } = useRequest(() => getFunds({ status: 'active' }))

/** 按展示桶的市值配置（恒 5 项，基金已按股基/债基细分） */
const allocation = computed(() => summary.value?.allocation ?? [])
/** 总资产（元） */
const totalAssets = computed(() => summary.value?.totalAssets ?? 0)

// 五个展示桶（基金按 asset.asset_type 细分成股基/债基，key 见 AllocationBucket）；
// 颜色变量在 style.css，浅色/深色两套都有
const palette: Record<AllocationBucket, string> = {
  equityFund: '--chart-1',
  bondFund: '--chart-2',
  stock: '--chart-3',
  bond: '--chart-4',
  cash: '--chart-5',
}

const themeTick = ref(0)
watch(isDark, async () => {
  await nextTick()
  themeTick.value++
})

/** 列表行：短名 + 颜色变量 + 占比/金额（金额比占比重，两列右对齐） */
const data = computed(() =>
  allocation.value.map((a) => ({
    ...a,
    // 列表用短名（股基/债基），后端 label 保持全称不动
    label: a.category === 'equityFund' ? '股基' : a.category === 'bondFund' ? '债基' : a.label,
    colorVar: palette[a.category],
    clickable: hasFunds(a.category),
  })),
)

/** 该分类里有没有能上排行榜的基金：只有股基/债基有，股票/债券/现金不做交互暗示 */
function hasFunds(bucket: AllocationBucket): boolean {
  return (currentFunds.value ?? []).some((f) =>
    bucket === 'bondFund' ? f.assetType === 'bond' : bucket === 'equityFund' ? f.assetType !== 'bond' : false,
  )
}

function pick(bucket: AllocationBucket) {
  if (!hasFunds(bucket)) return
  emit('select', props.activeBucket === bucket ? null : bucket)
}

const option = computed<EChartsOption>(() => {
  void themeTick.value

  return {
    title: {
      text: formatWan(totalAssets.value),
      subtext: '总资产',
      left: 'center',
      top: 'center',
      itemGap: 4,
      textStyle: {
        fontSize: 28,
        fontWeight: 700,
        fontFamily: MONO_FONT,
        color: themeColor('--foreground'),
      },
      subtextStyle: {
        fontSize: 12,
        color: themeColor('--muted-foreground'),
      },
    },
    tooltip: {
      trigger: 'item',
      backgroundColor: themeColor('--popover'),
      borderColor: themeColor('--border', 0.5),
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: themeColor('--foreground'), fontSize: 13, fontFamily: SANS_FONT },
      extraCssText: 'border-radius:10px;box-shadow:0 10px 24px rgba(0,0,0,0.12);',
      formatter: (params: unknown) => {
        const p = params as { name: string; value: number }
        return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;min-width:120px">
          <span style="color:${themeColor('--muted-foreground')}">${p.name}</span>
          <span style="font-family:${MONO_FONT};font-weight:500;font-variant-numeric:tabular-nums">${formatCNY(p.value)}</span>
        </div>`
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['66%', '88%'],
        padAngle: 2,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: 'transparent', borderWidth: 0 },
        emphasis: { scale: true, scaleSize: 4 },
        data: data.value.map((a) => ({
          name: a.label,
          value: a.value,
          itemStyle: { color: themeColor(a.colorVar) },
        })),
      },
    ],
  }
})

function pctOf(value: number) {
  return totalAssets.value === 0 ? '0.0' : ((value / totalAssets.value) * 100).toFixed(1)
}

/** 总资产与分类金额：¥ 整数（万级数字再加两位小数反而糊），中心圈仍用「万」 */
function cny(n: number) {
  return '¥' + Math.round(n).toLocaleString('zh-CN')
}

/** 带名字的持仓项：三个 Insight 都要说清"这个数由谁构成" */
type HoldingItem = { name: string; value: number }

// 持仓集中度：按个体持仓（含现金）降序排列。
// 非正值要先剔掉：现金是「手工记录 − 定投/买入」，还没记现金时可能是负数，
// 负值在占比里会平白拉低 top3
const holdings = computed<HoldingItem[]>(() => {
  const cash = allocation.value.find((a) => a.category === 'cash')?.value ?? 0
  return [
    ...(currentFunds.value ?? []).map((f) => ({ name: f.name, value: f.current })),
    { name: '现金', value: cash },
  ]
    .filter((item) => item.value > 0)
    .sort((a, b) => b.value - a.value)
})

/** 最大单一资产占比 */
const maxShare = computed(() =>
  totalAssets.value === 0
    ? '0.0'
    : (((holdings.value[0]?.value ?? 0) / totalAssets.value) * 100).toFixed(1),
)

/** 前 3 大资产占比（资产集中度） */
const top3Share = computed(() =>
  totalAssets.value === 0
    ? '0.0'
    : ((holdings.value.slice(0, 3).reduce((s, h) => s + h.value, 0) / totalAssets.value) * 100).toFixed(1),
)

/**
 * 安全资金 = asset_type 为 bond（债基）/ cash（现金）的市值合计，由后端算好（跟 totalAssets 同一份
 * 未归档口径）。不按 category 猜：基金在库里都是 category='fund'，股基/债基看 asset.asset_type，
 * 环形图里「债券基金」那一块与它同源（AssetService.bucketOf）。
 */
const safeFunds = computed(() => summary.value?.safeAssets ?? 0)

/** 安全资金包含的资产项：债基 + 现金（现金不在 /funds 里，从 allocation 的 cash 桶取） */
const safeHoldings = computed<HoldingItem[]>(() => {
  const bonds = (currentFunds.value ?? [])
    .filter((f) => f.assetType === 'bond')
    .map((f) => ({ name: f.name, value: f.current }))
  const cash = allocation.value.find((a) => a.category === 'cash')?.value ?? 0
  return [...bonds, { name: '现金', value: cash }]
    .filter((item) => item.value !== 0)
    .sort((a, b) => b.value - a.value)
})

/** 安全资金占比（%，一位小数，与另外两个同款；总资产为 0 时记 0.0） */
const safeShare = computed(() =>
  totalAssets.value === 0 ? '0.0' : ((safeFunds.value / totalAssets.value) * 100).toFixed(1),
)

/** 一个 Insight：一句说明 + 数字 + 短说明（辅助信息，不做成重卡片；完整构成放 title） */
type Insight = {
  key: 'top3' | 'safe' | 'max'
  label: string
  /** hover 提示：口径 + 完整构成名单 */
  hint: string
  icon: Component
  share: string
  caption: string
  /** 项数，用来决定三个 Insight 的左右顺序 */
  count: number
}

/**
 * 三个 Insight，**项数多的排左边、少的排右边**（用户要求；项数一样时保持基准顺序）。
 * Array.prototype.sort 是稳定的，所以同数量不会来回跳。
 * 说明文字要短（"前 3 项资产 / 现金 + 1 只债基"），完整名单只放在 hover 里 —— 这块是辅助信息。
 */
const insights = computed<Insight[]>(() => {
  const names = (items: HoldingItem[]) => items.map((i) => i.name).join(' · ')
  const top3 = holdings.value.slice(0, 3)
  const max = holdings.value.slice(0, 1)
  const cashOnly = safeHoldings.value.filter((i) => i.name === '现金').length > 0
  const bondCount = safeHoldings.value.length - (cashOnly ? 1 : 0)
  const safeCaption =
    [cashOnly ? '现金' : null, bondCount ? `${bondCount} 只债基` : null].filter(Boolean).join(' + ') || '暂无'

  const build = (
    key: Insight['key'],
    label: string,
    define: string,
    icon: Component,
    share: string,
    caption: string,
    items: HoldingItem[],
  ): Insight => ({
    key,
    label,
    icon,
    share,
    caption,
    count: items.length,
    hint: `${define}：${items.length ? names(items) : '暂无'}`,
  })

  return [
    build('top3', '资产集中度', '市值最大的几项合计占总资产的比例', BarChart3,
      top3Share.value, top3.length ? `前 ${top3.length} 项资产` : '暂无', top3),
    build('safe', '安全资金', '债券基金 + 现金，占总资产的比例', ShieldCheck,
      safeShare.value, safeCaption, safeHoldings.value),
    build('max', '最大资产', '市值最大的单项占总资产的比例', PieChart,
      maxShare.value, max.length ? max[0].name : '暂无', max),
  ].sort((a, b) => b.count - a.count)
})
</script>

<template>
  <Card class="lg:h-full">
    <!-- lg 起卡片高度由页面锁死（拉到页面底部）；内容装不下时在这块里滚，不撑高卡片、也不被裁掉。
         子项都 shrink-0：flex 列里默认允许收缩，一收缩环形图会被压扁，所以只让主体区「长」不让它「缩」 -->
    <CardContent class="flex flex-1 flex-col lg:min-h-0 lg:overflow-y-auto">
      <!-- 头部：标题 + 三组 KPI。KPI 参考投资看板的折线图卡片 —— 标签 / 大数字 / 小注，
           三栏用竖线分隔、两端对齐（首尾不吃左右内边距，和卡片内容边缘对齐）。
           lg（卡片 400~500px）时三栏还可以并排，只是数字降一档，免得撑破栏宽 -->
      <header class="shrink-0">
        <div class="space-y-1">
          <h2 class="text-xl font-semibold tracking-tight text-foreground">资产配置结构</h2>
          <p class="text-xs text-muted-foreground">每一块钱分布在哪里，以及是否过于集中</p>
        </div>

        <div
          class="mt-4 grid grid-cols-1 gap-4 pt-2 sm:flex sm:flex-row sm:items-start sm:justify-between sm:divide-x sm:divide-border"
        >
          <div
            v-for="g in insights"
            :key="g.key"
            class="min-w-0 space-y-1 sm:flex-1 sm:px-3 first:sm:pl-0 last:sm:pr-0 xl:px-6"
            :title="g.hint"
          >
            <p class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <!-- lg 那一段一格只有 ~100px，图标先让位，免得「资产集中度」折成两行 -->
              <component :is="g.icon" class="hidden size-3.5 xl:block" aria-hidden="true" />
              {{ g.label }}
            </p>
            <!-- 数字按「卡片实际宽度」分档，不是按视口：lg 时页面是两栏、卡片只有 400px，
                 一格 ~110px 放不下 45px 的「48.5%」，会折行，所以那一段反而要调小 -->
            <p
              class="font-mono text-2xl font-bold leading-tight tracking-tight tabular-nums text-foreground lg:text-xl xl:text-4xl"
            >
              {{ g.share }}%
            </p>
            <p class="truncate text-xs text-muted-foreground">{{ g.caption }}</p>
          </div>
        </div>
      </header>

      <!-- 主体：环形图（左） + 资产构成列表（右）。
           更高的大屏（≥1180px 视口高）由 style.css 的 .main-allocation 分档加大顶部 padding，
           主体整体下移、视觉重心向卡片中间靠；矮窗口余白不足时规则不生效，不撑滚动。
           底部只留 36px：KPI 移到卡片头部后，下方只剩名言（mt-auto 收余白），
           原来与底部指标块对称的 71px 没有对称对象了，留着会在 1200 高上下就撑出内部滚动。
           并排要 xl（卡片 ≥500px）才放得下 —— lg 以下卡片只有 400px 上下，挤并排名字会被裁没 -->
      <section
        class="main-allocation flex shrink-0 flex-col items-center gap-6 pt-14 pb-6 xl:flex-row xl:items-start xl:gap-10 xl:pt-[92px] xl:pb-[36px]"
      >
        <!-- 环形图随断点放大，大屏靠图表本身吃掉空间而不是居中留白 -->
        <div
          class="aspect-square w-full max-w-[200px] shrink-0 sm:max-w-[240px] xl:max-w-[340px] 2xl:max-w-[380px]"
        >
          <VChart
            class="h-full w-full"
            :option="option"
            autoresize
            :update-options="{ notMerge: true }"
          />
        </div>

        <!-- xl 起列表 flex-1 撑满右侧剩余宽度（环形图靠左、两端分布），不再水平居中留两侧空边 -->
        <div class="w-full min-w-0 max-w-[420px] xl:max-w-none xl:flex-1">
          <!-- 总资产：列表的锚点 -->
          <div class="flex items-baseline justify-between border-b border-border/70 pb-2">
            <span class="text-xs text-muted-foreground xl:text-sm">总资产</span>
            <span class="font-mono text-lg font-semibold tabular-nums text-foreground xl:text-xl">
              {{ cny(totalAssets) }}
            </span>
          </div>

          <!-- 资产构成：名称 / 占比 / 金额；有基金的分类可点，轻微的可交互暗示 -->
          <ul class="divide-y divide-border/50">
            <!-- hover / 选中的背景填充整行 li（而非内部 button），button 只承载内容与点击 -->
            <li
              v-for="a in data"
              :key="a.category"
              class="transition-colors"
              :class="[
                a.clickable ? 'cursor-pointer hover:bg-muted/60' : 'cursor-default',
                activeBucket === a.category ? 'bg-muted/70' : '',
              ]"
            >
              <button
                type="button"
                class="flex w-full items-center gap-2.5 px-2 py-3 text-left xl:py-4"
                :disabled="!a.clickable"
                :title="a.clickable ? `只看${a.label}的基金` : undefined"
                @click="pick(a.category)"
              >
                <span
                  class="size-2.5 shrink-0 rounded-full xl:size-3"
                  :style="{ backgroundColor: `var(${a.colorVar})` }"
                  aria-hidden
                />
                <span
                  class="truncate text-sm font-medium xl:text-[15px]"
                  :class="activeBucket === a.category ? 'text-brand' : 'text-foreground'"
                >
                  {{ a.label }}
                </span>
                <span
                  class="ml-auto shrink-0 font-mono text-xs tabular-nums text-muted-foreground xl:text-sm"
                >
                  {{ pctOf(a.value) }}%
                </span>
                <span
                  class="w-20 shrink-0 text-right font-mono text-sm font-semibold tabular-nums text-foreground xl:w-24 xl:text-base"
                >
                  {{ cny(a.value) }}
                </span>
              </button>
            </li>
          </ul>
        </div>
      </section>

      <!-- 卡片底部：沉底的一句名言。正文左对齐、署名右下角，左右呼应。
           字号 / 行高 / 署名间距都在 style.css 的 .asset-quote 里按视口高度调（大屏留白多就放大）。
           pt 只留 4px：上方 section 的 pb 已按 92px 对称留白，这里再空 40px 会撑出内部滚动。
           mt-auto 把锁一屏多出来的余白全吃在它上方，名言本身贴着卡片底沿 -->
      <figure class="asset-quote mt-auto w-full shrink-0 pt-1">
        <blockquote class="text-muted-foreground/80">
          我们一步一步走下去，踏踏实实地去走，永不抗拒生命交给我们的重负，才是一个勇者。
        </blockquote>
        <figcaption class="text-right text-muted-foreground/60">—— 三毛</figcaption>
      </figure>
    </CardContent>
  </Card>
</template>
