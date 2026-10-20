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

/** 基金细分类型（asset.asset_type）：equity 股票基金 / bond 债券基金 / money 货币基金。cash 只属于保留 code CASH */
export type FundType = 'equity' | 'bond' | 'money'

/**
 * 资产总览（/assets/summary）的展示桶 key：基金按 FundType 细分成股票基金 / 债券基金，
 * **不是 asset.category 原值**——两行同 key 会撞开 v-for 与取色表，所以后端派生后单列。
 */
export type AllocationBucket = 'equityFund' | 'bondFund' | 'moneyFund' | 'stock' | 'bond' | 'cash'

/** 定投频率 */
export type InvestFrequency = 'daily' | 'weekly' | 'monthly'

/** 标的市场：us 美股 / hk 港股；null = A 股（只有 QDII 需要标，见 asset.market） */
export type FundMarket = 'us' | 'hk'

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
  /**
   * 是否从下一交易日起生效：只有「调整定投」创建的行动记录为 true。
   * 时间线据此显示「次一交易日起生效」。
   */
  deferred?: boolean
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
  /**
   * 申购费率（**百分数**，0.12 = 0.12%），来自 asset.rate；null / 0 = 不收申购费。
   * 定投详情页的「编辑」按钮用它回填；改完由后端整段重算序列。
   */
  rate?: number | null
  /** 标的市场：us / hk；null = A 股。定投详情页的编辑表单用它回填 */
  market?: FundMarket | null
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

/**
 * 编辑基金基础信息入参（PUT /funds/{id}，定投详情页的「编辑」按钮）。
 * 编码不在这里：它是 adjustments / asset_series / nav_trend / plan_asset 的关联键，不允许改。
 */
export interface FundUpdatePayload {
  /** 资产名称，必填 */
  name: string
  /** 基金细分类型：与 asset.asset_type 一致 */
  assetType: FundType
  /** 申购费率（百分数）；null = 不收申购费 */
  rate: number | null
  /** 标的市场；null = A 股 */
  market: FundMarket | null
}

/* ==================== 资产调整记录（录入数据） ==================== */

/**
 * 资产调整动作：
 * - 1 定投开始
 * - 2 结束定投
 * - 3 买入（一次性资金流入；现金的进账也用它）
 * - 4 卖出（一次性资金流出；现金的出账也用它）
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
  /** 动作：1定投开始 / 2结束定投 / 3买入 / 4卖出（现金为进账 / 出账） */
  action: AdjustmentAction
  /**
   * 仅 action=1 有意义：true = 从下一交易日起生效（当天仍按原计划扣款，频率与金额都从次日开始生效）。
   * 不传 / false = 原有口径（只改金额当天生效、改频率次日生效）。
   */
  deferred?: boolean
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
  /** 动作：1定投开始 / 2结束定投 / 3买入 / 4卖出（现金为进账 / 出账） */
  action: AdjustmentAction
  /** 是否从下一交易日起生效（仅 action=1；历史记录为 false） */
  deferred?: boolean
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
   * 安全资金（元）= asset_type 为 bond（债基）/ money（货币基金）/ cash（现金）的市值合计（后端算好，口径见
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

/**
 * 一个计划类型的完整定义（/savings/plan-types）。
 *
 * **类型可以自定义**（2026-10-13 起）：`code` 是稳定标识（计划存的就是它），`name` 是可改的显示名，
 * 所以改名不用碰任何计划。内置三种（asset/life/wish）由后端首次访问时自动补种；
 * 自定义类型 code 形如 `c8f3a1b2`。只有 `system`（= asset）不能改名 / 删除。
 */
export type SavingsPlanTypeItem = {
  /** 类型 id（改 / 删时用） */
  id: string
  /** 类型标识：内置 asset/life/wish，自定义 c+随机串；计划的 type 与之对应 */
  code: string
  /** 显示名（可改） */
  name: string
  /** 内置类型（asset/life/wish），界面上标「内置」 */
  builtin: boolean
  /** 系统类型（只有 asset）：不能改名、不能删除 */
  system: boolean
}

/** 计划内持有的资产项 */
export type SavingsAsset = {
  /** 资产 id（解除关联时用；也是列表渲染的 key —— 名字可以重复、也可能被改） */
  id: string
  /** 资产编码 */
  code: string
  name: string
  amount: number
  /**
   * 这只资产自己的收益四件套（与计划整体那份同口径，直接喂 lib/finance.ts 的 profit / returnRate）。
   * **现金为 null**：它没有 asset_series、本金恒等于市值，谈不上收益 —— 界面显示「—」。
   */
  returnStats: SavingsReturnStats | null
  /** 图表主题色变量 */
  colorVar?: string
  /**
   * 当前是否正在执行定投。**只有计划详情类响应才有**：
   * 列表响应为 null/undefined（不为每个计划各 fold 一次），前端按「未计算」处理。
   */
  dcaActive?: boolean | null
  /** 当前定投频率；未在定投时为 null */
  dcaFrequency?: InvestFrequency | null
  /** 当前每期定投金额（元）；未在定投时为 null */
  dcaAmount?: number | null
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
  /**
   * 已在**另一个计划**里时，那个计划的名字（一一对应规则：一个资产只能属于一个分类）；
   * 没有归属、或就是本计划时是 null。界面据此把这一行禁用并提示「已在『养老金』里」。
   */
  linkedPlanName?: string | null
}

