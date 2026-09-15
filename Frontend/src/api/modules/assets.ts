import { request } from '../http'
import type { DistributionData, DistributionDim, DistributionItem, AssetSummary } from '../types'

/** 资产总览：总资产 + 按资产大类的市值配置 */
export function getAssetSummary() {
  return request<AssetSummary>({ url: '/assets/summary' })
}

/** 资产分布：一次返回全部维度（行业/区域/币种） */
export function getAssetDistribution() {
  return request<DistributionData>({ url: '/assets/distribution' })
}

/** 资产分布：按指定维度返回 */
export function getAssetDistributionByDim(dim: DistributionDim) {
  return request<DistributionItem[]>({ url: '/assets/distribution', params: { dim } })
}
