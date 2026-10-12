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

/**
 * 为单个计划重新生成每日序列（整段重算 asset_series + 异步补一次净值）。
 * 返回生成后的序列行数，0 = 该 code 没有调整记录、没有可生成的序列。
 */
export function generateFundSeries(id: string) {
  return request<number>({ url: `/funds/${id}/series`, method: 'POST' })
}

/**
 * 一键更新全部资产的每日序列（组合总览页的按钮）：每只都整段重算 + 各自异步补净值。
 * 返回更新的资产数（含已归档；现金没有序列，不计）。
 */
export function generateAllFundSeries() {
  return request<number>({ url: '/funds/series', method: 'POST' })
}
