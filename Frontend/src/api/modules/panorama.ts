import { request } from '../http'
import type { Panorama } from '../types'

/** 资金全景：按展示桶分组的资产 + 未来 12 个自然月的累计投入（「演变」动画的数据） */
export function getPanorama() {
  return request<Panorama>({ url: '/visualization/panorama' })
}
