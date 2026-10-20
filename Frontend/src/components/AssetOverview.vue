<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart as EChartsBarChart, PieChart as EChartsPieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, TitleComponent } from 'echarts/components'
import type { EChartsOption } from 'echarts'
import Card from '@/components/ui/card/Card.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import { isDark } from '@/composables/useTheme'
import { useRequest } from '@/composables/useApi'
import { getAssetSummary, getFunds } from '@/api'
import { MONO_FONT, SANS_FONT, themeColor } from '@/lib/chart-theme'
import { formatCNY, formatWan } from '@/lib/finance'
import type { AllocationBucket } from '@/api/types'

use([CanvasRenderer, EChartsBarChart, EChartsPieChart, GridComponent, TooltipComponent, TitleComponent])

/**
 * 我的资产 · 左卡。自上而下三段，段与段之间是拉到卡片左右沿的横向分割线：
 * ① 三组 KPI 横排（全宽三等分）→ ② 构成柱状图 + 总资产 → ③ 资产配置（环形图 + 分类列表）。
 *
 * 几条布局约定：
 * - 卡片无标题，三组 KPI（资产集中度 / 安全资金 / 最大资产）是卡片头部、**始终横排**：
 *   竖线分隔、三等分、首尾贴卡片内容边缘。排版对齐投资看板折线图卡片：标签 / 大数字 / 小注；
 *   顺序按「项数多的在左」排（见 insights）。
 *   三组 KPI 同时是「构成柱状图」的**切换开关**（详见下方 selectedInsightKey）：选中的一组
 *   加深下划线，第二段的柱状图跟着换成同一批资产（口径同源，柱子的和 = 该组的数字）。
 * - 第二段：左边是选中 KPI 的构成柱状图（无标题，看哪组由上方选中的 KPI 决定），右边是
 *   **总金额**指标块（竖线分隔，排版同 KPI：标签 / 大数字）——金额是当前选中模块的合计
 *   （与柱状图同源，切 KPI 跟着变），不是全部总资产。该块原先挂在第三段列表的头部，
 *   挪出来后列表只剩分类行，环形图的可用高度也跟着变。
 * - 第三段「资产配置」带段标题，内容是**环形图（左）+ 分类列表（右）**；分类列表是
 *   「名称 / 占比 / 金额」三列对齐的紧凑列表，金额比占比重。
 *   点有基金的分类会筛选右侧排行榜（只给轻微的可交互暗示，不做按钮）。
 * - 卡片在 lg 起跟右侧排行榜**等高**（grid 拉伸）。大屏多出的高度先靠环形图按视口高度放大吃掉
 *   （.donut-wrap 分档，见 style.css），剩下的归 flex-1 的第三段。
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
  moneyFund: '--chart-6',
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
    // 列表用短名（股基/债基/货基），后端 label 保持全称不动
    label:
      a.category === 'equityFund'
        ? '股基'
        : a.category === 'bondFund'
          ? '债基'
          : a.category === 'moneyFund'
            ? '货基'
            : a.label,
    colorVar: palette[a.category],
    clickable: hasFunds(a.category),
  })),
)

/**
 * 该分类里有没有能上排行榜的基金：只有基金桶有，股票/债券/现金不做交互暗示。
 * equityFund 连「没标过」（null）一起收 —— 与后端 bucketOf 的兜底一致（未标注按股基算）。
 */
