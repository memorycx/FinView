import type { AllocationBucket, DistributionData, DistributionDim, PortfolioSeries, Fund, AdjustmentAction, AdjustmentPayload, PlanAdjustment, SavingsPlan } from '../types'
import { ApiError } from '../error'
import { db } from './db'

/** 未定投的现金/货币基金储备（元） */
const CASH_RESERVE = 128000

/** 展示桶中文名，与后端 AssetService.BUCKET_LABELS 一一对应（基金按 assetType 细分成两个桶） */
const BUCKET_LABELS: Record<AllocationBucket, string> = {
  equityFund: '股票基金',
  bondFund: '债券基金',
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
    // 「最后更新时间」= 全部资产里最新的一个（后端口径：asset 表全部行的 MAX，ISO 字符串可直接比较）
    lastUpdateTime: db.funds
      .map((f) => f.lastUpdateTime)
      .filter((t): t is string => !!t)
      .sort()
      .pop() ?? null,
  }
}

/* ==================== 资产分析 ==================== */

/**
 * 资产总览：按展示桶汇总当前市值（含现金储备）。
 * 与后端同口径：基金按 assetType 细分（null 按股基算），五个桶恒返回，没有的为 0。
 */
function assetSummary() {
  const base: Record<AllocationBucket, number> = {
    equityFund: 0,
    bondFund: 0,
    stock: 0,
    bond: 0,
    cash: 0,
  }
  for (const f of unarchivedFunds()) {
    if (f.category === 'fund') {
      base[f.assetType === 'bond' ? 'bondFund' : 'equityFund'] += f.current
    } else {
      base[f.category as AllocationBucket] += f.current
    }
  }
  base.cash += CASH_RESERVE

  const allocation = (Object.keys(base) as AllocationBucket[]).map((category) => ({
    category,
    label: BUCKET_LABELS[category],
    value: base[category],
  }))
  return {
    totalAssets: allocation.reduce((s, a) => s + a.value, 0),
    // 安全资金 = 债基 + 现金（与后端 asset_type ∈ {bond, cash} 一致），未标注按股基算、不计入
    safeAssets: CASH_RESERVE + base.bondFund,
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

/** 动作码 → 时间线文案（mock 的 fund.adjustments 存的是展示文案，不是动作码） */
const ACTION_LABELS: Record<AdjustmentAction, string> = {
  1: '定投开始',
  2: '结束定投',
  3: '一笔收入',
  4: '一笔支出',
}

/** 时间线文案 → 动作码：编辑表单要把 fixture 里的文案还原成 1/2/3/4（含「上调金额」这类 action=1 的细分文案） */
const ACTION_CODES: Record<string, AdjustmentAction> = {
  定投开始: 1,
  开始定投: 1,
  加大定投: 1,
  上调金额: 1,
  下调金额: 1,
  调整频率: 1,
  结束定投: 2,
  一笔收入: 3,
  一笔支出: 4,
}

/** 新建 / 更新的公共校验，与后端 AdjustmentRequest 的注解一一对应 */
function validateAdjustmentPayload(payload: AdjustmentPayload) {
  if (!payload || typeof payload !== 'object') {
    throw new ApiError(400, '请求体不能为空')
  }
  if (!payload.code?.trim()) throw new ApiError(400, '资产编码不能为空')
  if (payload.name && payload.name.length > 100) {
    throw new ApiError(400, '资产名称长度不能超过 100')
  }
  // 类型只认 equity / bond（cash 由保留 code CASH 系统维护，不接受前端指定）
  if (payload.assetType && payload.assetType !== 'equity' && payload.assetType !== 'bond') {
    throw new ApiError(400, '资产类型必须是 equity/bond')
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
}

/** payload → fixture 形态的时间线项（action 是文案；结束定投的频率与金额都置空，列表靠它显示徽标） */
function toRecord(id: string, payload: AdjustmentPayload): PlanAdjustment {
  return {
    id,
    date: payload.date,
    reason: payload.reason?.trim() || '',
    action: ACTION_LABELS[payload.action],
    frequency: payload.action === 1 ? (payload.frequency ?? null) : null,
    amount: payload.action === 2 ? null : Number(payload.amount),
    note: payload.note?.trim() || undefined,
  }
}

/**
 * 把记录落进 fixture：已有该 code 的基金就追加，没有就造一只最小可用的新基金
 * （mock 不重算序列，用记录当天的一个序列点把走势图撑住）。
 */
function ensureFundForRecord(code: string, payload: AdjustmentPayload, record: PlanAdjustment): Fund {
  const existing = db.funds.find((f) => f.code === code)
  if (existing) {
    existing.adjustments.push(record)
    // 类型只在请求里带了才覆盖（与后端 applyAssetType 的「传了才改」一致）
    if (payload.assetType) existing.assetType = payload.assetType
    return existing
  }
  // 定投开始 / 一笔收入会让市值跟着本金走；结束定投与支出在 mock 里按 0 处理，够展示用
  const principal = payload.action === 1 || payload.action === 3 ? Number(payload.amount) : 0
  const fund: Fund = {
    id: code,
    name: payload.name?.trim() || code,
    code,
    category: 'fund',
    // 新建基金默认股基（后端 SeriesService.DEFAULT_ASSET_TYPE 同款）
    assetType: payload.assetType ?? 'equity',
    active: payload.action === 1,
    archived: false,
    frequency: payload.frequency ?? 'monthly',
    amount: Number(payload.amount),
    startDate: payload.date.slice(0, 7),
    principal,
    current: principal,
    fee: 0,
    annualizedRate: null,
    lastUpdateTime: new Date().toISOString().slice(0, 19),
    series: [{ day: payload.date, principal, total: principal, fee: 0 }],
    adjustments: [record],
  }
  db.funds.push(fund)
  return fund
}

/** 校验并落库一条调整记录，返回后端视图对象；有对应 code 的基金时同步进 fixture 列表 */
function createAdjustment(payload: AdjustmentPayload) {
  validateAdjustmentPayload(payload)
  adjustmentSeq += 1
  const record = toRecord(String(adjustmentSeq), payload)
  ensureFundForRecord(payload.code.trim(), payload, record)
  return {
    adjustmentId: adjustmentSeq,
    userId: 1,
    code: payload.code.trim(),
    date: record.date,
    reason: record.reason || null,
    action: payload.action,
    frequency: payload.frequency ?? null,
    amount: Number(payload.amount),
    note: record.note ?? null,
  }
}

/** 按记录 id 在 fixture 里找记录（mock 的记录 id 是 'a1' 这种字符串） */
function requireAdjustment(id: string) {
  for (const fund of db.funds) {
    const record = fund.adjustments.find((a) => a.id === id)
    if (record) return { fund, record }
  }
  throw new ApiError(404, `调整记录不存在: ${id}`)
}

/** 单条记录 → 后端视图（编辑表单只回填日期/动作/金额等字段，不关心 adjustmentId 的类型） */
function adjustmentDetail(id: string) {
  const { fund, record } = requireAdjustment(id)
  return {
    adjustmentId: record.id,
    userId: 1,
    code: fund.code,
    date: record.date,
    reason: record.reason || null,
    action: ACTION_CODES[record.action] ?? 1,
    frequency: record.frequency ?? null,
    amount: record.amount ?? 0,
    note: record.note ?? null,
  }
}

/** 整条更新：mock 不重算序列，只改这一条记录本身（对应后端 PUT /adjustments/{id}） */
function updateAdjustmentRecord(id: string, payload: AdjustmentPayload) {
  validateAdjustmentPayload(payload)
  const { fund, record } = requireAdjustment(id)
  Object.assign(record, toRecord(id, payload))
  // 后端 PUT 也会写 asset_type（applyAssetType），mock 保持同语义：传了才改
  if (payload.assetType) fund.assetType = payload.assetType
  return adjustmentDetail(id)
}

/** 删除记录：从 fixture 列表里摘掉（对应后端 DELETE /adjustments/{id}，成功时 data 为 null） */
function deleteAdjustmentRecord(id: string) {
  const { fund, record } = requireAdjustment(id)
  fund.adjustments.splice(fund.adjustments.indexOf(record), 1)
  return null
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
  // 生成序列：mock 的序列是写死的，重算没有意义，只回报现有行数（对应后端 POST /funds/{id}/series）
  {
    method: 'POST',
    pattern: '/funds/:id/series',
    handle: ({ params }) => requireFund(params.id).series.length,
  },
  // 一键更新全部序列：同样只回报资产数（对应后端 POST /funds/series；段数与 /funds/:id/series 不同，不冲突）
  { method: 'POST', pattern: '/funds/series', handle: () => db.funds.length },
  // 组合总览
  { method: 'GET', pattern: '/portfolio/series', handle: () => portfolioSeries() },
  // 资产分析
  { method: 'GET', pattern: '/assets/summary', handle: () => assetSummary() },
  { method: 'GET', pattern: '/assets/distribution', handle: ({ query }) => distribution(query.dim) },
  // 存钱计划
  { method: 'GET', pattern: '/savings/plans/:id', handle: ({ params }) => requireSavingsPlan(params.id) },
  { method: 'GET', pattern: '/savings/plans', handle: () => listSavingsPlans() },
  // 录入数据：新建 / 单条查询 / 整条更新 / 删除（详情页的编辑与删除用它）
  {
    method: 'POST',
    pattern: '/adjustments',
    handle: ({ body }) => createAdjustment(body as AdjustmentPayload),
  },
  { method: 'GET', pattern: '/adjustments/:id', handle: ({ params }) => adjustmentDetail(params.id) },
  {
    method: 'PUT',
    pattern: '/adjustments/:id',
    handle: ({ params, body }) => updateAdjustmentRecord(params.id, body as AdjustmentPayload),
  },
  { method: 'DELETE', pattern: '/adjustments/:id', handle: ({ params }) => deleteAdjustmentRecord(params.id) },
]
