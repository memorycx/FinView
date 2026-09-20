import type { DayPoint } from '@/api/types'

/**
 * 时间序列日度化工具：把按年/月采样的稀疏锚点扩展为按自然日连续的序列。
 */

/** YYYY-MM-DD -> 自纪元起的天数（UTC 计算，避免时区/夏令时干扰） */
function dayOrdinal(day: string): number {
  const [y, m, d] = day.split('-').map(Number)
  return Math.floor(Date.UTC(y, m - 1, d) / 86_400_000)
}

/** 自纪元起的天数 -> YYYY-MM-DD */
function fromOrdinal(ord: number): string {
  const dt = new Date(ord * 86_400_000)
  const y = dt.getUTCFullYear()
  const m = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const d = String(dt.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 确定性伪随机：同一日期始终得到同一波动，刷新后曲线不会跳动 */
function mulberry32(seed: number): number {
  let a = seed >>> 0
  a = (a + 0x6d2b79f5) | 0
  let t = Math.imul(a ^ (a >>> 15), 1 | a)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}

/** 单日市值相对波动幅度上限，用于模拟日度净值起伏 */
const DAY_VOLATILITY = 0.012

/**
 * 将稀疏采样点（年/月）扩展为按自然日连续的数据点：
 * - 本金 principal：相邻锚点间线性平滑
 * - 市值 total：线性插值叠加确定性微小波动，波动在锚点处归零，
 *   因此所有原始锚点（含末点统计值）保持严格不变
 * - 输入若已是日度数据，则原样返回
 */
// export function expandDaily(points: readonly DayPoint[]): DayPoint[] {
//   if (points.length <= 1) return points.map((p) => ({ ...p }))

//   const out: DayPoint[] = []
//   for (let i = 0; i < points.length - 1; i++) {
//     const a = points[i]
//     const b = points[i + 1]
//     const oa = dayOrdinal(a.day)
//     const span = dayOrdinal(b.day) - oa
//     if (span <= 0) continue

//     for (let d = 0; d < span; d++) {
//       const t = d / span
//       const principal = Math.round(a.principal + (b.principal - a.principal) * t)
//       const base = a.total + (b.total - a.total) * t
//       const fade = Math.sin(Math.PI * t)
//       const wobble = mulberry32(oa + d) * 2 - 1
//       const total = Math.round(base + wobble * DAY_VOLATILITY * Math.max(base, 1) * fade)
//       out.push({ day: fromOrdinal(oa + d), principal, total })
//     }
//   }
//   out.push({ ...points[points.length - 1] })
//   return out
// }