function hasFunds(bucket: AllocationBucket): boolean {
  return (currentFunds.value ?? []).some((f) => {
    if (bucket === 'bondFund') return f.assetType === 'bond'
    if (bucket === 'moneyFund') return f.assetType === 'money'
    if (bucket === 'equityFund') return !f.assetType || f.assetType === 'equity'
    return false
  })
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
        fontSize: 30,
        fontWeight: 700,
        fontFamily: MONO_FONT,
        color: themeColor('--foreground'),
      },
      subtextStyle: {
        fontSize: 13,
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
 * 安全资金 = asset_type 为 bond（债基）/ money（货币基金）/ cash（现金）的市值合计，由后端算好
 * （跟 totalAssets 同一份未归档口径）。不按 category 猜：基金在库里都是 category='fund'，
 * 细分看 asset.asset_type，环形图里「债券基金 / 货币基金」那两块与它同源（AssetService.bucketOf）。
 */
const safeFunds = computed(() => summary.value?.safeAssets ?? 0)

/** 安全资金包含的资产项：债基 + 货币基金 + 现金（现金不在 /funds 里，从 allocation 的 cash 桶取） */
const safeHoldings = computed<HoldingItem[]>(() => {
  const safeFundRows = (currentFunds.value ?? [])
    .filter((f) => f.assetType === 'bond' || f.assetType === 'money')
    .map((f) => ({ name: f.name, value: f.current }))
  const cash = allocation.value.find((a) => a.category === 'cash')?.value ?? 0
  return [...safeFundRows, { name: '现金', value: cash }]
    .filter((item) => item.value !== 0)
    .sort((a, b) => b.value - a.value)
})

/** 安全资金占比（%，一位小数，与另外两个同款；总资产为 0 时记 0.0） */
const safeShare = computed(() =>
  totalAssets.value === 0 ? '0.0' : ((safeFunds.value / totalAssets.value) * 100).toFixed(1),
)

/** 一个 KPI：一句说明 + 数字 + 短说明；完整构成放 title（hover 查看），构成明细同时给下方柱状图 */
type Insight = {
  key: 'top3' | 'safe' | 'max'
  label: string
  /** hover 提示：口径 + 完整构成名单 */
  hint: string
  share: string
  caption: string
  /** 项数，用来决定三个 KPI 的左右顺序 */
  count: number
  /** 构成明细（含现金），口径与数字同源 —— 柱状图直接画它，不再另算一遍 */
  items: HoldingItem[]
}

/**
 * 三个 KPI，**项数多的排左边、少的排右边**（用户要求；项数一样时保持基准顺序）。
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
    share: string,
    caption: string,
    items: HoldingItem[],
  ): Insight => ({
    key,
    label,
    share,
    caption,
    count: items.length,
    items,
    hint: `${define}：${items.length ? names(items) : '暂无'}`,
  })

  return [
    build('top3', '资产集中度', '市值最大的几项合计占总资产的比例',
      top3Share.value, top3.length ? `前 ${top3.length} 项资产` : '暂无', top3),
    build('safe', '安全资金', '债券基金 + 现金，占总资产的比例',
      safeShare.value, safeCaption, safeHoldings.value),
    build('max', '最大资产', '市值最大的单项占总资产的比例',
      maxShare.value, max.length ? max[0].name : '暂无', max),
  ].sort((a, b) => b.count - a.count)
})

/**
 * 构成柱状图当前看哪一组 KPI：默认资产集中度（top3），点其它两组切换。
 * **按 key 记，不按下标** —— 三个 KPI 会随「项数多的在左」重排，记下标的话
 * 数据一变选中项就跟着跳。选中的 key 不在列表里（例如某组暂无资产）时兜底到第一组。
 */
const selectedInsightKey = ref<Insight['key']>('top3')
const selectedInsight = computed<Insight>(
  () => insights.value.find((i) => i.key === selectedInsightKey.value) ?? insights.value[0],
)

/**
 * 顶部分段控件的滑动底色（写法同投资看板 PerformanceChart 的范围/视图切换）：
 * 底色块的 left/width 用 offsetLeft/offsetWidth 量出来，不靠 CSS 动画猜位置。
 * 两个额外的重算时机（那边没有，这里补上，因为这张卡的宽度会被侧栏拖拽/收起改变）：
 * - insights 重排（项数多的在左）或选中项变化 → watch；
 * - 卡片宽度变化 → ResizeObserver（分段按钮是等分的，容器一变宽就要重量）。
 */
const switchEl = ref<HTMLElement | null>(null)
const switchBtns = ref<(HTMLElement | null)[]>([])
const switchOverlay = ref({ left: 0, width: 0 })
function updateSwitchOverlay() {
  const idx = insights.value.findIndex((i) => i.key === selectedInsightKey.value)
  const btn = switchBtns.value[idx]
  if (btn) switchOverlay.value = { left: btn.offsetLeft, width: btn.offsetWidth }
}
watch([selectedInsightKey, insights], async () => {
  await nextTick()
  updateSwitchOverlay()
})
onMounted(() => {
  updateSwitchOverlay()
  if (switchEl.value) new ResizeObserver(updateSwitchOverlay).observe(switchEl.value)
})

/**
 * 第二段右侧「总金额」：当前选中 KPI 模块的金额合计。**直接对 selectedInsight.items 求和**，
 * 与柱状图柱子（柱子的和）、KPI 占比（合计 / totalAssets）同一数据源，三处永远一致，
 * 不在别处另算一遍。
 */
const selectedAmount = computed(() =>
  (selectedInsight.value?.items ?? []).reduce((sum, item) => sum + item.value, 0),
)

/** 柱子上的数值标签用「万」缩写（同环形图中心圈），零头不入账，完整金额在 tooltip 里 */
function compactCNY(n: number) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : '¥' + Math.round(n).toLocaleString('zh-CN')
}

