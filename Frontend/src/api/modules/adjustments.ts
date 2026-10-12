import { request } from '../http'
import type { Adjustment, AdjustmentPayload } from '../types'

/** 新建资产调整记录（录入数据） */
export function createAdjustment(payload: AdjustmentPayload) {
  return request<Adjustment>({
    url: '/adjustments',
    method: 'POST',
    body: payload,
  })
}

/**
 * 查单条调整记录（定投详情页的编辑表单用它回填真实字段）。
 * 不能直接用 /funds 里的时间线：那里的 action 是展示文案（「上调金额」这种）、
 * 结束定投的记录金额被刻意置空，改记录要的是 adjustments 表里的原始值。
 */
export function getAdjustment(id: string) {
  return request<Adjustment>({ url: `/adjustments/${id}` })
}

/** 整条更新（字段与新建一致；后端写完会整段重算该 code 的每日序列），返回更新后的记录 */
export function updateAdjustment(id: string, payload: AdjustmentPayload) {
  return request<Adjustment>({
    url: `/adjustments/${id}`,
    method: 'PUT',
    body: payload,
  })
}

/** 删除调整记录（后端删完会整段重算该 code 的每日序列），成功时 data 为 null */
export function deleteAdjustment(id: string) {
  return request<null>({
    url: `/adjustments/${id}`,
    method: 'DELETE',
  })
}
