import { onMounted, ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 接口请求组合式函数：挂载时自动请求，管理 loading / error / data 状态。
 *
 * @example
 * const { data: funds, loading, error, reload } = useRequest(() => getFunds({ status: 'active' }))
 */
export function useRequest<T>(fn: () => Promise<T>) {
  const data: Ref<T | undefined> = ref()
  const loading = ref(true)
  const error = ref<Error | null>(null)

  async function run() {
    loading.value = true
    error.value = null
    try {
      data.value = await fn()
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
    } finally {
      loading.value = false
    }
  }

  onMounted(run)

  return { data, loading, error, reload: run }
}
