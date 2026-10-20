import { request } from '../http'
import type { CashFlowCalendar, CashFlowForecast } from '../types'

/**
 * 定投日历：某个月每天的扣款金额与执行计划数 + 本月总览 / 每只基金 / 每周分布。
 * month 形如 2026-10；不传 = 当前月。
 */
export function getCashFlowCalendar(month?: string) {
  return request<CashFlowCalendar>({ url: '/cashflow/calendar', params: month ? { month } : undefined })
}

/** 定投分析：当前现金 + 未来 12 个整月的定投支出与月末余额预测（口径见后端 CashFlowService） */
export function getCashFlowForecast() {
  return request<CashFlowForecast>({ url: '/cashflow/forecast' })
}

/**
 * 保存默认的「每月固定新增」（元；传 null = 清空 → 页面回到「未设置」）。
 * 返回重算后的整份预测，两张卡片一次往返一起刷新。
 */
export function updateMonthlyInflow(monthlyInflow: number | null) {
  return request<CashFlowForecast>({
    url: '/cashflow/monthly-inflow',
    method: 'PUT',
    body: { monthlyInflow },
  })
}

/**
 * 保存某个月的「每月固定新增」覆盖值（点柱状图的柱子），month 形如 2026-12。
 * 传 null = 删除覆盖、恢复默认值（传 0 是「这个月收入为零」，不是恢复默认）。
 * 同样返回重算后的整份预测。
 */
export function updateMonthlyInflowOfMonth(month: string, monthlyInflow: number | null) {
  return request<CashFlowForecast>({
    url: `/cashflow/monthly-inflow/${month}`,
    method: 'PUT',
    body: { monthlyInflow },
  })
}
