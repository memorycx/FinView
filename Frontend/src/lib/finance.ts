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
