<script setup lang="ts">
import { computed } from 'vue'
import { seriesColor } from './palette'
import { compact, full, toNumber } from './format'

/**
 * 饼图。
 *
 * ## 只适合"部分与整体、且段数少"
 *
 * dataviz 的反模式清单里写着：饼图**不适合比较接近的数值**，
 * 且段数要 **≤6**——再多相邻扇区就分不出来了。
 * 故这里超过 6 段时直接拒绝渲染，让用户切到柱状图，而不是画一个读不出来的饼。
 *
 * ## 段间用 2px 表面色间隙分隔，不描边
 *
 * 描边会给图形加上不属于数据的墨。
 */
const props = defineProps<{
  categories: string[]
  values: (number | null)[]
  valueName: string
  masked?: boolean
}>()

const MAX_SEGMENTS = 6

const R = 92
const RING = 2

const size = R * 2 + 24
const cx = size / 2
const cy = size / 2

interface Seg {
  label: string
  value: number
  pct: number
  index: number
}

const total = computed<number>(() =>
  props.values.reduce<number>((acc, v) => acc + Math.max(0, toNumber(v) ?? 0), 0),
)

const segments = computed<Seg[]>(() =>
  props.categories
    .map((c, i) => {
      const v = Math.max(0, toNumber(props.values[i]) ?? 0)
      return { label: c, value: v, pct: total.value > 0 ? v / total.value : 0, index: i }
    })
    .filter((s) => s.value > 0),
)

const tooMany = computed(() => segments.value.length > MAX_SEGMENTS)

function arc(from: number, to: number): string {
  // 单段占满整圈时 SVG 的弧线画不出来，用两个半圆代替
  if (to - from >= 0.9999) {
    return (
      `M${cx},${cy - R} A${R},${R} 0 1 1 ${cx},${cy + R}` +
      ` A${R},${R} 0 1 1 ${cx},${cy - R} Z`
    )
  }
  const a0 = from * 2 * Math.PI - Math.PI / 2
  const a1 = to * 2 * Math.PI - Math.PI / 2
  const x0 = cx + R * Math.cos(a0)
  const y0 = cy + R * Math.sin(a0)
  const x1 = cx + R * Math.cos(a1)
  const y1 = cy + R * Math.sin(a1)
  const large = to - from > 0.5 ? 1 : 0
  return `M${cx},${cy} L${x0},${y0} A${R},${R} 0 ${large} 1 ${x1},${y1} Z`
}

/**
 * 把每段收窄一点点，露出 2px 的表面色间隙。
 *
 * 收窄的是**角度**而不是给描边——描边会让扇形看起来比实际大。
 * 角度收窄量按半径换算：2px 弧长 ≈ 2/R 弧度。
 */
const pad = computed(() => RING / R / (2 * Math.PI))

const arcs = computed(() => {
  let acc = 0
  return segments.value.map((seg) => {
    const from = acc
    acc += seg.pct
    const a0 = from + pad.value / 2
    const a1 = Math.max(a0, acc - pad.value / 2)
    return { seg, d: arc(a0, a1) }
  })
})
</script>

<template>
  <div class="chart">
    <p v-if="masked" class="note">数值列已被脱敏，画不出图。切到表格查看。</p>

    <p v-else-if="tooMany" class="note">
      这个结果有 {{ segments.length }} 段，饼图超过 {{ MAX_SEGMENTS }} 段就分不清了。
      请切到柱状图比较量级。
    </p>

    <p v-else-if="segments.length === 0" class="note">没有可展示的正数值。</p>

    <div v-else class="wrap">
      <svg
        :viewBox="`0 0 ${size} ${size}`"
        :style="{ width: size + 'px', height: size + 'px' }"
        role="img"
        :aria-label="`饼图：${valueName}`"
      >
        <path
          v-for="(a, i) in arcs"
          :key="a.seg.label"
          :d="a.d"
          :fill="seriesColor(i)"
        />
      </svg>

      <!-- 图例即标签：段数少，直接列出名称与占比比在扇区上挤文字清楚 -->
      <ul class="legend">
        <li v-for="(a, i) in arcs" :key="a.seg.label">
          <span class="swatch" :style="{ background: seriesColor(i) }" aria-hidden="true" />
          <span class="name">{{ a.seg.label }}</span>
          <span class="value tick">{{ compact(a.seg.value) }}</span>
          <span class="pct tick">{{ (a.seg.pct * 100).toFixed(1) }}%</span>
        </li>
      </ul>
    </div>

    <p v-if="!masked && !tooMany && segments.length > 0" class="total">
      合计 <span class="tick">{{ full(total) }}</span> · {{ valueName }}
    </p>
  </div>
</template>

<style scoped>
.chart {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--space-6);
}

.legend {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: var(--text-sm);
}

.legend li {
  display: grid;
  grid-template-columns: 10px minmax(4em, 1fr) auto auto;
  align-items: center;
  gap: var(--space-3);
}

.swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
}

.name {
  color: var(--ink-soft);
}

/* 数字用等宽，让一列值纵向对齐 */
.value {
  color: var(--ink);
  font-weight: 600;
}

.pct {
  color: var(--ink-faint);
  min-width: 3.5em;
  text-align: right;
}

.tick {
  font-variant-numeric: tabular-nums;
}

.total {
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.total .tick {
  color: var(--ink);
  font-weight: 600;
}
</style>
