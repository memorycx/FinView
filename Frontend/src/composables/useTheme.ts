import { ref, watch } from 'vue'

type Theme = 'light' | 'dark'

const STORAGE_KEY = 'finview-theme'

function getInitialDark(): boolean {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'light' || stored === 'dark') {
    return stored === 'dark'
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** 当前是否为深色主题（全局单例） */
export const isDark = ref<boolean>(getInitialDark())

function applyTheme(dark: boolean) {
  const el = document.documentElement
  el.classList.toggle('dark', dark)
  el.classList.toggle('light', !dark)
  el.style.colorScheme = dark ? 'dark' : 'light'
}

// 模块加载时立即应用，避免首屏闪烁
applyTheme(isDark.value)

watch(isDark, (dark) => {
  applyTheme(dark)
  localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light')
})

export function useTheme() {
  function toggleTheme() {
    isDark.value = !isDark.value
  }

  function setTheme(theme: Theme) {
    isDark.value = theme === 'dark'
  }

  return { isDark, toggleTheme, setTheme }
}
