export type AssetCategory = "fund" | "stock" | "bond" | "cash"

export const categoryLabels: Record<AssetCategory, string> = {
  fund: "基金",
  stock: "股票",
  bond: "债券",
  cash: "现金",
}

export type YearPoint = {
  year: number
  /** 累计投入本金 */
  principal: number
  /** 当前总市值 */
  total: number
}

export type PlanAdjustment = {
  id: string
  date: string
  /** 更改原因 */
  reason: string
  /** 调整动作简述 */
  action: string
  /** 调整后的月定投金额，null 表示停止定投 */
  monthlyAmount: number | null
  note?: string
}

export type Fund = {
  id: string
  name: string
  code: string
  category: AssetCategory
  /** 定投是否进行中 */
  active: boolean
  /** 当前月定投金额 */
  monthlyAmount: number
  startDate: string
  /** 累计投入本金 */
  principal: number
  /** 当前市值 */
  current: number
  series: YearPoint[]
  adjustments: PlanAdjustment[]
}

export function returnRate(f: { principal: number; current: number }) {
  return (f.current - f.principal) / f.principal
}

export function profit(f: { principal: number; current: number }) {
  return f.current - f.principal
}

export const funds: Fund[] = [
  {
    id: "csi300",
    name: "沪深300指数增强",
    code: "005827",
    category: "fund",
    active: true,
    monthlyAmount: 3000,
    startDate: "2019-03",
    principal: 246000,
    current: 312400,
    series: [
      { year: 2019, principal: 30000, total: 31200 },
      { year: 2020, principal: 66000, total: 79800 },
      { year: 2021, principal: 102000, total: 121500 },
      { year: 2022, principal: 138000, total: 132900 },
      { year: 2023, principal: 174000, total: 188200 },
      { year: 2024, principal: 210000, total: 252600 },
      { year: 2025, principal: 246000, total: 312400 },
    ],
    adjustments: [
      { id: "a1", date: "2019-03-10", reason: "计划启动", action: "开始定投", monthlyAmount: 2000, note: "以沪深300为核心底仓" },
      { id: "a2", date: "2020-04-06", reason: "市场大幅回撤，估值处于低位", action: "加大定投", monthlyAmount: 3500 },
      { id: "a3", date: "2022-05-18", reason: "现金流阶段性紧张", action: "下调金额", monthlyAmount: 2000 },
      { id: "a4", date: "2024-01-15", reason: "估值修复，恢复常态投入", action: "上调金额", monthlyAmount: 3000 },
    ],
  },
  {
    id: "nasdaq",
    name: "纳斯达克100(QDII)",
    code: "270042",
    category: "fund",
    active: true,
    monthlyAmount: 2500,
    startDate: "2020-01",
    principal: 174000,
    current: 268900,
    series: [
      { year: 2020, principal: 24000, total: 29800 },
      { year: 2021, principal: 54000, total: 78600 },
      { year: 2022, principal: 84000, total: 88200 },
      { year: 2023, principal: 114000, total: 148900 },
      { year: 2024, principal: 144000, total: 214300 },
      { year: 2025, principal: 174000, total: 268900 },
    ],
    adjustments: [
      { id: "b1", date: "2020-01-08", reason: "布局海外科技资产", action: "开始定投", monthlyAmount: 2000 },
      { id: "b2", date: "2023-02-20", reason: "AI 主线景气度提升", action: "上调金额", monthlyAmount: 2500 },
    ],
  },
  {
    id: "dividend",
    name: "中证红利低波",
    code: "515100",
    category: "fund",
    active: true,
    monthlyAmount: 1500,
    startDate: "2021-06",
    principal: 82500,
    current: 96800,
    series: [
      { year: 2021, principal: 10500, total: 10800 },
      { year: 2022, principal: 28500, total: 30200 },
      { year: 2023, principal: 46500, total: 51900 },
      { year: 2024, principal: 64500, total: 74600 },
      { year: 2025, principal: 82500, total: 96800 },
    ],
    adjustments: [
      { id: "c1", date: "2021-06-12", reason: "配置低波动分红资产对冲", action: "开始定投", monthlyAmount: 1500 },
    ],
  },
  {
    id: "bond-fund",
    name: "稳健债券配置",
    code: "003376",
    category: "bond",
    active: true,
    monthlyAmount: 2000,
    startDate: "2020-09",
    principal: 126000,
    current: 141200,
    series: [
      { year: 2020, principal: 8000, total: 8100 },
      { year: 2021, principal: 32000, total: 33200 },
      { year: 2022, principal: 56000, total: 58900 },
      { year: 2023, principal: 80000, total: 86400 },
      { year: 2024, principal: 104000, total: 114700 },
      { year: 2025, principal: 126000, total: 141200 },
    ],
    adjustments: [
      { id: "d1", date: "2020-09-01", reason: "构建组合防御垫", action: "开始定投", monthlyAmount: 1500 },
      { id: "d2", date: "2022-11-10", reason: "债市回调后提升配置", action: "上调金额", monthlyAmount: 2000 },
    ],
  },
  {
    id: "growth-stock",
    name: "新能源龙头组合",
    code: "SZ-EV",
    category: "stock",
    active: true,
    monthlyAmount: 2000,
    startDate: "2021-01",
    principal: 108000,
    current: 94600,
    series: [
      { year: 2021, principal: 24000, total: 29400 },
      { year: 2022, principal: 48000, total: 43200 },
      { year: 2023, principal: 72000, total: 61800 },
      { year: 2024, principal: 96000, total: 82400 },
      { year: 2025, principal: 108000, total: 94600 },
    ],
    adjustments: [
      { id: "e1", date: "2021-01-20", reason: "看好电动化长期趋势", action: "开始定投", monthlyAmount: 3000 },
      { id: "e2", date: "2023-08-14", reason: "行业竞争加剧，控制仓位", action: "下调金额", monthlyAmount: 2000 },
    ],
  },
  {
    id: "gold",
    name: "黄金ETF联接",
    code: "000216",
    category: "fund",
    active: true,
    monthlyAmount: 1000,
    startDate: "2022-03",
    principal: 40000,
    current: 52700,
    series: [
      { year: 2022, principal: 10000, total: 10400 },
      { year: 2023, principal: 22000, total: 25100 },
      { year: 2024, principal: 34000, total: 42800 },
      { year: 2025, principal: 40000, total: 52700 },
    ],
    adjustments: [
      { id: "f1", date: "2022-03-05", reason: "对冲通胀与地缘风险", action: "开始定投", monthlyAmount: 1000 },
    ],
  },
  {
    id: "hk-tech",
    name: "恒生科技(已归档)",
    code: "513130",
    category: "stock",
    active: false,
    monthlyAmount: 0,
    startDate: "2021-02",
    principal: 72000,
    current: 61300,
    series: [
      { year: 2021, principal: 22000, total: 24800 },
      { year: 2022, principal: 50000, total: 39600 },
      { year: 2023, principal: 72000, total: 58200 },
      { year: 2024, principal: 72000, total: 61300 },
    ],
    adjustments: [
      { id: "g1", date: "2021-02-18", reason: "布局港股互联网", action: "开始定投", monthlyAmount: 2500 },
      { id: "g2", date: "2022-10-24", reason: "监管与流动性压力持续", action: "下调金额", monthlyAmount: 1500 },
      { id: "g3", date: "2023-12-30", reason: "调整配置结构，止盈离场", action: "停止定投", monthlyAmount: null, note: "本金转投红利低波策略" },
    ],
  },
  {
    id: "reit",
    name: "基础设施REITs(已归档)",
    code: "180301",
    category: "fund",
    active: false,
    monthlyAmount: 0,
    startDate: "2021-09",
    principal: 48000,
    current: 53900,
    series: [
      { year: 2021, principal: 8000, total: 8100 },
      { year: 2022, principal: 26000, total: 27400 },
      { year: 2023, principal: 44000, total: 48600 },
      { year: 2024, principal: 48000, total: 53900 },
    ],
    adjustments: [
      { id: "h1", date: "2021-09-10", reason: "试水基础设施REITs", action: "开始定投", monthlyAmount: 1500 },
      { id: "h2", date: "2024-03-01", reason: "达到预期目标收益", action: "停止定投", monthlyAmount: null, note: "计划圆满结束" },
    ],
  },
]

