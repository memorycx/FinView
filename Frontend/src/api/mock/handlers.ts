import type { AllocationBucket, DistributionData, DistributionDim, PortfolioSeries, Fund, FundMarket, FundType, FundUpdatePayload, AdjustmentAction, AdjustmentPayload, PlanAdjustment, SavingsAsset, SavingsPlan, SavingsPlanPayload, CashFlowForecast, CashFlowCalendar, InvestFrequency, Panorama } from '../types'
import { ApiError } from '../error'
import { targetDateFromMonthly } from '@/lib/finance'
import { db, planAssetLinks, planTypes } from './db'

/** 未定投的现金/货币基金储备（元） */
const CASH_RESERVE = 128000

/** 展示桶中文名，与后端 AssetService.BUCKET_LABELS 一一对应（基金按 assetType 细分成两个桶） */
const BUCKET_LABELS: Record<AllocationBucket, string> = {
  equityFund: '股票基金',
  bondFund: '债券基金',
  moneyFund: '货币基金',
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

/**
 * 编辑基金基础信息（对应后端 PUT /funds/{id}）：只改名称 / 类型 / 费率 / 标的市场。
 * mock 的序列是写死的，费率 / 市场变化不重算（真后端会整段重算 asset_series）。
 */
function updateFundMetadata(id: string, body: FundUpdatePayload): Fund {
  const fund = requireFund(id)
  const name = body.name?.trim()
  if (!name) throw new ApiError(400, '基金名称不能为空')
  if (name.length > 100) throw new ApiError(400, '基金名称不能超过 100 个字符')

  const assetType = body.assetType?.toLowerCase() as FundType | undefined
  if (!assetType || !['equity', 'bond', 'money'].includes(assetType)) {
    throw new ApiError(400, '基金类型必须是 equity/bond/money')
  }

  const rate = body.rate ?? null
  if (rate !== null && (!Number.isFinite(rate) || rate < 0 || rate > 100)) {
    throw new ApiError(400, '申购费率需在 0~100 之间')
  }

  const rawMarket = body.market == null ? '' : String(body.market).trim().toLowerCase()
  if (rawMarket && rawMarket !== 'us' && rawMarket !== 'hk') {
    throw new ApiError(400, '标的市场必须是 us/hk')
  }
  const market = (rawMarket || null) as FundMarket | null

  fund.name = name
  fund.assetType = assetType
  fund.rate = rate
  fund.market = market
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
    moneyFund: 0,
    stock: 0,
    bond: 0,
    cash: 0,
  }
  for (const f of unarchivedFunds()) {
    if (f.category === 'fund') {
      base[f.assetType === 'bond' ? 'bondFund' : f.assetType === 'money' ? 'moneyFund' : 'equityFund'] += f.current
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
    // 安全资金 = 债基 + 货币基金 + 现金（与后端 AssetService.SAFE_ASSET_TYPES 一致）
    safeAssets: CASH_RESERVE + base.bondFund + base.moneyFund,
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

/** mock 里的现金资产：真后端是 asset 表里 code=CASH 的那一行，进计划的口径一样 */
const MOCK_CASH = { id: 'mock-cash', code: 'CASH', name: '现金', amount: CASH_RESERVE }

/** 资产行在列表里的配色轮转，与后端 SavingsService.ASSET_COLORS 一致 */
const ASSET_COLOR_VARS = ['--chart-1', '--chart-3', '--chart-4', '--chart-5']

/**
 * 计划里的资产（口径照抄后端 SavingsService.assetsOf）：
 * 总资产计划 = 全部未归档资产（含现金）；生活保障 / 愿望 = planAssetLinks 里显式关联的。
 * 与真后端一样**不含已归档**。
 */
function savingsPlanAssets(plan: SavingsPlan): SavingsAsset[] {
  const coversAll = plan.type === 'asset'
  const linked = new Set(planAssetLinks[plan.id] ?? [])
  // 每只基金带上自己的四件套（fixture 里本来就有这四个数）；现金给 null（没有收益口径）
  const rows: Omit<SavingsAsset, 'colorVar'>[] = db.funds
    .filter((f) => !f.archived && (coversAll || linked.has(f.id)))
    .map((f) => ({
      id: f.id,
      code: f.code,
      name: f.name,
      amount: f.current,
      returnStats: {
        current: f.current,
        principal: f.principal,
        fee: f.fee,
        invested: f.invested ?? f.principal + f.fee,
      },
      // 与真后端 detail 响应同口径：定投状态取自基金计划
      dcaActive: f.active,
      dcaFrequency: f.active ? f.frequency : null,
      dcaAmount: f.active ? f.amount : null,
    }))
  if (coversAll || linked.has(MOCK_CASH.id)) {
    rows.push({
      ...MOCK_CASH,
      returnStats: null,
      dcaActive: false,
      dcaFrequency: null,
      dcaAmount: null,
    })
  }
  // 稳定排序让 mock 与真后端一致：定投中的在前面（颜色先分配，排序不挪颜色）
  return rows
    .map((a, i) => ({ ...a, colorVar: ASSET_COLOR_VARS[i % ASSET_COLOR_VARS.length] }))
    .sort((a, b) => Number(Boolean(b.dcaActive)) - Number(Boolean(a.dcaActive)))
}

/**
 * 读取计划时的统一视图：assets / currentAmount / returnStats 都是算出来的
 * （fixture 里写的 assets: [] / currentAmount: 0 只是占位，别当成数据）。
 *
 * returnStats 的口径照抄后端 SavingsService.returnStats：**只统计非现金资产**，
 * 四个数同进同出（currentAmount 含现金、它不含，是故意的）。
 */
function planView(plan: SavingsPlan): SavingsPlan {
  const assets = savingsPlanAssets(plan)
  const returnStats = planReturnStats(plan)
  return {
    ...plan,
    // 类型显示名现算：改名后不用回头改计划（真后端同理，计划里存的只是 code）
    typeName: planTypes.find((t) => t.code === plan.type)?.name ?? plan.type,
    assets,
    currentAmount: assets.reduce((sum, a) => sum + a.amount, 0),
    returnStats,
  }
}

/* ==================== 计划类型（内置 + 自定义） ==================== */

let planTypeSeq = 0

function requirePlanType(id: string) {
  const type = planTypes.find((t) => t.id === id)
  if (!type) throw new ApiError(404, `计划类型不存在: ${id}`)
  return type
}

function requireTypeNameFree(name: string, excludeId?: string) {
  if (!name) throw new ApiError(400, '类型名称不能为空')
  if (name.length > 32) throw new ApiError(400, '类型名称最多 32 个字')
  if (planTypes.some((t) => t.name === name && t.id !== excludeId)) {
    throw new ApiError(400, `已有同名类型：${name}`)
  }
}

function createPlanType(name: string) {
  const trimmed = (name ?? '').trim()
  requireTypeNameFree(trimmed)
  planTypeSeq += 1
  const item = {
    id: `type-custom-${planTypeSeq}`,
    code: `c${Date.now().toString(36)}${planTypeSeq}`,
    name: trimmed,
    builtin: false,
    system: false,
  }
  planTypes.push(item)
  return item
}

function renamePlanType(id: string, name: string) {
  const type = requirePlanType(id)
  if (type.system) throw new ApiError(400, '总资产类型不能改名')
  const trimmed = (name ?? '').trim()
  requireTypeNameFree(trimmed, id)
  type.name = trimmed
  return type
}

function deletePlanType(id: string) {
  const type = requirePlanType(id)
  if (type.system) throw new ApiError(400, '总资产类型不能删除')
  const inUse = db.savingsPlans.filter((plan) => plan.type === type.code).length
  if (inUse > 0) {
    throw new ApiError(400, `还有 ${inUse} 个计划在用这个类型，先把它们改成别的类型再删`)
  }
  planTypes.splice(planTypes.indexOf(type), 1)
  return null
}

/** 非现金资产的收益四件套（mock 的 Fund fixture 里本来就有这四个数） */
function planReturnStats(plan: SavingsPlan) {
  const coversAll = plan.type === 'asset'
  const linked = new Set(planAssetLinks[plan.id] ?? [])
  const funds = db.funds.filter(
    (f) => !f.archived && !f.code.toUpperCase().startsWith('CASH') && (coversAll || linked.has(f.id)),
  )
  return funds.reduce(
    (sum, f) => ({
      current: sum.current + f.current,
      principal: sum.principal + f.principal,
      fee: sum.fee + f.fee,
      invested: sum.invested + (f.invested ?? f.principal + f.fee),
    }),
    { current: 0, principal: 0, fee: 0, invested: 0 },
  )
}

/** 资产 → 所属计划（一一对应，2026-10-13 起；总资产计划不走关联表） */
function planOfAsset(assetId: string): SavingsPlan | null {
  for (const [planId, ids] of Object.entries(planAssetLinks)) {
    if (ids.includes(assetId)) {
      return db.savingsPlans.find((p) => p.id === planId) ?? null
    }
  }
  return null
}

/** 排他校验：一个资产只能进一个具体分类（与后端 SavingsService.requireAssetNotInOtherPlan 同文案） */
function requireAssetNotInOtherPlan(plan: SavingsPlan, assetName: string, assetId: string) {
  const owner = planOfAsset(assetId)
  if (owner && owner.id !== plan.id) {
    throw new ApiError(400, `「${assetName}」已经在「${owner.name}」里了，一个资产只能属于一个分类`)
  }
}

/** 候选列表的公共部分：db.funds + 一笔现金，带上是否已关联（总资产计划恒 true）与「已在别的计划」 */
function savingsCandidatesOf(coversAll: boolean, linked: Set<string>, planId: string | null) {
  const withPlan = (id: string, name: string, base: Record<string, unknown>) => {
    const owner = planOfAsset(id)
    return {
      ...base,
      linked: coversAll || linked.has(id),
      // 已在别的分类里时带上那个计划的名字，前端据此禁用这一行
      linkedPlanName: owner && owner.id !== planId ? owner.name : null,
    }
  }
  return [
    ...db.funds.map((f) =>
      withPlan(f.id, f.name, { id: f.id, code: f.code, name: f.name, amount: f.current, archived: !!f.archived }),
    ),
    withPlan(MOCK_CASH.id, MOCK_CASH.name, {
      id: MOCK_CASH.id,
      code: MOCK_CASH.code,
      name: MOCK_CASH.name,
      amount: MOCK_CASH.amount,
      archived: false,
    }),
  ]
}

/** 「添加资产」面板的候选 */
function savingsCandidates(plan: SavingsPlan) {
  return savingsCandidatesOf(plan.type === 'asset', new Set(planAssetLinks[plan.id] ?? []), plan.id)
}

/** 新建表单的候选（还没有计划，任何已有归属都算"别处"） */
function savingsCandidatesAll() {
  return savingsCandidatesOf(false, new Set(), null)
}

function linkPlanAsset(planId: string, assetId: string): SavingsPlan {
  const plan = requireSavingsPlan(planId)
  if (plan.type === 'asset') {
    throw new ApiError(400, '总资产计划默认包含全部未归档资产，不需要手动关联')
  }
  const fund = db.funds.find((f) => f.id === assetId)
  if (!fund && assetId !== MOCK_CASH.id) throw new ApiError(404, `资产不存在: ${assetId}`)
  if (fund?.archived) throw new ApiError(400, '已归档的资产不计入计划，先取消归档再关联')
  requireAssetNotInOtherPlan(plan, fund?.name ?? MOCK_CASH.name, assetId)
  const links = (planAssetLinks[plan.id] ??= [])
  // 幂等：已经关联过就不重复加（真后端靠 plan_asset 的唯一键 + INSERT IGNORE）
  if (!links.includes(assetId)) links.push(assetId)
  return planView(plan)
}

function unlinkPlanAsset(planId: string, assetId: string): SavingsPlan {
  const plan = requireSavingsPlan(planId)
  const links = planAssetLinks[plan.id] ?? []
  const index = links.indexOf(assetId)
  if (index >= 0) links.splice(index, 1)
  return planView(plan)
}

function listSavingsPlans() {
  return db.savingsPlans.map(planView)
}

function requireSavingsPlan(id: string): SavingsPlan {
  const plan = db.savingsPlans.find((p) => p.id === id)
  if (!plan) throw new ApiError(404, `存钱计划不存在: ${id}`)
  return plan
}

/** Mock 自增 ID（fixture 用的是 'total-asset' / 'japan-trip' 这类语义串，这里用前缀区分开） */
let savingPlanSeq = 0

/**
 * 校验规则与后端 SavingsPlanRequest 的注解 + SavingsService.validateNewPlan 一一对应，
 * 消息也照抄 —— mock 下踩到的错误和真后端要长得一样。
 *
 * 目标金额可选（2026-10-05）：不传 = 无目标计划，此时目标日期 / 每月投入一起被忽略，
 * 且 type 只能是 wish（总资产计划必须有目标）。
 */
function validateSavingsPlanPayload(payload: SavingsPlanPayload) {
  if (!payload || typeof payload !== 'object') throw new ApiError(400, '请求体不能为空')
  if (!payload.name?.trim()) throw new ApiError(400, '计划名称不能为空')
  if (payload.name.length > 100) throw new ApiError(400, '计划名称长度不能超过 100')
  // 类型必须是该用户类型表里的 code（内置三种已在 fixture 里，自定义的由用户先建）
  if (!planTypes.some((t) => t.code === payload.type)) {
    throw new ApiError(400, `计划类型不存在，请先在类型里新建：${payload.type}`)
  }
  if (!payload.startDate) throw new ApiError(400, '开始日期不能为空')

  const hasTarget = payload.targetAmount != null
  if (payload.type === 'asset' && !hasTarget) {
    throw new ApiError(400, '总资产计划需要设定目标金额')
  }
  if (hasTarget) {
    if (!(Number(payload.targetAmount) > 0)) throw new ApiError(400, '目标金额必须大于 0')
    if (!(Number(payload.monthlyPlanAmount) > 0)) throw new ApiError(400, '设了目标金额就要填每月计划投入')
    // 目标日期选填（2026-10-13 起）：没填时下面 createSavingsPlan 会自适应推算；填了才校验顺序
    if (payload.targetDate && payload.targetDate < payload.startDate) {
      throw new ApiError(400, '目标日期不能早于开始日期')
    }
  }
  if (payload.type === 'asset' && db.savingsPlans.some((p) => p.type === 'asset')) {
    throw new ApiError(400, '已有总资产计划，只能新建生活保障或愿望')
  }
}

/** 新建计划：返回与列表 / 详情同构的对象 —— id 是字符串、assets / series 给空数组 */
function createSavingsPlan(payload: SavingsPlanPayload): SavingsPlan {
  validateSavingsPlanPayload(payload)
  savingPlanSeq += 1
  const hasTarget = payload.targetAmount != null
  const plan: SavingsPlan = {
    id: `mock-plan-${savingPlanSeq}`,
    name: payload.name.trim(),
    // 类型里 description 是必填 string（后端 read DTO 也从不返回 null）
    description: payload.description?.trim() || '',
    type: payload.type,
    // 显示名在 planView 里按 code 现算；这里先按同一个口径填一份（TS 要求字段齐全）
    typeName: planTypes.find((t) => t.code === payload.type)?.name ?? payload.type,
    image: payload.image?.trim() || undefined,
    // 新建计划的市值恒为 0，进度等关联资产（后面 planView 会按关联的基金现算）
    currentAmount: 0,
    // 无目标计划这三个字段都是 null（与真后端一致）
    targetAmount: hasTarget ? Number(payload.targetAmount) : null,
    startDate: payload.startDate,
    // 目标日期选填：没填就按每月存入自适应推算（口径与后端 estimatedTargetDate 一致）
    targetDate: hasTarget
      ? (payload.targetDate ||
          targetDateFromMonthly(payload.startDate, Number(payload.targetAmount), Number(payload.monthlyPlanAmount)))
      : '',
    monthlyPlanAmount: hasTarget ? Number(payload.monthlyPlanAmount) : null,
    quote: payload.quote?.trim() || undefined,
    note: payload.note?.trim() || undefined,
    assets: [],
    series: [],
  }
  db.savingsPlans.push(plan)

  // 表单里勾的资产：校验后写进关联表（总资产计划忽略该字段，它本来就全含）
  if (plan.type !== 'asset' && payload.assetIds?.length) {
    planAssetLinks[plan.id] = payload.assetIds.map((assetId) => {
      const fund = db.funds.find((f) => f.id === assetId)
      if (!fund && assetId !== MOCK_CASH.id) throw new ApiError(404, `资产不存在: ${assetId}`)
      if (fund?.archived) throw new ApiError(400, `已归档的资产不计入计划：${fund.code}`)
      // 与详情页「添加资产」同一条排他规则
      requireAssetNotInOtherPlan(plan, fund?.name ?? MOCK_CASH.name, assetId)
      return assetId
    })
  }
  return planView(plan)
}

/** 新建表单用的候选（还没有计划，linked 恒 false） */

/* ==================== 资产调整记录（录入数据） ==================== */

/** Mock 自增 ID */
let adjustmentSeq = 0

/** 动作码 → 时间线文案（mock 的 fund.adjustments 存的是展示文案，不是动作码） */
const ACTION_LABELS: Record<AdjustmentAction, string> = {
  1: '定投开始',
  2: '结束定投',
  3: '买入',
  4: '卖出',
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
  买入: 3,
  卖出: 4,
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
  // 类型只认 equity / bond / money（cash 由保留 code CASH 系统维护，不接受前端指定）
  if (payload.assetType && !['equity', 'bond', 'money'].includes(payload.assetType)) {
    throw new ApiError(400, '资产类型必须是 equity/bond/money')
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
    // deferred 只属于 action=1（定投开始 / 调整定投），其余动作归一成 false
    deferred: payload.action === 1 && !!payload.deferred,
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
  // 定投开始 / 买入会让市值跟着本金走；结束定投与卖出在 mock 里按 0 处理，够展示用
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
    deferred: record.deferred ?? false,
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
    deferred: record.deferred ?? false,
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

/* ==================== 定投分析（现金流预测） ==================== */

/** 默认「每月固定新增」的 mock 值：真后端是 user.monthly_inflow（NULL = 未设置），mock 里会话内可变 */
let mockMonthlyInflow: number | null = 3000

/** 单月覆盖的 mock 值：真后端是 monthly_inflow_override 表（month 'YYYY-MM' → 金额），会话内可变 */
const mockMonthlyInflowOverrides = new Map<string, number>()

/** 金额归到分（与后端 12 位小数四舍五入的另一半对齐，mock 简单些） */
function round2(value: number): number {
  return Math.round(value * 100) / 100
}

/** 未来 12 个整月（与后端 CashFlowService.windowStart/windowEnd 同口径：从下月 1 日起算） */
function forecastMonths(): { year: number; month: number }[] {
  const now = new Date()
  return Array.from({ length: 12 }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() + 1 + i, 1)
    return { year: d.getFullYear(), month: d.getMonth() + 1 }
  })
}

/** 某个频率的定投在某天扣不扣；mock 没有交易日历，按「周一~周五」近似（顺延不模拟） */
function deductsOn(frequency: InvestFrequency, year: number, month: number, day: number): boolean {
  const week = new Date(year, month - 1, day).getDay()
  if (frequency === 'daily') return week !== 0 && week !== 6
  if (frequency === 'weekly') return week === 1
  if (frequency === 'monthly') return day === 1
  return false
}

/** 某只基金在某月的扣款合计 */
function deductionInMonth(frequency: InvestFrequency, amount: number, year: number, month: number): number {
  const daysInMonth = new Date(year, month, 0).getDate()
  let count = 0
  for (let day = 1; day <= daysInMonth; day++) {
    if (deductsOn(frequency, year, month, day)) count++
  }
  return count * amount
}

/** 本月的 YYYY-MM */
function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 月份加减（YYYY-MM，delta 单位是月） */
function shiftMonth(month: string, delta: number): string {
  const [year, m] = month.split('-').map(Number)
  const total = year * 12 + (m - 1) + delta
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}

/**
 * 与后端 CashFlowService.calendar 同口径：某月每天的周期扣款金额与执行计划数，
 * 外加本月总览（合计 / 执行天数 / 基金数 / 日均）、每只基金合计、周一~周日分布。
 */
function cashFlowCalendar(month?: string): CashFlowCalendar {
  const target = /^\d{4}-(0[1-9]|1[0-2])$/.test(month ?? '') ? (month as string) : currentMonth()
  const [year, m] = target.split('-').map(Number)
  const daysInMonth = new Date(year, m, 0).getDate()
  const activeFunds = unarchivedFunds().filter((f) => f.active && f.frequency)

  const byCode = new Map<string, number>()
  const weekdays = Array.from({ length: 7 }, () => 0)
  const days: CashFlowCalendar['days'] = []
  let total = 0
  for (let day = 1; day <= daysInMonth; day++) {
    const dayFunds: { code: string; amount: number }[] = []
    for (const f of activeFunds) {
      if (deductsOn(f.frequency as InvestFrequency, year, m, day)) {
        dayFunds.push({ code: f.code, amount: f.amount })
        byCode.set(f.code, round2((byCode.get(f.code) ?? 0) + f.amount))
      }
    }
    // 与后端一致：按金额降序，并列按 code 升序
    dayFunds.sort((a, b) => b.amount - a.amount || a.code.localeCompare(b.code))
    const amount = round2(dayFunds.reduce((sum, f) => sum + f.amount, 0))
    const iso = `${target}-${String(day).padStart(2, '0')}`
    days.push({ day: iso, amount, plans: dayFunds.length, funds: dayFunds })
    total = round2(total + amount)
    const weekday = (new Date(year, m - 1, day).getDay() + 6) % 7   // 周一 = 0
    weekdays[weekday] = round2(weekdays[weekday] + amount)
  }

  const executedDays = days.filter((d) => d.amount > 0).length
  // 年度分布：所选月份那一年的 1~12 月，外加年度口径的三个统计（与后端同口径）
  const monthsOfYear = Array.from({ length: 12 }, (_, i) =>
    round2(activeFunds.reduce((sum, f) => sum + deductionInMonth(f.frequency as InvestFrequency, f.amount, year, i + 1), 0)),
  )
  const yearTotal = round2(monthsOfYear.reduce((sum, v) => sum + v, 0))
  const yearDays = new Set<string>()
  const yearCodes = new Set<string>()
  for (let mth = 1; mth <= 12; mth++) {
    const dim = new Date(year, mth, 0).getDate()
    for (let day = 1; day <= dim; day++) {
      for (const f of activeFunds) {
        if (deductsOn(f.frequency as InvestFrequency, year, mth, day)) {
          yearDays.add(`${year}-${mth}-${day}`)
          yearCodes.add(f.code)
        }
      }
    }
  }
  const yearExecutedDays = yearDays.size
  const yearFundCount = yearCodes.size
  const yearDailyAverage = yearExecutedDays === 0 ? 0 : round2(yearTotal / yearExecutedDays)
  const funds = [...byCode.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([code, amount], i) => ({
      code,
      name: db.funds.find((f) => f.code === code)?.name ?? code,
      amount,
      share: total === 0 ? 0 : Math.round((amount / total) * 10000) / 10000,
      colorVar: ASSET_COLOR_VARS[i % ASSET_COLOR_VARS.length],
    }))

  return {
    month: target,
    minMonth: shiftMonth(currentMonth(), -12),
    maxMonth: shiftMonth(currentMonth(), 12),
    total,
    executedDays,
    fundCount: funds.length,
    dailyAverage: executedDays === 0 ? 0 : round2(total / executedDays),
    days,
    funds,
    weekdays,
    monthsOfYear,
    yearTotal,
    yearExecutedDays,
    yearFundCount,
    yearDailyAverage,
  }
}

/**
 * 与后端 CashFlowService.project 同口径：窗口 = 下月起 12 个整月；
 * 每月新增 = 单月覆盖（有就用）?? 默认值；支出按实际扣款日归月；
 * 每月定投总额 = 合计 ÷ 12，末月余额钉到 projectedCash，
 * 保证卡片上「现金 + 新增合计 − 12×总额 = 12个月后」精确成立。
 */
function cashFlowForecast(): CashFlowForecast {
  const base = mockMonthlyInflow ?? 0
  const activeFunds = unarchivedFunds().filter((f) => f.active && f.frequency)
  const rows = forecastMonths().map(({ year, month }) => {
    const key = `${year}-${String(month).padStart(2, '0')}`
    const overridden = mockMonthlyInflowOverrides.has(key)
    return {
      month: key,
      inflow: overridden ? mockMonthlyInflowOverrides.get(key) ?? 0 : base,
      overridden,
      outlay: activeFunds.reduce(
        (sum, f) => sum + deductionInMonth(f.frequency as InvestFrequency, f.amount, year, month),
        0,
      ),
    }
  })

  const totalOutlay = round2(rows.reduce((sum, row) => sum + row.outlay, 0))
  const totalInflow = round2(rows.reduce((sum, row) => sum + row.inflow, 0))
  const monthlyOutlay = round2(totalOutlay / 12)
  const projectedCash = round2(CASH_RESERVE + totalInflow - monthlyOutlay * 12)

  let balance = CASH_RESERVE
  const months = rows.map((row, i) => {
    balance = round2(balance + row.inflow - row.outlay)
    return { ...row, endBalance: i === rows.length - 1 ? projectedCash : balance }
  })

  return {
    currentCash: CASH_RESERVE,
    monthlyInflow: mockMonthlyInflow,
    monthlyOutlay,
    projectedCash,
    totalOutlay,
    fromMonth: months[0].month,
    toMonth: months[months.length - 1].month,
    calendarCoverageEnd: null,
    months,
  }
}

/** 校验「每月固定新增」入参（null 合法 = 清空/恢复默认），与后端 MonthlyInflowRequest 一致 */
function parseMonthlyInflow(body: unknown): number | null {
  const raw = (body as { monthlyInflow?: number | null }).monthlyInflow
  if (raw !== null && raw !== undefined
      && (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 0)) {
    throw new ApiError(400, '每月固定新增不能为负数')
  }
  return raw ?? null
}

/* ==================== 资金全景（3D 塔视图） ==================== */

/** 桶内资产配色轮转（与真后端 PanoramaService.ASSET_COLORS 一致） */
const PANORAMA_COLORS = ['--chart-1', '--chart-3', '--chart-4', '--chart-5', '--chart-6']

/** mock 的桶划分：与 AssetService.bucketOf 同口径（基金按 assetType 细分） */
function mockBucketOf(category: string | null | undefined, assetType: string | null | undefined): string {
  if (category === 'cash') return 'cash'
  if (category !== 'fund') return category ?? 'fund'
  if (assetType === 'bond') return 'bondFund'
  if (assetType === 'money') return 'moneyFund'
  return 'equityFund'
}

/**
 * 资金全景：桶 + 未来 12 个自然月的累计投入（口径照抄后端 PanoramaService：
 * 从**明天**起算、只算周期扣款、现金恒 0）。
 */
function panorama(): Panorama {
  const now = new Date()
  const months: string[] = []
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() + i, 1)
    months.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }

  // 逐只资产：当月只算「今天之后」的扣款日，之后按月整体算
  const cumulativeOf = (fundId: string | null): number[] => {
    const fund = fundId ? db.funds.find((f) => f.id === fundId) : null
    if (!fund || !fund.active || !fund.frequency) {
      return Array.from({ length: 12 }, () => 0)
    }
    const result: number[] = []
    let running = 0
    months.forEach((month, i) => {
      const [year, monthValue] = month.split('-').map(Number)
      let amount = 0
      const dim = new Date(year, monthValue, 0).getDate()
      const startDay = i === 0 ? now.getDate() + 1 : 1
      for (let day = startDay; day <= dim; day++) {
        if (deductsOn(fund.frequency as InvestFrequency, year, monthValue, day)) amount += fund.amount
      }
      running = round2(running + amount)
      result.push(running)
    })
    return result
  }

  const rows = unarchivedFunds().map((f) => ({
    key: mockBucketOf(f.category, f.assetType),
    code: f.code,
    name: f.name,
    value: round2(f.current),
    cumulative: cumulativeOf(f.id),
  }))
  rows.push({
    key: 'cash',
    code: MOCK_CASH.code,
    name: MOCK_CASH.name,
    value: MOCK_CASH.amount,
    cumulative: Array.from({ length: 12 }, () => 0),
  })

  const byBucket = new Map<string, typeof rows>()
  for (const row of rows) {
    if (row.value === 0 && (row.cumulative[11] ?? 0) === 0) continue
    const list = byBucket.get(row.key) ?? []
    list.push(row)
    byBucket.set(row.key, list)
  }

  const buckets = [...byBucket.entries()]
    .map(([key, list]) => {
      const assets = [...list].sort((a, b) => b.value - a.value || a.code.localeCompare(b.code))
      return {
        key,
        label: BUCKET_LABELS[key as keyof typeof BUCKET_LABELS] ?? key,
        value: round2(assets.reduce((sum, a) => sum + a.value, 0)),
        assets: assets.map((a, i) => ({ ...a, colorVar: PANORAMA_COLORS[i % PANORAMA_COLORS.length] })),
      }
    })
    .sort((a, b) => b.value - a.value)

  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    asOf: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`,
    months,
    buckets,
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
  // 编辑基金基础信息（定投详情页的「编辑」按钮，对应后端 PUT /funds/{id}）
  {
    method: 'PUT',
    pattern: '/funds/:id',
    handle: ({ params, body }) => updateFundMetadata(params.id, body as FundUpdatePayload),
  },
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
  // 存钱计划（详情与列表的 assets / currentAmount / returnStats 都是 planView 现算的）
  // 注意顺序：mock 是按「方法 + 段数」先匹配先赢，/savings/plans/candidates 与
  // /savings/plans/:id 都是 3 段，字面量那条必须排在前面（真后端靠 Spring 的优先级，不受影响）
  { method: 'GET', pattern: '/savings/plans/candidates', handle: () => savingsCandidatesAll() },
  {
    method: 'GET',
    pattern: '/savings/plans/:id/candidates',
    handle: ({ params }) => savingsCandidates(requireSavingsPlan(params.id)),
  },
  { method: 'GET', pattern: '/savings/plans/:id', handle: ({ params }) => planView(requireSavingsPlan(params.id)) },
  { method: 'GET', pattern: '/savings/plans', handle: () => listSavingsPlans() },
  // 计划类型（内置 + 自定义）：字面量段与 /savings/plans 不同，不会互相抢路由
  { method: 'GET', pattern: '/savings/plan-types', handle: () => planTypes },
  {
    method: 'POST',
    pattern: '/savings/plan-types',
    handle: ({ body }) => createPlanType((body as { name: string }).name),
  },
  {
    method: 'PUT',
    pattern: '/savings/plan-types/:id',
    handle: ({ params, body }) => renamePlanType(params.id, (body as { name: string }).name),
  },
  {
    method: 'DELETE',
    pattern: '/savings/plan-types/:id',
    handle: ({ params }) => deletePlanType(params.id),
  },
  {
    method: 'POST',
    pattern: '/savings/plans',
    handle: ({ body }) => createSavingsPlan(body as SavingsPlanPayload),
  },
  {
    method: 'POST',
    pattern: '/savings/plans/:id/assets',
    handle: ({ params, body }) => linkPlanAsset(params.id, (body as { assetId: string }).assetId),
  },
  {
    method: 'DELETE',
    pattern: '/savings/plans/:id/assets/:assetId',
    handle: ({ params }) => unlinkPlanAsset(params.id, params.assetId),
  },
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
  // 定投分析：现金流预测 + 每月固定新增（PUT 返回重算后的整份预测，与真后端一致）
  { method: 'GET', pattern: '/cashflow/forecast', handle: () => cashFlowForecast() },
  // 资金全景：3D 塔视图的数据（桶 + 未来 12 个月累计投入）
  { method: 'GET', pattern: '/visualization/panorama', handle: () => panorama() },
  // 定投日历：?month=YYYY-MM（不传 = 当前月）
  { method: 'GET', pattern: '/cashflow/calendar', handle: ({ query }) => cashFlowCalendar(query.month) },
  {
    method: 'PUT',
    pattern: '/cashflow/monthly-inflow',
    handle: ({ body }) => {
      mockMonthlyInflow = parseMonthlyInflow(body)
      return cashFlowForecast()
    },
  },
  // 单月覆盖（点柱状图的柱子）；金额 null = 恢复默认。段数比上面多一段，不冲突
  {
    method: 'PUT',
    pattern: '/cashflow/monthly-inflow/:month',
    handle: ({ params, body }) => {
      const month = params.month
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new ApiError(400, '月份格式必须是 YYYY-MM')
      const value = parseMonthlyInflow(body)
      if (value === null) mockMonthlyInflowOverrides.delete(month)
      else mockMonthlyInflowOverrides.set(month, value)
      return cashFlowForecast()
    },
  },
]
