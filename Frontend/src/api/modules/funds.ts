import { request } from '../http'
import type { Fund, FundStatus } from '../types'

/** 获取定投计划列表 */
export function getFunds(params?: { status?: FundStatus }) {
  return request<Fund[]>({ url: '/funds', params })
}

/** 获取单个定投计划详情（含走势与调整记录） */
export function getFund(id: string) {
  return request<Fund>({ url: `/funds/${id}` })
}

/** 收益排行榜：按收益率降序，含已归档计划 */
export function getFundLeaderboard() {
  return request<Fund[]>({ url: '/funds/leaderboard' })
}