export const activeFunds = funds.filter((f) => f.active)
export const archivedFunds = funds.filter((f) => !f.active)

/** 组合总览折线：按年份汇总所有进行中持仓的本金与市值 */
export const portfolioSeries: YearPoint[] = (() => {
  const years = [2019, 2020, 2021, 2022, 2023, 2024, 2025]
  return years.map((year) => {
    let principal = 0
    let total = 0
    for (const f of activeFunds) {
      const point = f.series.find((p) => p.year === year)
      if (point) {
        principal += point.principal
        total += point.total
      }
    }
    return { year, principal, total }
  })
})()

export type Allocation = {
  category: AssetCategory
  label: string
  value: number
}

/** 资产总览：按大类统计当前市值（含一部分未定投的现金储备） */
export const allocation: Allocation[] = (() => {
  const base: Record<AssetCategory, number> = { fund: 0, stock: 0, bond: 0, cash: 0 }
  for (const f of activeFunds) {
    base[f.category] += f.current
  }
  base.cash += 128000 // 活期与货币基金储备
  return (Object.keys(base) as AssetCategory[]).map((category) => ({
    category,
    label: categoryLabels[category],
    value: base[category],
  }))
})()

export const totalAssets = allocation.reduce((s, a) => s + a.value, 0)

/** 收益排行榜：按收益率排序（含归档） */
export const leaderboard = [...funds].sort((a, b) => returnRate(b) - returnRate(a))

