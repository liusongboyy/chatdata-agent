/**
 * 图表调色板。
 *
 * ## 这些颜色是**验证过的**，不是挑出来的
 *
 * 用 dataviz 技能的校验脚本跑过（浅色表面 #ffffff，分类调色板）：
 *
 * ```
 * [PASS] Lightness band       all 8 inside L 0.43–0.77
 * [PASS] Chroma floor         all 8 >= 0.1
 * [PASS] CVD separation       worst adjacent #eda100↔#1baf7a ΔE 9.1 (protan)
 * [PASS] Normal-vision floor  worst adjacent #e87ba4↔#eda100 ΔE 19.6
 * [WARN] Contrast vs surface  #1baf7a 2.82 / #eda100 2.17 / #e87ba4 2.69 低于 3:1
 * ```
 *
 * 那条 WARN 要求"补偿"：**可见的直接标签，或表格视图**。
 * 两者我们都有——柱子上有数值标签，且任何时候都能切到表格视图。
 *
 * ## 顺序是 CVD 安全机制，不是审美
 *
 * 槽位顺序经过枚举验证；换顺序可能让相邻两色在色盲视角下不可分。
 * **不要重排，也不要循环使用**——第 9 个系列不是生成一个新色，
 * 而是折叠成"其他"或改用小倍数图。
 */
export const SERIES = [
  '#2a78d6', // 1 蓝
  '#eb6834', // 2 橙
  '#1baf7a', // 3 青
  '#eda100', // 4 黄
  '#e87ba4', // 5 品红
  '#008300', // 6 绿
  '#6250d6', // 7 紫
  '#e34948', // 8 红
] as const

/** 第 9 个及以后的系列统一用它——不生成新色。 */
export const SERIES_OVERFLOW = '#8a8a80'

/** 单系列时用槽位 1。 */
export const SERIES_PRIMARY = SERIES[0]

/**
 * 取第 i 个系列色。
 *
 * 超出 8 个返回中性灰，而不是循环回第 1 个——
 * 循环会让两个不同的系列看起来是同一个。
 */
export function seriesColor(i: number): string {
  return SERIES[i] ?? SERIES_OVERFLOW
}

/** 图表用的中性色。**文字永远用这些，不穿数据色**。 */
export const INK = {
  primary: '#111827',
  secondary: '#52514e',
  muted: '#8a8a80',
  /** 网格线与轴：比表面深一档的灰，发丝级（1px 实线，绝不虚线） */
  grid: '#e8ecf1',
  axis: '#d6dae0',
  surface: '#ffffff',
} as const
