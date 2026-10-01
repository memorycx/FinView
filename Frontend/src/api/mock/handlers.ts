import type { AssetCategory, DistributionData, DistributionDim, PortfolioSeries, Fund, AdjustmentPayload, SavingsPlan } from '../types'
import { ApiError } from '../error'
import { db } from './db'

/** 未定投的现金/货币基金储备（元） */
const CASH_RESERVE = 128000

const CATEGORY_LABELS: Record<AssetCategory, string> = {
  fund: '基金',
  stock: '股票',
  bond: '债券',
  cash: '现金',
}

/** 未归档（持有中）：与后端 AssetService / PortfolioService 及 /funds?status=active 的口径一致 */
const unarchivedFunds = () => db.funds.filter((f) => !f.archived)

/** 与 lib/finance.returnRate 同口径：累计收益 / 累计投入（mock 数据没清过仓，投入 = 本金 + 手续费） */
const returnRate = (f: Fund) => {
  const invested = f.invested ?? f.principal + f.fee
  return invested === 0 ? 0 : (f.current - f.principal - f.fee) / invested
}

/* ==================== 定投计划 ==================== */

function listFunds(status?: string): Fund[] {
  if (status === 'active') return unarchivedFunds()
  if (status === 'archived') return db.funds.filter((f) => f.archived)
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

/** 按自然日汇总全部持有中（未归档）持仓的本金与市值（日度精度，未开始的日期不计入） */
function portfolioSeries(): PortfolioSeries {
  const merged = new Map<string, { principal: number; total: number; fee: number }>()
  for (const f of unarchivedFunds()) {
    for (const p of f.series) {
      const cur = merged.get(p.day)
      if (cur) {
        cur.principal += p.principal
        cur.total += p.total
        cur.fee += p.fee
      } else {
        merged.set(p.day, { principal: p.principal, total: p.total, fee: p.fee })
      }
    }
  }
  return {
    series: [...merged.entries()]
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([day, v]) => ({ day, principal: v.principal, total: v.total, fee: v.fee })),
    // 组合年化由后端 XIRR 算；mock 不实现求解器，写死一个估值即可
    annualizedRate: 0.0921,
  }
}

/* ==================== 资产分析 ==================== */

/** 资产总览：按大类汇总当前市值（含现金储备） */
function assetSummary() {
  const base: Record<AssetCategory, number> = { fund: 0, stock: 0, bond: 0, cash: 0 }
  for (const f of unarchivedFunds()) {
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

function requireSavingsPlan(id: string): SavingsPlan {
  const plan = db.savingsPlans.find((p) => p.id === id)
  if (!plan) throw new ApiError(404, `存钱计划不存在: ${id}`)
  return plan
}

/* ==================== 资产调整记录（录入数据） ==================== */

/** Mock 自增 ID */
let adjustmentSeq = 0

/** 校验并落库一条调整记录，返回后端视图对象 */
function createAdjustment(payload: AdjustmentPayload) {
  if (!payload || typeof payload !== 'object') {
    throw new ApiError(400, '请求体不能为空')
  }
  if (!payload.code?.trim()) throw new ApiError(400, '资产编码不能为空')
  if (payload.name && payload.name.length > 100) {
    throw new ApiError(400, '资产名称长度不能超过 100')
  }
  if (!payload.date) throw new ApiError(400, '记录日期不能为空')
  if (![1, 2, 3, 4].includes(payload.action)) {
    throw new ApiError(400, '动作必须是 1/2/3/4')
  }
  if (payload.action === 1 && !payload.frequency) {
    throw new ApiError(400, '定投开始需指定频率')
  }
  if (payload.amount == null || Number(payload.amount) <= 0) {
    throw new ApiError(400, '金额必须大于 0')
  }
  adjustmentSeq += 1
  return {
    adjustmentId: adjustmentSeq,
    userId: 1,
    code: payload.code.trim(),
    date: payload.date,
    reason: payload.reason?.trim() || null,
    action: payload.action,
    frequency: payload.frequency ?? null,
    amount: Number(payload.amount),
    note: payload.note?.trim() || null,
  }
}

/* ==================== 路由表 ==================== */

export interface MockRouteContext {
  /** 路径参数（对应 pattern 中的 `:xxx` 段） */
  params: Record<string, string>
  /** 查询参数（值为 string，undefined 的参数已被剔除） */
  query: Record<string, string>
  /** 请求体（POST/PUT/PATCH 时透传，GET 时为 undefined） */
  body?: unknown
}

export interface MockRoute {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
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
  // 录入数据：新建资产调整记录
  {
    method: 'POST',
    pattern: '/adjustments',
    handle: ({ body }) => createAdjustment(body as AdjustmentPayload),
  },
]