export function formatCNY(n: number) {
  return "¥" + n.toLocaleString("zh-CN", { maximumFractionDigits: 0 })
}

export function formatWan(n: number) {
  return (n / 10000).toLocaleString("zh-CN", { maximumFractionDigits: 1 }) + "万"
}

export function formatPct(n: number) {
  return (n >= 0 ? "+" : "") + (n * 100).toFixed(1) + "%"
}

/** 分布维度 */
export type DistributionDim = "industry" | "region" | "currency"

export type DistributionItem = {
  name: string
  value: number
}

/** 行业分布（基于持仓成分估算） */
export const industryDistribution: DistributionItem[] = [
  { name: "宽基指数", value: 312400 },
  { name: "科技互联网", value: 330200 },
  { name: "红利低波", value: 96800 },
  { name: "新能源", value: 94600 },
  { name: "固收债券", value: 141200 },
  { name: "黄金商品", value: 52700 },
  { name: "现金储备", value: 128000 },
]

/** 区域分布 */
export const regionDistribution: DistributionItem[] = [
  { name: "A股", value: 603200 },
  { name: "美股", value: 268900 },
  { name: "港股", value: 61300 },
  { name: "跨境(QDII)", value: 0 },
  { name: "商品/海外", value: 52700 },
  { name: "现金", value: 128000 },
]

/** 币种分布 */
export const currencyDistribution: DistributionItem[] = [
  { name: "人民币 CNY", value: 697700 },
  { name: "美元 USD", value: 268900 },
  { name: "港币 HKD", value: 61300 },
]

export const distributionData: Record<DistributionDim, DistributionItem[]> = {
  industry: industryDistribution,
  region: regionDistribution,
  currency: currencyDistribution,
}

export const distributionDimLabel: Record<DistributionDim, string> = {
  industry: "行业",
  region: "区域",
  currency: "币种",
}

/* ==================== 存钱计划 ==================== */

export type SavingsPlanType = "asset" | "wish"

export type SavingsAsset = {
  name: string
  amount: number
  /** 图表主题色变量 */
  colorVar?: string
}

export type SavingsMonthPoint = {
  /** YYYY-MM */
  month: string
  amount: number
}

export type SavingsPlan = {
  id: string
  name: string
  description: string
  type: SavingsPlanType
  /** 详情页头图 URL（留空则占位） */
  image?: string
  currentAmount: number
  targetAmount: number
  startDate: string
  targetDate: string
  monthlyPlanAmount: number
  quote?: string
  note?: string
  noteImage?: string
  assets: SavingsAsset[]
  series: SavingsMonthPoint[]
}

