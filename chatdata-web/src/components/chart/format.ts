/**
 * 图表数值与标签的格式化。
 *
 * 抽出来是因为柱、折、饼、数字卡四处都要用同一套规则——
 * 各写各的迟早会出现"柱子上是 1.2K、表格里是 1,234"这种不一致。
 */

/**
 * 紧凑数字：1,284 / 12.9K / 4.2M。
 *
 * 轴刻度与数字卡用它；**表格里不用**——表格要的是能逐位对齐的精确值。
 */
export function compact(v: unknown): string {
  const n = toNumber(v)
  if (n === null) return v === null || v === undefined ? '—' : String(v)

  const abs = Math.abs(n)
  if (abs >= 1_000_000_000) return trim(n / 1_000_000_000) + 'B'
  if (abs >= 1_000_000) return trim(n / 1_000_000) + 'M'
  if (abs >= 1_000) return trim(n / 1_000) + 'K'
  return trim(n)
}

/** 表格与标签用的完整数字：千分位，最多两位小数。 */
export function full(v: unknown): string {
  const n = toNumber(v)
  if (n === null) return v === null || v === undefined ? '—' : String(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

/**
 * 轴刻度取整。
 *
 * 返回一个「好看」的步长：1 / 2 / 5 × 10^n。不取整的话刻度会是
 * 0 / 3333 / 6666 / 9999 这种读不出量级的数字。
 */
export function niceStep(rawStep: number): number {
  if (!Number.isFinite(rawStep) || rawStep <= 0) return 1
  const exp = Math.floor(Math.log10(rawStep))
  const base = Math.pow(10, exp)
  const norm = rawStep / base
  const mult = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10
  return mult * base
}

/** 由最大值与刻度数算出一组整齐的刻度。 */
export function niceTicks(max: number, count = 4): number[] {
  if (!Number.isFinite(max) || max <= 0) return [0, 1]
  const step = niceStep(max / count)
  const top = Math.ceil(max / step) * step
  const out: number[] = []
  for (let v = 0; v <= top + step / 2; v += step) {
    out.push(Number(v.toFixed(10)))
  }
  return out
}

/** 把值转成数字。非数值返回 null —— 调用方据此走"不是数字"的分支。 */
export function toNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  if (typeof v === 'string') {
    const n = Number(v.replace(/,/g, ''))
    return Number.isFinite(n) ? n : null
  }
  return null
}

/** 去掉多余的小数位：1.50 → 1.5，2.00 → 2。 */
function trim(n: number): string {
  return String(Number(n.toFixed(1)))
}