/** 月度存入数据点 */
export type SavingsMonthPoint = {
  /** YYYY-MM */
  month: string
  amount: number
}

/** 计划详情里的单项资产（图例 + 配色） */
export type SavingsInflowAsset = {
  code: string
  name: string
  /** 图表主题色变量，如 --chart-1 */
  colorVar?: string | null
}

/** 未来流入的单月明细 */
export type SavingsInflowMonth = {
  /** YYYY-MM */
  month: string
  /** 当月定投流入合计（元） */
  total: number
  /** code → 当月金额，只放非零项；空对象 = 这个月没有扣款 */
  byAsset: Record<string, number>
}

/**
 * 计划详情的未来 12 个月定投流入（对应后端 SavingsInflowForecastResponse）。
 *
 * 口径与「定投分析」的现金流预测同源：下月起 12 个完整自然月、只算周期定投
 * （action=1/2）、金额 = amount + fee、按实际扣款日归月（顺延到次月就记在次月）。
 * `assets` 只含窗口内有金额的资产；`months` 恒 12 项，没扣款的月份 total=0。
 */
export type SavingsInflowForecast = {
  /** 窗口首月，如 2026-11 */
  fromMonth: string
  /** 窗口末月，如 2027-10 */
  toMonth: string
  assets: SavingsInflowAsset[]
  months: SavingsInflowMonth[]
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
  /** 类型 code：内置 asset/life/wish，或用户自定义类型（c+随机串）。判断总资产用 `type === 'asset'` */
  type: string
  /** 类型显示名（用户可改）；分类标签直接用它，别自己再映射一套 */
  typeName: string
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
  /**
   * 未来 12 个月定投流入预测。**只有计划详情类接口返回**（列表没有）；
   * mock 模式也可能缺省，页面据此隐藏「未来资金流入」卡片。
   */
  inflowForecast?: SavingsInflowForecast | null
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
  /** 类型 code：必须是该用户类型表里的（内置三种或自定义），普通计划不设目标时也行（总资产除外） */
  type: string
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

/* ==================== 定投分析（现金流预测） ==================== */

/**
 * 定投分析里单个自然月的预测点（/cashflow/forecast 的 months[]，恒 12 项、按月份升序）。
 *
 * 口径见后端 CashFlowService：窗口 = 下月起 12 个整月；支出按**实际扣款日**归月
 * （每周一 / 每月 1 日遇非交易日顺延，跨月就记在次月）。
 */
export type CashFlowMonth = {
  /** YYYY-MM */
  month: string
  /** 当月现金新增：有单月覆盖用覆盖值，否则用默认的 monthlyInflow（都为 0 时是 0） */
  inflow: number
  /** 当月定投支出（元）＝当月实际划走的定投金额合计（申购费从里面扣，不额外增加流出） */
  outlay: number
  /** 当月末现金余额 = 上月末余额 + 当月新增 − 当月支出 */
  endBalance: number
  /** 这个月的新增是否被单独调整过（true = 点柱子设过；可以「恢复默认」） */
  overridden: boolean
}

/**
 * 定投分析（现金流规划 + 现金积累预测），对应后端 CashFlowForecastResponse。
 *
 * 卡片四个数的自洽算式（后端保证精确成立，分位不差）：
 * `projectedCash = currentCash + 12 × monthlyInflow − 12 × monthlyOutlay`，
 * 且「12 个月后」= months[11].endBalance。
 */
export type CashFlowForecast = {
  /** 当前可用现金（元）= asset 表 code=CASH 的 total，与 /assets/summary 的现金桶同源 */
  currentCash: number
  /**
   * **默认的**每月固定新增（元）；null = 用户还没设置，页面显示「未设置」并按 0 参与预测。
   * 单个月份可以在柱状图上点柱子单独覆盖（months[].inflow / overridden）
   */
  monthlyInflow: number | null
  /** 每月定投总额（元）= 未来 12 个月扣款合计 ÷ 12（已四舍五入到分） */
  monthlyOutlay: number
  /** 12 个月后预计现金（元）= currentCash + 12 个月新增合计 − 12 × monthlyOutlay（无单月覆盖时 = currentCash + 12 × monthlyInflow − 12 × monthlyOutlay） */
  projectedCash: number
  /** 窗口内 12 个月的扣款合计（元），对账用 */
  totalOutlay: number
  /** 窗口首月，YYYY-MM（下月起，如 2026-11） */
  fromMonth: string
  /** 窗口末月，YYYY-MM（如 2027-10） */
  toMonth: string
  /**
   * 交易日历覆盖到的最后一天（YYYY-MM-DD）；null = 日历没加载。
   * 日历只覆盖到当年年底，之后的节假日按「周一~周五」估算（扣款日偏多），脚注用它说明。
   */
  calendarCoverageEnd: string | null
  /** 12 个月明细，按月份升序 */
  months: CashFlowMonth[]
}

/** 定投日历里的一天（/cashflow/calendar 的 days[]，恒 lengthOfMonth 项） */
export type CashFlowCalendarDay = {
  /** YYYY-MM-DD */
  day: string
  /** 当日定投金额（元）；非扣款日是 0 */
  amount: number
  /** 当日执行的定投计划数量 */
  plans: number
  /**
   * 当天扣款的各基金明细（点某一天时右侧列表用它），按金额降序；没有扣款的日子是空数组。
   * 名称与配色不在这里重复，按 code 去 funds[] 里取。
   */
  funds: { code: string; amount: number }[]
}

/** 定投日历里一只基金在某月的汇总 */
export type CashFlowCalendarFund = {
  code: string
  name: string
  /** 本月金额（元） */
  amount: number
  /** 占比 0~1 = amount / total */
  share: number
  /** 配色变量名（--chart-N），直接写 `var(${colorVar})`；与存钱计划同一套轮转 */
  colorVar?: string
}

/**
 * 定投日历（定投分析页），对应后端 CashFlowCalendarResponse。
 *
 * 口径：金额 = 当天**周期扣款**划走的钱（申购费从里面扣，买入/卖出记录不算定投）；
 * 整月按当前事件流重算（过去的日子也是），本月剩余与未来月份是计划值。
 */
export type CashFlowCalendar = {
  /** 该月，YYYY-MM */
  month: string
  /** 导航下界：最早有定投记录的月份 */
  minMonth: string
  /** 导航上界：预测窗口末月（本月 + 12） */
  maxMonth: string
  /** 本月定投总金额（元） */
  total: number
  /** 定投执行天数（金额 > 0 的天数） */
  executedDays: number
  /** 涉及基金数量 */
  fundCount: number
  /** 日均定投金额（元）= total ÷ executedDays */
  dailyAverage: number
  /** 该月每一天，按日期升序 */
  days: CashFlowCalendarDay[]
  /** 每只基金的本月金额与占比，按金额降序 */
  funds: CashFlowCalendarFund[]
  /** 周一 ~ 周日的金额分布（7 项，下标 0 = 周一） */
  weekdays: number[]
  /**
   * 年度定投金额分布：所选月份那一年的 1~12 月合计（12 项，下标 0 = 1 月）。
   * 后端会把 fold 推到那一年年尾，所以即使次年年尾超出预测窗口，这里也是完整的 12 个月。
   */
  monthsOfYear: number[]
  /** 年度定投总金额（元）= monthsOfYear 之和 */
  yearTotal: number
  /** 年度定投执行天数：当年有扣款的天数 */
  yearExecutedDays: number
  /** 年度涉及基金数量 */
  yearFundCount: number
  /** 年度日均定投金额（元）= yearTotal ÷ yearExecutedDays；没有执行日时是 0 */
  yearDailyAverage: number
}


/* ==================== 资金全景（3D 塔视图） ==================== */

/** 塔里的一截（一只资产）；cumulative 与 3D 分段、右侧折线同源 */
export type PanoramaAsset = {
  code: string
  name: string
  /** 当前市值（元） */
  value: number
  /** 3D 分段与右侧折线共用的颜色变量（--chart-N，桶内轮转） */
  colorVar: string
  /** 12 项：从今天起到第 i 个月末的累计投入（元），单调不减；现金恒 0 */
  cumulative: number[]
}

/** 3D 里的一座塔 = 一个展示桶 */
export type PanoramaBucket = {
  /** equityFund / bondFund / moneyFund / stock / bond / cash */
  key: string
  label: string
  /** 桶当前市值（元） */
  value: number
  /** 桶内资产，按市值降序（塔从下往上就是这个顺序） */
  assets: PanoramaAsset[]
}

/** 「资金全景」页数据，对应后端 PanoramaResponse；口径见 PanoramaService */
export type Panorama = {
  /** 数据起点（今天），YYYY-MM-DD */
  asOf: string
  /** 12 个自然月（含本月剩余），YYYY-MM */
  months: string[]
  /** 按市值降序的桶 */
  buckets: PanoramaBucket[]
}