export const savingsPlans: SavingsPlan[] = [
  {
    id: "total-asset",
    name: "30岁资产目标",
    description: "财务自由，从现在开始",
    type: "asset",
    currentAmount: 160000,
    targetAmount: 600000,
    startDate: "2025-01-01",
    targetDate: "2030-12-31",
    monthlyPlanAmount: 5000,
    assets: [
      { name: "沪深300指数增强", amount: 312400, colorVar: "--chart-1" },
      { name: "纳斯达克100", amount: 268900, colorVar: "--chart-3" },
      { name: "稳健债券配置", amount: 141200, colorVar: "--chart-4" },
      { name: "黄金ETF联接", amount: 52700, colorVar: "--chart-5" },
      { name: "红利低波", amount: 96800, colorVar: "--chart-1" },
      { name: "现金储备", amount: 128000, colorVar: "--chart-5" },
    ],
    series: [
      { month: "2025-01", amount: 142000 },
      { month: "2025-03", amount: 148500 },
      { month: "2025-05", amount: 151200 },
      { month: "2025-07", amount: 154800 },
      { month: "2025-09", amount: 157600 },
      { month: "2025-11", amount: 160000 },
    ],
  },
  {
    id: "japan-trip",
    name: "日本旅行",
    description: "去看更大的世界，体验不同的风景和文化。",
    type: "wish",
    image: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=japanese%20landmark%20torii%20gate%20kyoto%20travel&image_size=landscape_4_3",
    currentAmount: 8000,
    targetAmount: 10000,
    startDate: "2025-05-01",
    targetDate: "2026-12-01",
    monthlyPlanAmount: 1000,
    quote: "世界这么大，我想去看看。",
    note: "希望明年能去日本旅行，体验樱花季，吃美食，看看富士山！",
    noteImage: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=mount%20fuji%20with%20cherry%20blossoms%20landscape&image_size=landscape_4_3",
    assets: [
      { name: "旅行专用银行卡", amount: 4200, colorVar: "--chart-1" },
      { name: "余额宝", amount: 2300, colorVar: "--chart-3" },
      { name: "货币基金", amount: 1500, colorVar: "--chart-4" },
    ],
    series: [
      { month: "2025-05", amount: 4500 },
      { month: "2025-07", amount: 5800 },
      { month: "2025-09", amount: 7000 },
      { month: "2025-11", amount: 8000 },
    ],
  },
  {
    id: "buy-computer",
    name: "买电脑",
    description: "提升生产力",
    type: "wish",
    image: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20laptop%20on%20desk%20productivity&image_size=landscape_4_3",
    currentAmount: 7200,
    targetAmount: 12000,
    startDate: "2025-06-01",
    targetDate: "2026-12-15",
    monthlyPlanAmount: 1200,
    assets: [
      { name: "科技基金", amount: 5200, colorVar: "--chart-1" },
      { name: "现金", amount: 2000, colorVar: "--chart-5" },
    ],
    series: [
      { month: "2025-06", amount: 4800 },
      { month: "2025-08", amount: 5800 },
      { month: "2025-10", amount: 6600 },
      { month: "2025-12", amount: 7200 },
    ],
  },
  {
    id: "graduation-trip",
    name: "毕业旅行",
    description: "给大学生活一个完美的句号",
    type: "wish",
    image: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=suitcase%20travel%20adventure%20wanderlust&image_size=landscape_4_3",
    currentAmount: 3500,
    targetAmount: 8000,
    startDate: "2025-03-01",
    targetDate: "2027-06-01",
    monthlyPlanAmount: 800,
    assets: [
      { name: "余额宝", amount: 2100, colorVar: "--chart-3" },
      { name: "货币基金", amount: 1400, colorVar: "--chart-4" },
    ],
    series: [
      { month: "2025-03", amount: 1200 },
      { month: "2025-06", amount: 1800 },
      { month: "2025-09", amount: 2600 },
      { month: "2025-12", amount: 3500 },
    ],
  },
  {
    id: "future-home",
    name: "未来的家",
    description: "一个温暖的小家",
    type: "wish",
    image: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20cozy%20house%20dream%20home&image_size=landscape_4_3",
    currentAmount: 60000,
    targetAmount: 500000,
    startDate: "2022-01-01",
    targetDate: "2030-12-31",
    monthlyPlanAmount: 8000,
    assets: [
      { name: "沪深300增强", amount: 312400, colorVar: "--chart-1" },
      { name: "红利低波", amount: 96800, colorVar: "--chart-3" },
      { name: "纳斯达克100", amount: 190800, colorVar: "--chart-4" },
      { name: "现金", amount: 128000, colorVar: "--chart-5" },
    ],
    series: [
      { month: "2025-01", amount: 42000 },
      { month: "2025-04", amount: 48000 },
      { month: "2025-07", amount: 54000 },
      { month: "2025-10", amount: 60000 },
    ],
  },
]

export function savingsProgress(p: SavingsPlan) {
  return p.targetAmount === 0 ? 0 : Math.min(1, p.currentAmount / p.targetAmount)
}

export function savingsRemaining(p: SavingsPlan) {
  return Math.max(0, p.targetAmount - p.currentAmount)
}

/** 根据开始时间、目标金额、已存金额，推算预计完成时间 */
export function savingsEstimatedDate(p: SavingsPlan): string {
  const monthsNeeded = Math.ceil(
    savingsRemaining(p) / (p.monthlyPlanAmount || 1),
  )
  const d = new Date()
  d.setMonth(d.getMonth() + monthsNeeded)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`
}
