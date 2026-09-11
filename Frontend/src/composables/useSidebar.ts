import { ref } from 'vue'

/** 展开态宽度范围（px） */
export const SIDEBAR_MIN_W = 200
export const SIDEBAR_MAX_W = 320
export const SIDEBAR_DEFAULT_W = 240
/** 收起态图标轨宽度（px） */
export const SIDEBAR_RAIL_W = 76

const COLLAPSED_KEY = 'finview-sidebar-collapsed'
const WIDTH_KEY = 'finview-sidebar-width'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

function readWidth(): number {
  const n = Number(localStorage.getItem(WIDTH_KEY))
  return Number.isFinite(n) && n >= SIDEBAR_MIN_W && n <= SIDEBAR_MAX_W
    ? n
    : SIDEBAR_DEFAULT_W
}

/** 单例状态：跨组件共享侧栏状态 */
const collapsed = ref(readCollapsed())
const width = ref(readWidth())
/** 正在拖拽时关闭过渡动画，避免滞后 */
const resizing = ref(false)

/** 桌面端（≥1024px 侧栏为固定左栏，拖拽/收起仅在桌面端生效） */
const isDesktop = ref(false)
if (typeof window !== 'undefined') {
  const mq = window.matchMedia('(min-width: 1024px)')
  isDesktop.value = mq.matches
  mq.addEventListener('change', (e) => {
    isDesktop.value = e.matches
  })
}

export function useSidebar() {
  function toggle() {
    collapsed.value = !collapsed.value
    try {
      localStorage.setItem(COLLAPSED_KEY, collapsed.value ? '1' : '0')
    } catch {
      /* ignore */
    }
  }

  function setWidth(v: number) {
    width.value = Math.min(SIDEBAR_MAX_W, Math.max(SIDEBAR_MIN_W, v))
    try {
      localStorage.setItem(WIDTH_KEY, String(Math.round(width.value)))
    } catch {
      /* ignore */
    }
  }

  /** 桌面端且处于收起态：只显示图标轨 */
  const railMode = () => isDesktop.value && collapsed.value

  return {
    collapsed,
    width,
    resizing,
    isDesktop,
    railMode,
    toggle,
    setWidth,
  }
}
