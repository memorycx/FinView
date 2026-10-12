import { request } from '../http'
import type { SavingsPlan, SavingsPlanCandidate, SavingsPlanPayload } from '../types'

/** 获取存钱计划列表 */
export function getSavingsPlans() {
  return request<SavingsPlan[]>({ url: '/savings/plans' })
}

/** 获取单个存钱计划详情 */
export function getSavingsPlan(id: string) {
  return request<SavingsPlan>({ url: `/savings/plans/${id}` })
}

/**
 * 新建存钱计划，返回读接口同款的计划对象（含后端回填的 id，前端拿它跳详情页）。
 * 校验失败时后端返回 code=400 + 中文 message，由 request 抛成 ApiError。
 */
export function createSavingsPlan(payload: SavingsPlanPayload) {
  return request<SavingsPlan>({ url: '/savings/plans', method: 'POST', body: payload })
}

/**
 * 「添加资产」面板的候选：该用户全部资产（含现金、含已归档）+ 是否已在本计划里。
 * 单独一个接口而不是塞进详情：详情每次都要拉，候选只在面板打开时需要；
 * /funds 也不行 —— 现金被后端 foldForUser 滤掉了，但它也能挂进计划。
 */
export function getPlanCandidates(id: string) {
  return request<SavingsPlanCandidate[]>({ url: `/savings/plans/${id}/candidates` })
}

/** 新建表单用的候选资产（还没有计划 id，linked 恒为 false） */
export function getNewPlanCandidates() {
  return request<SavingsPlanCandidate[]>({ url: '/savings/plans/candidates' })
}

/** 把小愿望和一个资产关联起来，返回更新后的计划详情（不用再拉一次 GET） */
export function linkPlanAsset(id: string, assetId: string) {
  return request<SavingsPlan>({
    url: `/savings/plans/${id}/assets`,
    method: 'POST',
    body: { assetId },
  })
}

/** 解除关联，返回更新后的计划详情 */
export function unlinkPlanAsset(id: string, assetId: string) {
  return request<SavingsPlan>({
    url: `/savings/plans/${id}/assets/${assetId}`,
    method: 'DELETE',
  })
}
