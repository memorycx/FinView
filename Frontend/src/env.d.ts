/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 是否使用本地 mock 数据（默认 true）；设为 'false' 时走真实接口 */
  readonly VITE_USE_MOCK?: string
  /** 真实接口基础地址（VITE_USE_MOCK=false 时生效） */
  readonly VITE_API_BASE_URL?: string
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}
