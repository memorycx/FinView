import type { AssetCategory, DistributionData, DistributionDim, DayPoint, Fund } from '../types'
import { ApiError } from '../error'
import { db } from './db'

/** 未定投的现金/货币基金储备（元） */
const CASH_RESERVE = 128000

/** 组合走势覆盖的年份区间 */
const PORTFOLIO_YEARS = [2019, 2020, 2021, 2022, 2023, 2024, 2025] as const

const CATEGORY_LABELS: Record<AssetCategory, string> = {
  fund: '基金',
  stock: '股票',
  bond: '债券',
  cash: '现金',
}

const activeFunds = () => db.funds.filter((f) => f.active)

const returnRate = (f: Fund) => (f.current - f.principal) / f.principal

/* ==================== 定投计划 ==================== */

function listFunds(status?: string): Fund[] {
  if (status === 'active') return activeFunds()
  if (status === 'archived') return db.funds.filter((f) => !f.active)
  return [...db.funds]
}

function requireFund(id: string): Fund {
  const fund = db.funds.find((f) => f.id === id)
  if (!fund) throw new ApiError(404, `定投计划不存在: ${id}`)
  return fund
}

/** 收益排行榜：按收益率降序（含已归档） */
function fundLeaderboard(): Fund[] {
  return [...db.funds].sort((a, b) => returnRate(b) - returnRate(a))
}

/* ==================== 组合总览 ==================== */

/** 在基金 series 中查找不超过目标日期的最新数据点 */
function pickPointOnOrBefore(series: DayPoint[], targetDay: string): DayPoint | undefined {
  let pick: DayPoint | undefined
  for (const p of series) {
    if (p.day <= targetDay) pick = p
    else break
  }
  return pick
}

/** 按年份汇总全部进行中持仓的本金与市值（每年取年末数据点） */
function portfolioSeries() {
  return PORTFOLIO_YEARS.map((year) => {
    const day = `${year}-12-01`
    let principal = 0
    let total = 0
    for (const f of activeFunds()) {
      const point = pickPointOnOrBefore(f.series, day)
      if (point) {
        principal += point.principal
        total += point.total
      }
    }
    return { day, principal, total }
  })
}

/* ==================== 资产分析 ==================== */

/** 资产总览：按大类汇总当前市值（含现金储备） */
function assetSummary() {
  const base: Record<AssetCategory, number> = { fund: 0, stock: 0, bond: 0, cash: 0 }
  for (const f of activeFunds()) {
    base[f.category] += f.current
  }
  base.cash += CASH_RESERVE

  const allocation = (Object.keys(base) as AssetCategory[]).map((category) => ({
    category,
    label: CATEGORY_LABELS[category],
    value: base[category],
  }))
  return {
    totalAssets: allocation.reduce((s, a) => s + a.value, 0),
    allocation,
  }
}

const distributionData: DistributionData = {
  industry: [
    { name: '宽基指数', value: 312400 },
    { name: '科技互联网', value: 330200 },
    { name: '红利低波', value: 96800 },
    { name: '新能源', value: 94600 },
    { name: '固收债券', value: 141200 },
    { name: '黄金商品', value: 52700 },
    { name: '现金储备', value: 128000 },
  ],
  region: [
    { name: 'A股', value: 603200 },
    { name: '美股', value: 268900 },
    { name: '港股', value: 61300 },
    { name: '跨境(QDII)', value: 0 },
    { name: '商品/海外', value: 52700 },
    { name: '现金', value: 128000 },
  ],
  currency: [
    { name: '人民币 CNY', value: 697700 },
    { name: '美元 USD', value: 268900 },
    { name: '港币 HKD', value: 61300 },
  ],
}

function distribution(dim?: string) {
  if (!dim) return distributionData
  const key = dim as DistributionDim
  if (!(key in distributionData)) throw new ApiError(400, `未知的分布维度: ${dim}`)
  return distributionData[key]
}

/* ==================== 存钱计划 ==================== */

function listSavingsPlans() {
  return [...db.savingsPlans]
}

function requireSavingsPlan(id: string) {
  const plan = db.savingsPlans.find((p) => p.id === id)
  if (!plan) throw new ApiError(404, `存钱计划不存在: ${id}`)
  return plan
}

/* ==================== 路由表 ==================== */

export interface MockRouteContext {
  /** 路径参数（对应 pattern 中的 `:xxx` 段） */
  params: Record<string, string>
  /** 查询参数（值为 string，undefined 的参数已被剔除） */
  query: Record<string, string>
}

export interface MockRoute {
  method: 'GET'
  /** 路由模式，支持 `:id` 形式的路径参数 */
  pattern: string
  handle: (ctx: MockRouteContext) => unknown
}

/**
 * Mock 路由表。
 * 注意：静态段路由（如 /funds/leaderboard）必须排在同长度动态路由（/funds/:id）之前。
 */
export const mockRoutes: MockRoute[] = [
  // 定投计划
  { method: 'GET', pattern: '/funds/leaderboard', handle: () => fundLeaderboard() },
  { method: 'GET', pattern: '/funds/:id', handle: ({ params }) => requireFund(params.id) },
  { method: 'GET', pattern: '/funds', handle: ({ query }) => listFunds(query.status) },
  // 组合总览
  { method: 'GET', pattern: '/portfolio/series', handle: () => portfolioSeries() },
  // 资产分析
  { method: 'GET', pattern: '/assets/summary', handle: () => assetSummary() },
  { method: 'GET', pattern: '/assets/distribution', handle: ({ query }) => distribution(query.dim) },
  // 存钱计划
  { method: 'GET', pattern: '/savings/plans/:id', handle: ({ params }) => requireSavingsPlan(params.id) },
  { method: 'GET', pattern: '/savings/plans', handle: () => listSavingsPlans() },
]
