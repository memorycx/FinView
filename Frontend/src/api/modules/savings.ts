import { request } from '../http'
import type { SavingsPlan, SavingsPlanCandidate, SavingsPlanPayload, SavingsPlanTypeItem } from '../types'

/** 获取存钱计划列表 */
export function getSavingsPlans() {
  return request<SavingsPlan[]>({ url: '/savings/plans' })
}

/* ==================== 计划类型（内置 + 自定义） ==================== */

/** 类型列表：内置 asset/life/wish 后端会自动补种，新建计划的类型选择用它 */
export function getPlanTypes() {
  return request<SavingsPlanTypeItem[]>({ url: '/savings/plan-types' })
}

/** 新建自定义类型（code 由后端生成，这里只传显示名） */
export function createPlanType(name: string) {
  return request<SavingsPlanTypeItem>({ url: '/savings/plan-types', method: 'POST', body: { name } })
}

/** 改类型名（只改显示名，计划里存的 code 不变） */
export function renamePlanType(id: string, name: string) {
  return request<SavingsPlanTypeItem>({ url: `/savings/plan-types/${id}`, method: 'PUT', body: { name } })
}

/** 删除类型：系统类型（总资产）不能删，还有计划在用也不能删（后端返回 400 + 中文提示） */
export function deletePlanType(id: string) {
  return request<null>({ url: `/savings/plan-types/${id}`, method: 'DELETE' })
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