/** 构成柱状图：竖柱，名称 / 金额取当前选中 KPI 的 items（柱子的和 = 上方那个大数字） */
const barOption = computed<EChartsOption>(() => {
  void themeTick.value

  const items = selectedInsight.value?.items ?? []
  const barColor = themeColor('--chart-1')

  return {
    animationDuration: 400,
    // 柱子往中间收（左右各留 18%）：柱少时不会散在两端；横轴名称跟着柱子对齐
    grid: { left: '18%', right: '18%', top: 24, bottom: 2, containLabel: true },
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
        return `<div style="display:flex;align-items:center;justify-content:space-between;gap:24px;min-width:140px">
          <span style="color:${themeColor('--muted-foreground')}">${p.name}</span>
          <span style="font-family:${MONO_FONT};font-weight:500;font-variant-numeric:tabular-nums">${formatCNY(p.value)}</span>
        </div>`
      },
    },
    xAxis: {
      type: 'category',
      data: items.map((i) => i.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: themeColor('--muted-foreground'),
        fontSize: 12,
        fontFamily: SANS_FONT,
        margin: 10,
        // 单行展示，太长就截断（完整名称在 tooltip 里）
        formatter: (name: string) => (name.length > 6 ? `${name.slice(0, 6)}…` : name),
      },
    },
    // 金额不画在纵轴上（没有轴标），数值用柱顶标签给 —— 省一列数字，柱子能占满整行
    yAxis: { type: 'value', axisLine: { show: false }, axisTick: { show: false }, axisLabel: { show: false }, splitLine: { show: false } },
    series: [
      {
        type: 'bar',
        barMaxWidth: 42,
        data: items.map((i) => i.value),
        itemStyle: { color: barColor, borderRadius: [4, 4, 0, 0] },
        label: {
          show: true,
          position: 'top',
          distance: 6,
          color: themeColor('--foreground'),
          fontSize: 12,
          fontFamily: MONO_FONT,
          formatter: (params: unknown) => compactCNY((params as { value: number }).value),
        },
      },
    ],
  }
})
</script>

