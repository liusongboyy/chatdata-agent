<script setup lang="ts">
import { computed } from 'vue'
import { INK, seriesColor } from './palette'
import { compact, niceTicks, toNumber } from './format'

/**
 * 折线图。
 *
 * 规格（dataviz marks-and-anatomy）：
 * - 线 **2px**，圆角连接与端点
 * - 端点标记 **≥8px**（r≥4），带 **2px 表面色描环**——它跨过线或与别的点重叠时仍可读
 * - 单系列时加 **10% 不透明度的面积填充**（一层薄雾，不是实色块）
 * - 只标端点值，**不是每个点都标**
 */
const props = defineProps<{
  categories: string[]
  series: { name: string; values: (number | null)[] }[]
  masked?: boolean
}>()

const H = 260
const PAD = { top: 20, right: 56, bottom: 44, left: 56 }
const MAX_LABEL = 8

const plotW = computed(() => Math.max(240, props.categories.length * 56))

const allValues = computed(() =>
  props.series.flatMap((s) => s.values.map((v) => toNumber(v) ?? 0)),
)
const ticks = computed(() => niceTicks(Math.max(...allValues.value, 0)))
const maxTick = computed(() => ticks.value[ticks.value.length - 1] ?? 1)

const innerH = H - PAD.top - PAD.bottom
const innerW = computed(() => plotW.value - PAD.left - PAD.right)

function y(v: number): number {
  return PAD.top + innerH - (v / maxTick.value) * innerH
}

const stepX = computed(() =>
  props.categories.length <= 1 ? 0 : innerW.value / (props.categories.length - 1),
)

function x(i: number): number {
  return PAD.left + i * stepX.value
}

/** 折线的 path。值为 null 的断点直接跳过该段——不要用 0 补，那会画出不存在的谷底。 */
function linePath(values: (number | null)[]): string {
  let d = ''
  let pen = false
  values.forEach((v, i) => {
    const n = toNumber(v)
    if (n === null) {
      pen = false
      return
    }
    d += (pen ? ' L' : ' M') + x(i) + ',' + y(n)
    pen = true
  })
  return d.trim()
}

function areaPath(values: (number | null)[]): string {
  const pts = values
    .map((v, i) => ({ n: toNumber(v), i }))
    .filter((p) => p.n !== null) as { n: number; i: number }[]
  if (pts.length < 2) return ''
  const first = pts[0]!
  const last = pts[pts.length - 1]!
  return (
    `M${x(first.i)},${y(0)}` +
    pts.map((p) => ` L${x(p.i)},${y(p.n)}`).join('') +
    ` L${x(last.i)},${y(0)} Z`
  )
}

/** 端点值标签。**只标端点**，不是每个点都标。 */
function endLabel(si: number): { x: number; y: number; text: string } | null {
  const s = props.series[si]
  if (!s) return null
  for (let i = s.values.length - 1; i >= 0; i--) {
    const n = toNumber(s.values[i])
    if (n !== null) {
      return { x: x(i) + 8, y: y(n) + 4, text: compact(n) }
    }
  }
  return null
}

function truncate(s: string): string {
  return s.length <= MAX_LABEL ? s : s.slice(0, MAX_LABEL - 1) + '…'
}

/** 轴标签抽稀：点太密时隔几个显示一个，避免糊在一起。 */
const labelEvery = computed(() =>
  Math.max(1, Math.ceil(props.categories.length / Math.floor(innerW.value / 56))),
)
</script>

<template>
  <div class="chart">
    <p v-if="masked" class="note">数值列已被脱敏，画不出图。切到表格查看。</p>

    <svg
      v-else
      class="plot"
      :viewBox="`0 0 ${plotW} ${H}`"
      :style="{ minWidth: plotW + 'px', height: H + 'px' }"
      role="img"
      aria-label="折线图"
    >
      <g>
        <line
          v-for="t in ticks"
          :key="`g${t}`"
          :x1="PAD.left"
          :x2="plotW - PAD.right"
          :y1="y(t)"
          :y2="y(t)"
          :stroke="INK.grid"
          stroke-width="1"
        />
        <text
          v-for="t in ticks"
          :key="`t${t}`"
          :x="PAD.left - 8"
          :y="y(t) + 4"
          text-anchor="end"
          :fill="INK.muted"
          font-size="11"
          class="tick"
        >
          {{ compact(t) }}
        </text>
      </g>

      <g v-for="(s, si) in series" :key="s.name">
        <!-- 单系列才铺面积；多系列叠在一起会互相盖住 -->
        <path
          v-if="series.length === 1"
          :d="areaPath(s.values)"
          :fill="seriesColor(si)"
          opacity="0.1"
        />
        <path
          :d="linePath(s.values)"
          fill="none"
          :stroke="seriesColor(si)"
          stroke-width="2"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
        <!-- 端点标记：r=4（直径 8px），2px 表面色描环 -->
        <g v-for="(v, i) in s.values" :key="i">
          <circle
            v-if="toNumber(v) !== null"
            :cx="x(i)"
            :cy="y(toNumber(v) as number)"
            r="4"
            :fill="seriesColor(si)"
            :stroke="INK.surface"
            stroke-width="2"
          />
        </g>
        <text
          v-if="endLabel(si)"
          :x="endLabel(si)!.x"
          :y="endLabel(si)!.y"
          :fill="INK.secondary"
          font-size="11"
          class="tick"
        >
          {{ endLabel(si)!.text }}
        </text>
      </g>

      <g v-for="(c, i) in categories" :key="c">
        <text
          v-if="i % labelEvery === 0"
          :x="x(i)"
          :y="H - PAD.bottom + 18"
          text-anchor="middle"
          :fill="INK.secondary"
          font-size="11"
        >
          {{ truncate(c) }}
        </text>
      </g>

      <line
        :x1="PAD.left"
        :x2="plotW - PAD.right"
        :y1="y(0)"
        :y2="y(0)"
        :stroke="INK.axis"
        stroke-width="1"
      />
    </svg>

    <ul v-if="!masked && series.length >= 2" class="legend">
      <li v-for="(s, si) in series" :key="s.name">
        <span class="swatch" :style="{ background: seriesColor(si) }" aria-hidden="true" />
        <span>{{ s.name }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.chart {
  overflow-x: auto;
}

.plot {
  display: block;
}

.tick {
  font-variant-numeric: tabular-nums;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.legend li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
</style>
