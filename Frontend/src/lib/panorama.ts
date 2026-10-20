import { themeColor } from '@/lib/chart-theme'

/**
 * 「资金全景」（3D 塔视图）的取色。
 *
 * **跟着主题走**：浅色主题 = 白底 + 浅色palet 的蓝（2026-10-14 用户要求改白底：深蓝底太暗看不清）；
 * 暗色主题 = 深蓝底 + 亮蓝。全部走 `themeColor()` 读 CSS 变量，切主题时重建场景即可换色
 * （见 PanoramaScene 里对 `isDark` 的 watch）。
 */
export function panoramaColor(colorVar: string | undefined): string {
  return themeColor(colorVar ?? '--chart-1')
}

/** 场景里的基础色：底、环线、文字 */
export function scenePalette() {
  return {
    background: themeColor('--card'),
    ring: themeColor('--border'),
    text: themeColor('--foreground'),
    textDim: themeColor('--muted-foreground'),
  }
}
