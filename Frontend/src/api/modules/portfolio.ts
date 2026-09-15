import { request } from '../http'
import type { DayPoint } from '../types'

/** 组合总览走势：按年份汇总全部进行中持仓的本金与市值 */
export function getPortfolioSeries() {
  return request<DayPoint[]>({ url: '/portfolio/series' })
}
