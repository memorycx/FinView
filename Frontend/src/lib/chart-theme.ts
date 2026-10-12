/**
 * ECharts 运行在 canvas 上，无法直接使用 CSS 变量（var(--chart-1)），
 * 这里读取 :root 上的颜色变量并转换为 canvas 可用的 rgb/rgba 字符串。
 * 支持 oklch、hex、rgb/rgba 三种格式。组件中应在响应式上下文里调用，
 * 以便切换主题后重算。
 */

function oklchToRgb(L: number, C: number, H: number): [number, number, number] {
  const h = (H * Math.PI) / 180
  const a = C * Math.cos(h)
  const b = C * Math.sin(h)

  const l = L + 0.3963377774 * a + 0.2158037573 * b
  const m = L - 0.1055613458 * a - 0.0638541728 * b
  const s = L - 0.0894841775 * a - 1.291485548 * b

  const l3 = l ** 3
  const m3 = m ** 3
  const s3 = s ** 3

  const r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
  const g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
  const bl = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3

  const toSrgb = (x: number) => {
    const v = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055
    return Math.round(Math.min(1, Math.max(0, v)) * 255)
  }

  return [toSrgb(r), toSrgb(g), toSrgb(bl)]
}

function parseAlpha(raw: string | undefined): number {
  if (!raw) return 1
  if (raw.endsWith('%')) return parseFloat(raw) / 100
  return parseFloat(raw)
}

function hexToRgb(hex: string): [number, number, number] | null {
  const m = hex.match(/^#?([\da-f]{2})([\da-f]{2})([\da-f]{2})$/i)
  if (!m) return null
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)]
}

function rgbaStr(r: number, g: number, b: number, a: number): string {
  return a >= 1 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${a})`
}

/**
 * 读取 CSS 自定义属性并转为 rgb/rgba 字符串。
 * @param name  变量名，如 '--chart-1'
 * @param alpha 可选，强制覆盖透明度（0-1）
 */
export function themeColor(name: string, alpha?: number): string {
  const raw = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim()

  if (!raw) return 'rgb(0, 0, 0)'

  // oklch 格式
  const oklchMatch = raw.match(
    /oklch\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+%?))?\s*\)/,
  )
  if (oklchMatch) {
    const [r, g, b] = oklchToRgb(
      parseFloat(oklchMatch[1]),
      parseFloat(oklchMatch[2]),
      parseFloat(oklchMatch[3]),
    )
    const a = alpha ?? parseAlpha(oklchMatch[4])
    return rgbaStr(r, g, b, a)
  }

  // hex 格式
  const hexRgb = hexToRgb(raw)
  if (hexRgb) {
    const a = alpha ?? 1
    return rgbaStr(hexRgb[0], hexRgb[1], hexRgb[2], a)
  }

  // rgba/rgb 格式 — 解析已有透明度
  const rgbMatch = raw.match(/rgba?\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+%?))?\s*\)/)
  if (rgbMatch) {
    const r = parseFloat(rgbMatch[1])
    const g = parseFloat(rgbMatch[2])
    const b = parseFloat(rgbMatch[3])
    const a = alpha ?? parseAlpha(rgbMatch[4])
    return rgbaStr(r, g, b, a)
  }

  // 其他格式原样返回
  return raw
}

/** 图表等宽字体（数字），与 Tailwind 的 font-mono 保持一致；尾部带中文回退 */
export const MONO_FONT =
  "'Geist Mono Variable', ui-monospace, 'SF Mono', Menlo, Consolas, " +
  "'PingFang SC', 'Microsoft YaHei UI', 'Microsoft YaHei', monospace"

/** 图表正文字体（中文标签/Tooltip），与 Tailwind 的 font-sans 保持一致 */
export const SANS_FONT =
  "'Geist Variable', 'PingFang SC', 'Hiragino Sans GB', " +
  "'Microsoft YaHei UI', 'Microsoft YaHei', 'Noto Sans CJK SC', sans-serif"
