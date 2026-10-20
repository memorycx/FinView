import type { InvestFrequency, SavingsPlan } from '@/api/types'

/* ==================== 金额格式化 ==================== */

/** 金额 → ¥12,345.67（固定两位小数：本金/市值都是分位精度，不能只显示到元） */
export function formatCNY(n: number) {
  return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 金额 → 1.23万（固定两位小数） */
export function formatWan(n: number) {
  return (n / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '万'
}

/** 比率 → +12.3% */
export function formatPct(n: number) {
  return (n >= 0 ? '+' : '') + (n * 100).toFixed(1) + '%'
}

/** 定投频率 → 日投 / 周投 / 月投 */
export function frequencyLabel(freq: InvestFrequency) {
  return { daily: '日投', weekly: '周投', monthly: '月投' }[freq]
}

/**
 * 调整记录的动作文案（时间线显示用）。
 * 后端 SeriesService 生成的 action=3 / 4 文案已经是「买入 / 卖出」；
 * 这里兼容尚未重启的旧后端（仍返回「一笔收入 / 一笔支出」），重启后这两条分支自然不再命中。
 */
export function adjustmentActionLabel(action: string) {
  if (action === '一笔收入') return '买入'
  if (action === '一笔支出') return '卖出'
  return action
}

/* ==================== 时间格式化 ==================== */

/**
 * ISO 时间（2026-10-02T14:33:12，后端 asset.last_update_time 的序列化格式）→ 2026-10-02 14:33。
 * null / 空串返回 null，由调用方显示「—」（还没有 asset 行时用）。
 */
export function formatDateTime(iso?: string | null) {
  return iso ? iso.replace('T', ' ').slice(0, 16) : null
}

/* ==================== 收益计算 ==================== */

/**
 * 收益率 = 累计收益 / 累计投入。
 * 累计收益 =（市值 − 本金 − 累计手续费）跨清仓连续（本金是净投入，卖出已冲减）；
 * 分母用累计投入而不是「本金 + 手续费」——清过仓的基金本金会变成负数，拿它当分母会算出假数字，
 * 没清过仓时两者恰好相等（见 CLAUDE.md）。
 */
export function returnRate(f: {
  principal: number
  current: number
  fee: number
  invested?: number
}) {
  const invested = f.invested ?? f.principal + f.fee
  return invested === 0 ? 0 : profit(f) / invested
}

/** 累计收益 = 市值 − 本金 − 累计手续费 */
export function profit(f: { principal: number; current: number; fee: number }) {
  return f.current - f.principal - f.fee
}

/* ==================== 存钱计划计算 ==================== */

/**
 * 完成度（0 ~ 1）。无目标计划（targetAmount 为 null）返回 0 —— 界面上要先用
 * savingsHasTarget() 判断该显示完成度还是收益率，别把 0 当成「进度为零」。
 */
export function savingsProgress(p: Pick<SavingsPlan, 'currentAmount' | 'targetAmount'>) {
  const target = p.targetAmount ?? 0
  return target <= 0 ? 0 : Math.min(1, p.currentAmount / target)
}

/** 还差金额（无目标计划恒为 0） */
export function savingsRemaining(p: Pick<SavingsPlan, 'currentAmount' | 'targetAmount'>) {
  return Math.max(0, (p.targetAmount ?? 0) - p.currentAmount)
}

/**
 * 计划有没有设定目标金额。
 * 没设目标的计划（2026-10-05 起支持）后端回的是 null，早期的行可能是 0 —— 两种都算「没设」。
 * 界面上一律用这个判断，「有目标 / 无目标」走两套显示（完成度 vs 总体收益率）。
 */
export function savingsHasTarget(
  p: Pick<SavingsPlan, 'targetAmount'>,
): boolean {
  return p.targetAmount != null && p.targetAmount > 0
}

/**
 * 计划整体的收益率：口径与基金列表完全一致 —— 用的是同一个 returnRate()，
 * 只是取值来源换成后端算好的 returnStats（只含非现金资产）。
 * invested 为 0（没买过 / 全是现金）时返回 0，界面上要另外用 savingsHasReturn() 判断该不该显示。
 */
export function savingsReturnRate(
  p: Pick<SavingsPlan, 'returnStats'>,
): number {
  return p.returnStats ? returnRate(p.returnStats) : 0
}

/** 有分母才算得动收益率：没有买入记录、或计划里只有现金时返回 false（界面显示「—」） */
export function savingsHasReturn(
  p: Pick<SavingsPlan, 'returnStats'>,
): boolean {
  return (p.returnStats?.invested ?? 0) > 0
}

/**
 * 根据每月计划存入，推算预计完成时间（YYYY-MM）。
 * 没设目标 / 每月存入为空时返回空串 —— 原来这里靠 `|| 1` 兜底，无目标的计划会算出
 * 「几万个月后」的荒唐日期，所以要在入口挡住。
 */
export function savingsEstimatedDate(
  p: Pick<SavingsPlan, 'targetAmount' | 'currentAmount' | 'monthlyPlanAmount'>,
): string {
  if (!savingsHasTarget(p) || !(p.monthlyPlanAmount && p.monthlyPlanAmount > 0)) return ''
  const monthsNeeded = Math.ceil(savingsRemaining(p) / p.monthlyPlanAmount)
  const d = new Date()
  d.setMonth(d.getMonth() + monthsNeeded)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/**
 * 新建计划时「目标日期」留空的自适应推算（**口径与后端 SavingsService.estimatedTargetDate 一字不差**）：
 * 份数 = ⌈目标金额 ÷ 每月存入⌉（至少 1、上限 100 年），从开始日期往后推 N 个月，日期不足时往回调
 * （10-31 + 4 个月 → 02-28，与 Java LocalDate.plusMonths 的语义一致，别用 Date.setMonth 直接加——
 * 它会溢出成 03-02/03-03）。
 *
 * 只用于表单里的实时预览；真正落库的日期以后端算的为准。
 */
export function targetDateFromMonthly(
  startDate: string,
  targetAmount: number,
  monthlyPlanAmount: number,
): string {
  if (!startDate || !(targetAmount > 0) || !(monthlyPlanAmount > 0)) return ''
  const [year, month, day] = startDate.split('-').map(Number)
  const months = Math.min(1200, Math.max(1, Math.ceil(targetAmount / monthlyPlanAmount)))
  const shifted = new Date(year, month - 1, 1)
  shifted.setMonth(shifted.getMonth() + months)
  const targetYear = shifted.getFullYear()
  const targetMonth = shifted.getMonth()
  const lastDay = new Date(targetYear, targetMonth + 1, 0).getDate()
  const dd = String(Math.min(day, lastDay)).padStart(2, '0')
  return `${targetYear}-${String(targetMonth + 1).padStart(2, '0')}-${dd}`
}
