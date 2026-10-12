import { request } from '../http'
import type { PortfolioSeries } from '../types'

/** 组合总览：全部进行中持仓的逐日本金/市值走势 + 组合年化收益率 */
export function getPortfolioSeries() {
  return request<PortfolioSeries>({ url: '/portfolio/series' })
}
