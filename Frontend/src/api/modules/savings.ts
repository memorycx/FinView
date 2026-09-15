import { request } from '../http'
import type { SavingsPlan } from '../types'

/** 获取存钱计划列表 */
export function getSavingsPlans() {
  return request<SavingsPlan[]>({ url: '/savings/plans' })
}

/** 获取单个存钱计划详情 */
export function getSavingsPlan(id: string) {
  return request<SavingsPlan>({ url: `/savings/plans/${id}` })
}