<template>
  <Card class="min-h-0 lg:h-full">
    <!-- 卡片高度：lg 起跟着网格行（= 视口剩余高度）拉伸 —— 网格行比内容高就长高、矮就变矮。
         内容按「大屏一屏放下」配比（2026-10-05 起：切换条 52 + 柱状图段 290 封顶 +
         资产配置段 ~330 + 卡片皮；1080 下实测 52 / 290 / 329.5）：
         1920×1080 / 1920×1200 / 2560×1440 全显示、无卡片滚动条；视口矮（≤900 高，如 1600×900、
         1440×900）时三段竖排塞不下，卡片自己滚（overflow-y-auto 兜底，见 style.css 里
         .donut-wrap 的分档注释）。
         外边距比早期版本小（左右 40/上 30），是用户要求「内部 padding 减小」 -->
    <CardContent class="flex min-h-0 flex-1 flex-col overflow-y-auto px-8 pt-5 pb-1.5 xl:px-10 xl:pt-6">
      <!-- 第一段：分段控件（**样式照投资看板**：性能卡右上那组「本金 vs 总持仓 / 收益走势」——
           圆角边框容器 + 一块 bg-brand 滑动底色 + 选中项反白字）。
           2026-10-05 改版：原来是三块全宽大数字 KPI（各带标签/数字/小注，120px 高）兼任切换开关，
           用户要求「换成投资看板那种切换、切过来再显示详细数据（和总金额一起）、
           上部空间解放出来放柱状图」—— 切换只留一行，数字挪到第二段右侧的「数据 + 总金额」两块里。
           **2026-10-05 二次调整：「做小一点」→ 宽度退回按内容（`w-fit`，1920 下 857 → ~360），
           和看板那组一样是左对齐的紧凑条**（先做过一版 `w-full` 三等分，每格 282px 太空）。
           `max-w-full + 按钮 min-w-0 truncate` 是窄屏兜底：卡片挤到 ~300px 以下时条会被压窄、
           标签才省略号（lg 1024 实测按内容宽放得下，不会截字）。
           底色块的位置由 updateSwitchOverlay 用 offsetLeft 量出来（同看板写法） -->
      <section class="shrink-0">
        <div
          ref="switchEl"
          role="tablist"
          class="relative flex w-fit max-w-full rounded-xl border border-border bg-background p-1 text-xs xl:text-sm"
        >
          <div
            class="absolute top-1 bottom-1 rounded-lg bg-brand shadow-sm transition-all duration-300 ease-out"
            :style="{ left: `${switchOverlay.left}px`, width: `${switchOverlay.width}px` }"
          />
          <button
            v-for="(g, i) in insights"
            :key="g.key"
            :ref="(el) => (switchBtns[i] = el as HTMLElement | null)"
            type="button"
            role="tab"
            :aria-selected="selectedInsight.key === g.key"
            :title="`${g.hint}（点选查看构成）`"
            class="relative z-10 min-w-0 truncate rounded-lg px-2.5 py-1.5 font-medium transition-colors xl:px-4"
            :class="
              selectedInsight.key === g.key
                ? 'text-brand-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            @click="selectedInsightKey = g.key"
          >
            {{ g.label }}
          </button>
        </div>
      </section>

      <!-- 第二段：选中项数据 / 总金额（左，竖线分隔）+ 构成柱状图（右）。
           **2026-10-05 用户要求「把柱状图和左侧的数据展示换一边」**：原来是「图左、数据右」，
           现在是「数据左、图右」——只是把两块调了个儿（DOM 顺序也换了，所以 xl 以下卡片变单列时
           同样是数据在上、图在下，不用 order-* 之类的补丁）。
           柱子数据 = 选中 KPI 的 items，和数字同源（柱子的和就是它）；
           柱子往中间收由 barOption 的 grid left/right 控制；三组都没有资产时给一句占位。
           **这一段 2026-10-05 起不带分割线**（用户要求「把柱状图上面的分割线删除」）：
           原来靠 `border-t` + `-mx-8 px-8 / xl:-mx-10 xl:px-10` 把横线拉到卡片左右沿，
           线一去掉，那对负边距/补偿内边距就没有意义了（净效果是 0，删掉内容位置不变）。
           **2026-10-05 三次调整：图再长高，允许超过左边数据块**（用户「按照这个来」——
           即「明显更高、往资产配置段那个高度去」那版）。
           段高改成 `xl:grow xl:shrink-0 xl:max-h-[30vh]`：1080 下段高 324 → **图 284**
           （最初 250、上一版 261；左边数据块 261）。
           30vh 是按比例封顶，不是写死像素：1440 下段高 432（图 392）、900 下 270（图 230），
           不封顶的话大屏上图会被撑到 700+，把环形图挤没。
           **这个比例是 2026-10-05 用户拍的**：先给 33vh（图 316），他看下来觉得资产配置段被压太紧
           （列表每行 40），让调到 30vh → 图 284、资产配置段 295.5、列表每行 ~47。
           xl:shrink-0 仍是保命的：矮视口没富余可长时，段高退回自然高（图 170 下限），
           卡片照旧整体滚，而不是把图压扁。 -->
      <section
        class="mt-5 flex flex-col gap-5 pt-4 pb-4 xl:max-h-[30vh] xl:shrink-0 xl:grow xl:flex-row xl:items-stretch xl:gap-10"
      >
        <!-- 左侧两块：① 选中项的数据（标签 / 大数字 / 小注，就是原来那块 KPI 的全部内容）
             ② 总金额（同一批 items 的合计，与柱状图同源）—— 用户要求「数据和总金额一起展示」。
             排版：xl 起**上下叠放**（竖线分隔换成横线），lg 以下卡片变单列时改左右并排（竖线分隔）。
             不并排是因为并排要 ~430px，柱状图会从 527 掉到 ~377px，横轴三根柱子的名字会被 ECharts
             按行宽丢掉一个（2026-10-05 实测）；叠放则沿用原来的 240/300 列宽，图宽不变。
             换到左边后竖线改画在右沿（xl:border-r + xl:pr-8），与柱状图之间的 50px 仍由段上的 gap 给。 -->
        <div
          class="flex shrink-0 flex-col justify-center xl:w-[240px] xl:border-r xl:border-border xl:pr-8 2xl:w-[300px]"
        >
          <div
            class="flex flex-col gap-5 sm:flex-row sm:items-start sm:gap-0 sm:divide-x sm:divide-border xl:flex-col xl:gap-0 xl:divide-x-0 xl:divide-y xl:divide-border"
          >
            <div class="min-w-0 sm:flex-1 sm:pr-6 xl:flex-none xl:pr-0 xl:pb-5">
              <p class="truncate text-sm leading-5 text-muted-foreground">{{ selectedInsight.label }}</p>
              <p class="mt-1.5 font-mono text-[clamp(1.5rem,2.35vw,2.25rem)] font-bold leading-none tracking-tight tabular-nums whitespace-nowrap">
                {{ selectedInsight.share }}%
              </p>
              <p class="mt-1.5 truncate text-xs leading-4 text-muted-foreground">
                {{ selectedInsight.caption }}
              </p>
            </div>
            <div class="min-w-0 sm:flex-1 sm:pl-6 xl:flex-none xl:pl-0 xl:pt-5">
              <p class="text-sm leading-5 text-muted-foreground">总金额</p>
              <p class="mt-1.5 font-mono text-[clamp(1.5rem,2.35vw,2.25rem)] font-bold leading-none tracking-tight tabular-nums whitespace-nowrap">
                {{ cny(selectedAmount) }}
              </p>
              <p class="mt-1.5 truncate text-xs leading-4 text-muted-foreground">
                {{ selectedInsight.items.length }} 项合计
              </p>
            </div>
          </div>
        </div>

        <div class="flex min-w-0 flex-1 flex-col">
          <!-- 图高：xl 起吃满这一段（= 段高 − 上下 pt/pb）；lg 以下仍写死 190px。
               xl:min-h-[170px] 是 flex 的坑：flex-1 的 basis 是 0，不给下限的话
               矮视口（没有富余可长）图会被算成 0 高、只剩左边文字块那点高度撑段 -->
          <div
            v-if="selectedInsight.items.length"
            class="mx-auto h-[190px] w-full max-w-[560px] xl:h-auto xl:min-h-[170px] xl:max-w-none xl:flex-1"
          >
            <VChart
              class="h-full w-full"
              :option="barOption"
              autoresize
              :update-options="{ notMerge: true }"
            />
          </div>
          <p
            v-else
            class="mx-auto flex h-[190px] w-full max-w-[560px] items-center justify-center text-sm text-muted-foreground xl:h-auto xl:min-h-[170px] xl:max-w-none xl:flex-1"
          >
            暂无数据
          </p>
        </div>
      </section>

      <!-- 第三段：资产配置 —— 段标题 + 环形图（左） + 分类列表（右）。
           border-t + -mx-8 / xl:-mx-10 把分割线拉到卡片内边距之外、顶到卡片左右沿：卡片自身
           是 overflow-hidden，横线不会真的溢出，只是不再被内边距截断（分隔感更利落）；
           同组 px 把内容重新对齐回内边距。
           高度 flex-1、下限**下面 300 / xl 起 260**（不是只写 xl: ——lg 档卡片被两栏挤到 ~400px、
           段内还是环形图压列表的竖排，若没有 300 这个下限，flex-1 会把整段压成几十像素、
           内容直接画到卡片外面；xl 起并排了，真正的最低需求就是环形图那 200 + 标题与内边距 ~57 = 257，
           所以 260 是「环形图刚好放下、上下无余量」的值）。
           2026-10-05 从 300 放到 260，是因为第二段（柱状图）改吃视口高度（1080 下 30vh = 324），
           剩下的富余不够 300。实际落点：图 284、这一段 295.5、列表每行 ~47（放 260 这条线
           是给「图再长一点」留的余量，正常不该贴到）。
           **别给这段加 shrink-0** —— 那会把高度锁死在「环形图 + 留白」，卡片再高也消化不掉，
           1080 屏上直接溢出滚动条（2026-10-03 两次踩）。
           并排要 xl（卡片 ≥500px）才放得下 —— lg 以下卡片只有 400px 上下，挤并排名字会被裁没 -->
      <section
        class="main-allocation flex min-h-[300px] flex-1 flex-col border-t border-border pt-4 pb-1.5 -mx-8 px-8 xl:min-h-[260px] xl:pt-4 xl:pb-1.5 xl:-mx-10 xl:px-10"
      >
        <p class="shrink-0 text-sm leading-5 text-muted-foreground">资产配置</p>

        <div class="flex min-h-0 flex-1 flex-col items-center gap-6 xl:mt-2 xl:flex-row xl:items-start xl:gap-10">
          <!-- 环形图：并排（xl）后**大小交给 flex 算**，不写死宽度 —— 卡片在 lg 两栏里只有
               ~406px、xl 约 662px，比折线图卡片（734px）窄，写太宽会把右侧列表挤到截字 +
               卡内横向滚动条。宽度断点封顶 170/200/200（用户要求整体缩小一档，约 −17%），
               xl 起 flex-1 吃列表之外的余量，列表用 min-w 保底 —— 空间不够时让环形图变小，而不是列表溢出。
               200 比右侧列表矮一档（列表 2026-10-05 起按列高平分，1080 下 5 行共 274），
               居中后上下各留 ~37px —— 用户明确认可「环形图略矮、居中留白」这个取向；再高第三段就顶出卡片
               （三段竖排比原来的两栏更吃高度）；xl:self-center 让环形图中心与右侧列表的中心对齐 -->
          <div
            class="donut-wrap aspect-square w-full max-w-[170px] sm:max-w-[200px] xl:min-w-[170px] xl:flex-1 xl:max-w-[200px] xl:self-center"
          >
            <VChart
              class="h-full w-full"
              :option="option"
              autoresize
              :update-options="{ notMerge: true }"
            />
          </div>

          <!-- xl 起列表 flex-1 撑满右侧剩余宽度（环形图靠左、两端分布）。
               xl 的 280px 下限是「名称 / 占比 / 金额」三列不截字的实测值：圆点 15 + 短名 30 +
               占比 53 + 金额 120 + 三个 12.5px 间距 + 行内左右 20 —— 1440 的卡片只有 662px，
               下限再低名字就被截成一个字（「股」），环形图按这个下限让位。
               xl:self-stretch 是关键：列高 = 环形图那段的高度，列表按这个高度平分行长（见下），
               只有空间连「5 × 行内最小高度」都不够时才会滚动 -->
          <div
            class="w-full min-w-0 max-w-[420px] xl:flex xl:min-h-0 xl:min-w-[280px] xl:max-w-none xl:flex-1 xl:flex-col xl:self-stretch"
          >
            <!-- 资产构成：名称 / 占比 / 金额；有基金的分类可点，轻微的可交互暗示。
                 xl 起列是 stretch（列高 = 环形图那一段的高度），列表超过列高时自己滚。
                 **2026-10-05 用户要求「贴紧环形图、行距舒开」**：原先横条限宽 380 并在列内
                 居中，列被 flex-1 拉满后两侧各留一大块空白（1920 下环形图右缘到列表 211px、
                 列表右缘到卡片沿还有 171px），列表像浮在中间的一条窄带。改成铺满整列
                 （左缘接 gap、右缘贴卡片内边距），名称与金额按「两端分布」撑开 —— 列宽本身
                 就是给这张表用的，不再另限宽。
                 行高**不写死 py**，交给 flex 平分列高（每行 = 列高 / 5，上限 58）：
                 列高是视口高度算出来的（1080 下 274 → 每行 55），写死就会在矮视口
                 （1600×900、1366×768 的列高只有 237）把「现金」那行挤出列外、列表自己出滚动条。
                 上限 58 是防大屏摊太开（2560×1440 列高 602，不封顶会被撑成一整列 602，
                 比旁边的环形图 380 还高）。
                 xl:justify-center + 上限封顶后剩余空间留在两端，列表居中不贴顶。 -->
            <ul
              class="divide-y divide-border/50 xl:flex xl:h-full xl:min-h-0 xl:flex-col xl:justify-center xl:overflow-y-auto"
            >
              <!-- hover / 选中的背景填充整行 li（而非内部 button），button 只承载内容与点击 -->
              <li
                v-for="a in data"
                :key="a.category"
                class="transition-colors xl:max-h-[58px] xl:flex-1"
                :class="[
                  a.clickable ? 'cursor-pointer hover:bg-muted/60' : 'cursor-default',
                  activeBucket === a.category ? 'bg-muted/70' : '',
                ]"
              >
                <button
                  type="button"
                  class="flex w-full items-center gap-2.5 px-2 py-3 text-left xl:h-full xl:py-0"
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
                    class="truncate text-sm font-medium xl:text-base"
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
        </div>
      </section>
    </CardContent>
  </Card>
</template>
