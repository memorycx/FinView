import { ApiError } from './error'
import { mockRoutes, type MockRouteContext } from './mock/handlers'

/**
 * 请求核心：
 * - 默认走本地 mock（src/api/mock/handlers.ts 的路由表），模拟真实网络延迟与错误；
 * - 设置 VITE_USE_MOCK=false 后走真实 HTTP，按统一响应包 { code, message, data } 解包。
 */

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestConfig {
  /** 接口路径，如 /funds/:id 中的具体值 /funds/csi300 */
  url: string
  method?: HttpMethod
  /** 查询参数，值为 undefined 的项会被忽略 */
  params?: Record<string, string | number | undefined>
}

/** 后端统一响应包 */
export interface ApiEnvelope<T> {
  /** 业务状态码，0 表示成功 */
  code: number
  message: string
  data: T
}

/* ==================== 真实请求 ==================== */

function buildQuery(params: RequestConfig['params']): string {
  if (!params) return ''
  const qs = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) qs.set(key, String(value))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

async function realRequest<T>(cfg: RequestConfig): Promise<T> {
  const response = await fetch(`${BASE_URL}${cfg.url}${buildQuery(cfg.params)}`, {
    method: cfg.method ?? 'GET',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    throw new ApiError(response.status, `请求失败: ${response.status} ${response.statusText}`)
  }
  const body = (await response.json()) as ApiEnvelope<T>
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message || '请求失败')
  }
  return body.data
}

/* ==================== Mock 请求 ==================== */

const MOCK_DELAY_MIN = 120
const MOCK_DELAY_MAX = 300

const mockDelay = () =>
  new Promise((resolve) =>
    setTimeout(resolve, MOCK_DELAY_MIN + Math.random() * (MOCK_DELAY_MAX - MOCK_DELAY_MIN)),
  )

function matchMockRoute(method: HttpMethod, url: string) {
  const path = url.split('?')[0]
  const actual = path.split('/').filter(Boolean)

  for (const route of mockRoutes) {
    if (route.method !== method) continue
    const segments = route.pattern.split('/').filter(Boolean)
    if (segments.length !== actual.length) continue

    const params: Record<string, string> = {}
    let matched = true
    for (let i = 0; i < segments.length; i++) {
      const seg = segments[i]
      if (seg.startsWith(':')) {
        params[seg.slice(1)] = decodeURIComponent(actual[i])
      } else if (seg !== actual[i]) {
        matched = false
        break
      }
    }
    if (matched) return { route, params }
  }
  return null
}

async function mockRequest<T>(cfg: RequestConfig): Promise<T> {
  const method = cfg.method ?? 'GET'
  const matched = matchMockRoute(method, cfg.url)
  if (!matched) {
    throw new ApiError(404, `Mock 接口未实现: ${method} ${cfg.url}`)
  }

  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(cfg.params ?? {})) {
    if (value !== undefined) query[key] = String(value)
  }

  await mockDelay()
  return matched.route.handle({ params: matched.params, query } satisfies MockRouteContext) as T
}

/* ==================== 对外入口 ==================== */

/** 发起接口请求，失败时抛出 ApiError */
export function request<T>(cfg: RequestConfig): Promise<T> {
  return USE_MOCK ? mockRequest<T>(cfg) : realRequest<T>(cfg)
}
