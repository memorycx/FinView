/**
 * 领域模型与接口出入参类型（前后端共享的接口契约）
 */

/* ==================== 通用 ==================== */

/** 定投计划列表筛选：active = 未归档（持有中），archived = 已归档 */
export type FundStatus = 'active' | 'archived' | 'all'

/** 资产分布维度 */
export type DistributionDim = 'industry' | 'region' | 'currency'

/* ==================== 定投计划（基金/持仓） ==================== */

/** 资产大类（asset.category）：fund 不再细分，股基/债基看下面的 FundType */
export type AssetCategory = 'fund' | 'stock' | 'bond' | 'cash'

/** 基金细分类型（asset.asset_type）：equity 股票基金 / bond 债券基金。cash 只属于保留 code CASH */
export type FundType = 'equity' | 'bond'

/**
 * 资产总览（/assets/summary）的展示桶 key：基金按 FundType 细分成股票基金 / 债券基金，
 * **不是 asset.category 原值**——两行同 key 会撞开 v-for 与取色表，所以后端派生后单列。
 */
export type AllocationBucket = 'equityFund' | 'bondFund' | 'stock' | 'bond' | 'cash'

/** 定投频率 */
export type InvestFrequency = 'daily' | 'weekly' | 'monthly'

/** 时间序列数据点（按日/月采样） */
export type DayPoint = {
  /** YYYY-MM-DD */
  day: string
  /** 累计投入本金（净投入：卖出已冲减，清过仓的基金可能为负） */
  principal: number
  /** 当前总市值 */
  total: number
  /** 当日手续费（买入按金额×费率收取，卖出为 0）；累计手续费由前端按日累加 */
  fee: number
  /**
   * 截至当日的累计投入 = Σ 历年买入的（净投入 + 手续费），卖出日不计入 —— 「收益率」的分母。
   * 后端一定会给；手写的 mock 序列没有它，缺省时按「本金 + 当日累计手续费」兜底（没清过仓时相等）
   */
  invested?: number
}

/** 组合总览（全部持有中持仓）：逐日汇总曲线 + 组合年化收益率 */
export type PortfolioSeries = {
  /** 按日期升序的本金/市值走势 */
  series: DayPoint[]
  /** 组合 XIRR 年化，小数：0.08 = 8%；null = 持有不足 30 天或无法计算 */
  annualizedRate: number | null
  /**
   * 组合的「最后更新时间」= 用户全部 asset 行（含已归档与现金）last_update_time 的 MAX，
   * ISO 8601（2026-10-02T14:33:12）；null = 一条资产都没有，页脚显示「—」
   */
  lastUpdateTime?: string | null
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
  /**
   * 基金细分类型（asset.asset_type），与 category 独立：category 仍是 fund。
   * null / 缺省 = 未标注（存量没标过的），按「股票基金」显示；
   * 现金不进 /funds，这个字段不会出现 'cash'
   */
  assetType?: FundType | null
  /** 定投是否进行中 */
  active: boolean
  /** 是否已归档（用户手工标记，与定投是否进行中无关；归档 = 移出「持有中」视图） */
  archived: boolean
  /** 当前定投频率 */
  frequency: InvestFrequency
  /** 当前每期定投金额 */
  amount: number
  /** 开始时间 YYYY-MM */
  startDate: string
  /** 累计投入本金（不含手续费的净本金） */
  principal: number
  /** 当前市值 */
  current: number
  /** 累计申购手续费（收益率/收益按「市值 − 本金 − 手续费」算，费算进成本） */
  fee: number
  /**
   * 累计投入 = Σ 历年买入的（净投入 + 手续费），卖出日不计入。
   * 收益率 =（市值 − 本金 − 手续费）/ 累计投入：清过仓的基金本金会变负，只能用它当分母；
   * 没清过仓时它恰好等于「本金 + 累计手续费」
   */
  invested?: number
  /**
   * 后端算的 XIRR 年化收益率，小数：0.08 = 8%。
   * null = 持有不足 30 天或数学上无解，看板显示「—」。
   * 与「累计收益率」不是一回事：那个是期末简单收益率，这个按每笔钱在场天数折年
   */
  annualizedRate: number | null
  /**
   * 最后更新时间（后端 asset.last_update_time）：该计划最后一次生成 / 重算序列的时刻，
   * ISO 8601（2026-10-02T14:33:12）；asset 行缺失时为 null，页脚显示「—」
   */
  lastUpdateTime?: string | null
  /** 本金/市值走势 */
  series: DayPoint[]
  /** 定投调整记录（按时间正序） */
  adjustments: PlanAdjustment[]
}

/* ==================== 资产调整记录（录入数据） ==================== */

/**
 * 资产调整动作：
 * - 1 定投开始
 * - 2 结束定投
 * - 3 一笔收入
 * - 4 一笔支出
 */
export type AdjustmentAction = 1 | 2 | 3 | 4

/** 新建调整记录入参（user_id 由后端从 token 解析，前端不传） */
export interface AdjustmentPayload {
  /** 资产编码 */
  code: string
  /**
   * 资产名称，选填。
   * 不落 adjustments 表，只用来更新 asset.name（自动生成的资产行默认拿 code 当名字）；
   * 留空则保持资产现有名字不变。
   */
  name?: string
  /**
   * 基金细分类型，选填。不落 adjustments 表，只写 asset.asset_type（新建的基金默认 equity）。
   * **只在用户动过类型控件时才带**：不传 = 不改已有分类，给一只债基录常规买入不会被翻回股基；
   * 现金（code=CASH）会被后端忽略（类型是系统语义）。
   */
  assetType?: FundType
  /** 记录日期 YYYY-MM-DD */
  date: string
  /** 变动原因 */
  reason?: string
  /** 动作：1定投开始 / 2结束定投 / 3一笔收入 / 4一笔支出 */
  action: AdjustmentAction
  /** 定投频率，仅 action=1（定投开始）时需要 */
  frequency?: InvestFrequency | null
  /** 金额 */
  amount: number
  /** 备注 */
  note?: string
}

