/**
 * 领域模型与接口出入参类型（前后端共享的接口契约）
 */

/* ==================== 通用 ==================== */

/** 定投计划状态筛选 */
export type FundStatus = 'active' | 'archived' | 'all'

/** 资产分布维度 */
export type DistributionDim = 'industry' | 'region' | 'currency'

/* ==================== 定投计划（基金/持仓） ==================== */

/** 资产大类 */
export type AssetCategory = 'fund' | 'stock' | 'bond' | 'cash'

/** 定投频率 */
export type InvestFrequency = 'daily' | 'weekly' | 'monthly'

/** 时间序列数据点（按日/月采样） */
export type DayPoint = {
  /** YYYY-MM-DD */
  day: string
  /** 累计投入本金 */
  principal: number
  /** 当前总市值 */
  total: number
}

/** 定投调整记录 */
export type PlanAdjustment = {
  id: string
  date: string
  /** 更改原因 */
  reason: string
  /** 调整动作简述 */
  action: string
  /** 调整后的定投频率，null 表示停止定投 */
  frequency: InvestFrequency | null
  /** 调整后的每期定投金额，null 表示停止定投 */
  amount: number | null
  note?: string
}

/** 定投计划（一只基金或一个股票组合） */
export type Fund = {
  id: string
  name: string
  code: string
  category: AssetCategory
  /** 定投是否进行中 */
  active: boolean
  /** 当前定投频率 */
  frequency: InvestFrequency
  /** 当前每期定投金额 */
  amount: number
  /** 开始时间 YYYY-MM */
  startDate: string
  /** 累计投入本金 */
  principal: number
  /** 当前市值 */
  current: number
  /** 本金/市值走势 */
  series: DayPoint[]
  /** 定投调整记录（按时间正序） */
  adjustments: PlanAdjustment[]
}

/* ==================== 资产分析 ==================== */

/** 资产大类配置项 */
export type Allocation = {
  category: AssetCategory
  label: string
  /** 当前市值 */
  value: number
}

/** 资产总览（大类配置 + 总资产） */
export type AssetSummary = {
  /** 总资产（元） */
  totalAssets: number
  /** 按资产大类的市值分布 */
  allocation: Allocation[]
}

/** 分布统计项 */
export type DistributionItem = {
  name: string
  value: number
}

/** 全维度分布数据 */
export type DistributionData = Record<DistributionDim, DistributionItem[]>

/* ==================== 存钱计划 ==================== */

/** 计划类型：总资产目标 / 小愿望 */
export type SavingsPlanType = 'asset' | 'wish'

/** 计划内持有的资产项 */
export type SavingsAsset = {
  name: string
  amount: number
  /** 图表主题色变量 */
  colorVar?: string
}

/** 月度存入数据点 */
export type SavingsMonthPoint = {
  /** YYYY-MM */
  month: string
  amount: number
}

/** 存钱计划 */
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
  /** 月度存入走势 */
  series: SavingsMonthPoint[]
}
