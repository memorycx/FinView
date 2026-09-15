import type { InvestFrequency, SavingsPlan } from '@/api/types'

/* ==================== 金额格式化 ==================== */

/** 金额 → ¥12,345 */
export function formatCNY(n: number) {
  return '¥' + n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

/** 金额 → 12.3万 */
export function formatWan(n: number) {
  return (n / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + '万'
}

/** 比率 → +12.3% */
export function formatPct(n: number) {
  return (n >= 0 ? '+' : '') + (n * 100).toFixed(1) + '%'
}

/** 定投频率 → 日投 / 周投 / 月投 */
export function frequencyLabel(freq: InvestFrequency) {
  return { daily: '日投', weekly: '周投', monthly: '月投' }[freq]
}

/* ==================== 收益计算 ==================== */

/** 收益率 =（市值 - 本金）/ 本金 */
export function returnRate(f: { principal: number; current: number }) {
  return (f.current - f.principal) / f.principal
}

/** 累计收益 = 市值 - 本金 */
export function profit(f: { principal: number; current: number }) {
  return f.current - f.principal
}

/* ==================== 存钱计划计算 ==================== */

/** 完成度（0 ~ 1） */
export function savingsProgress(p: Pick<SavingsPlan, 'currentAmount' | 'targetAmount'>) {
  return p.targetAmount === 0 ? 0 : Math.min(1, p.currentAmount / p.targetAmount)
}

/** 还差金额 */
export function savingsRemaining(p: Pick<SavingsPlan, 'currentAmount' | 'targetAmount'>) {
  return Math.max(0, p.targetAmount - p.currentAmount)
}

/** 根据开始时间、目标金额、已存金额，推算预计完成时间（YYYY-MM） */
export function savingsEstimatedDate(
  p: Pick<SavingsPlan, 'targetAmount' | 'currentAmount' | 'monthlyPlanAmount'>,
): string {
  const monthsNeeded = Math.ceil(
    savingsRemaining(p) / (p.monthlyPlanAmount || 1),
  )
  const d = new Date()
  d.setMonth(d.getMonth() + monthsNeeded)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