/** 调整记录（后端返回） */
export interface Adjustment {
  /** 调整记录ID */
  adjustmentId: number
  /** 用户id */
  userId: number
  /** 资产编码 */
  code: string
  /** 记录日期 YYYY-MM-DD */
  date: string
  /** 变动原因 */
  reason: string | null
  /** 动作：1定投开始 / 2结束定投 / 3一笔收入 / 4一笔支出 */
  action: AdjustmentAction
  /** 频率 */
  frequency: string | null
  /** 金额 */
  amount: number
  /** 备注 */
  note: string | null
}

/* ==================== 资产分析 ==================== */

/** 资产大类配置项 */
export type Allocation = {
  category: AllocationBucket
  label: string
  /** 当前市值 */
  value: number
}

/** 资产总览（大类配置 + 总资产） */
export type AssetSummary = {
  /** 总资产（元） */
  totalAssets: number
  /**
   * 安全资金（元）= asset_type 为 bond（债基）/ cash（现金）的资产市值合计（后端算好，口径见 types /
   * AssetSummaryResponse）；「安全资金占比」= safeAssets / totalAssets。未标注类型按股票基金算，同样非安全
   */
  safeAssets: number
  /** 按展示桶的市值分布：恒返回 5 项（股票基金/债券基金/股票/债券/现金），无持仓的为 0 */
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

/**
 * 计划类型（三个并列，都存 saving_plans.type）：
 * asset 总资产（全账号唯一、必须带目标）/ life 生活保障 / wish 愿望。
 * 后两个原来合称「小愿望」，行为完全一样，只是标签不同。
 */
export type SavingsPlanType = 'asset' | 'life' | 'wish'

/** 计划内持有的资产项 */
export type SavingsAsset = {
  /** 资产 id（解除关联时用；也是列表渲染的 key —— 名字可以重复、也可能被改） */
  id: string
  /** 资产编码 */
  code: string
  name: string
  amount: number
  /** 图表主题色变量 */
  colorVar?: string
}

/** 「添加资产」面板里的一条候选（= 该用户的某项资产） */
export type SavingsPlanCandidate = {
  id: string
  code: string
  name: string
  /** 最新市值（元），口径与计划详情里的资产金额一致 */
  amount: number
  /** 是否已关联到本计划（总资产计划默认全含，恒为 true） */
  linked: boolean
  /** 是否已归档：已归档的不能新关联（已关联的仍要显示出来以便解除） */
  archived: boolean
}

/** 月度存入数据点 */
export type SavingsMonthPoint = {
  /** YYYY-MM */
  month: string
  amount: number
}

/**
 * 计划整体的收益四件套，字段名与「单只基金」的那四个一模一样（对应后端 SavingsReturnResponse），
 * 所以能直接传给 lib/finance.ts 的 returnRate()。
 * **四个数都只统计非现金资产**：现金没有收益，混进来只会稀释收益率。
 */
export type SavingsReturnStats = {
  /** 非现金资产的最新市值合计 */
  current: number
  /** 累计投入本金（净投入，清过仓可为负） */
  principal: number
  /** 累计手续费 */
  fee: number
  /** 累计投入（收益率的分母） */
  invested: number
}

/** 存钱计划 */
export type SavingsPlan = {
  id: string
  name: string
  description: string
  type: SavingsPlanType
  /** 详情页头图 URL（留空则占位） */
  image?: string
  /** 当前市值：计划内资产的最新市值合计（后端算的） */
  currentAmount: number
  /** 目标金额；null = 这个计划不设目标（只看这组资产的收益），此时 targetDate / monthlyPlanAmount 也是 null */
  targetAmount: number | null
  startDate: string
  /** 无目标计划是空串（后端对 null 的兜底）—— 用 targetAmount !== null 判断有没有目标，别用这个字段 */
  targetDate: string
  monthlyPlanAmount: number | null
  /**
   * 计划整体的收益四件套（后端算的，**只统计非现金资产**）—— 直接喂给
   * lib/finance.ts 的 returnRate()，别在别处再写一遍公式。
   * 注意它和 currentAmount 口径不同：currentAmount 含现金，这个不含（现金没有收益）。
   */
  returnStats?: SavingsReturnStats
  quote?: string
  note?: string
  noteImage?: string
  assets: SavingsAsset[]
  /** 月度存入走势 */
  series: SavingsMonthPoint[]
}

/**
 * 新建存钱计划的请求体（对应后端 SavingsPlanRequest）。
 *
 * 不含 userId（后端从 token 取）。
 * **目标金额可选**：不传 = 不设目标（只看这组资产的收益），此时 targetDate / monthlyPlanAmount
 * 会被后端忽略并落 NULL，且 type 只能是 wish（总资产计划必须带目标）。
 * startDate 必填（详情页直接渲染）。
 */
export type SavingsPlanPayload = {
  name: string
  type: SavingsPlanType
  description?: string
  /** 封面图 URL，选填；留空时前端渲染占位块 */
  image?: string
  /** 目标金额，选填：不传就是无目标计划 */
  targetAmount?: number
  /** YYYY-MM-DD */
  startDate: string
  /** 有目标金额时必填 */
  targetDate?: string
  /** 有目标金额时必填且 > 0（用来推算预计完成时间） */
  monthlyPlanAmount?: number
  /** 表单里直接勾的资产 id（只有 life / wish 用得上；总资产计划全含，传了也会被忽略） */
  assetIds?: string[]
  quote?: string
  note?: string
  noteImage?: string
}
