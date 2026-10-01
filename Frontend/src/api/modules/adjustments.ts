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
