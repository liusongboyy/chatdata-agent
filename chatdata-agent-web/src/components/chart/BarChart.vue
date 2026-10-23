<script setup lang="ts">
import { computed } from 'vue'
import { INK, seriesColor } from './palette'
import { compact, niceTicks, toNumber } from './format'

/**
 * 柱状图。
 *
 * 手写 SVG 而不是引图表库：一是包体（ECharts 会给 55 kB 的包加约 330 kB gzip），
 * 二是 mark 规格很精确，手写反而更容易做准。
 *
 * 规格（来自 dataviz 的 marks-and-anatomy）：
 * - 柱厚 **≤ 24px**，不填满槽位，剩下的留白
 * - **4px 圆角数据端，基线端是方的**
 * - 相邻柱之间留 **2px 表面色间隙**——用留白分隔，而不是描边
 * - 网格线发丝级（1px）实线，弱化
 */
const props = defineProps<{
  /** 分类轴的值 */
  categories: string[]
  /** 每个系列：名称 + 与 categories 等长的数值 */
  series: { name: string; values: (number | null)[] }[]
  /** 数值列是否被脱敏——脱敏时不能画图，值不是数字 */
  masked?: boolean
}>()

const H = 260
const PAD = { top: 16, right: 16, bottom: 44, left: 56 }

/** 轴标签最多这么长，超出截断——斜排的轴标签比截断更难读。 */
const MAX_LABEL = 8

const plotW = computed(() => Math.max(120, props.categories.length * 64))

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

const bandW = computed(() => innerW.value / Math.max(1, props.categories.length))

/** 组内柱厚：上限 24px，并给相邻柱留 2px 表面色间隙。 */
const barW = computed(() => {
  const n = Math.max(1, props.series.length)
  const GAP = 2
  const avail = bandW.value * 0.62 - GAP * (n - 1)
  return Math.max(3, Math.min(24, avail / n))
})

const groupW = computed(() => barW.value * props.series.length + 2 * (props.series.length - 1))

/** 只在柱数不多时给每根柱标值；多了会糊成一片，让轴与表格承载。 */
const labelAll = computed(() => props.categories.length <= 10 && props.series.length === 1)

function barPath(x: number, top: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, Math.max(0, h))
  if (h <= 0) return ''
  // 顶部两角 4px 圆，底部方——数据端圆角、基线端贴齐
  return `M${x},${top + r} Q${x},${top} ${x + r},${top} L${x + w - r},${top} Q${x + w},${top} ${x + w},${top + r} L${x + w},${top + h} L${x},${top + h} Z`
}

function truncate(s: string): string {
  return s.length <= MAX_LABEL ? s : s.slice(0, MAX_LABEL - 1) + '…'
}

const hover = defineModel<number | null>('hover', { default: null })
</script>

<template>
  <div class="chart">
    <p v-if="masked" class="note">
      数值列已被脱敏，画不出图。切到表格查看。
    </p>

    <svg
      v-else
      class="plot"
      :viewBox="`0 0 ${plotW} ${H}`"
      :style="{ minWidth: plotW + 'px', height: H + 'px' }"
      role="img"
      aria-label="柱状图"
      @mouseleave="hover = null"
    >
      <!-- 网格线：发丝级实线，弱化。**绝不虚线** -->
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

      <!-- 柱 -->
      <g v-for="(c, ci) in categories" :key="c">
        <g
          v-for="(s, si) in series"
          :key="s.name"
          @mouseenter="hover = ci"
          @focus="hover = ci"
        >
          <template v-if="toNumber(s.values[ci]) !== null">
            <path
              :d="barPath(
                PAD.left + ci * bandW + (bandW - groupW) / 2 + si * (barW + 2),
                y(toNumber(s.values[ci]) as number),
                barW,
                y(0) - y(toNumber(s.values[ci]) as number),
              )"
              :fill="seriesColor(si)"
              :opacity="hover === null || hover === ci ? 1 : 0.55"
              class="bar"
            />
            <!-- 直接标签：只有单系列且柱数不多时给每根标。
                 这是 dataviz 那条对比度 WARN 要求的"补偿"之一 -->
            <text
              v-if="labelAll"
              :x="PAD.left + ci * bandW + bandW / 2"
              :y="y(toNumber(s.values[ci]) as number) - 6"
              text-anchor="middle"
              :fill="INK.secondary"
              font-size="11"
              class="tick"
            >
              {{ compact(s.values[ci]) }}
            </text>
          </template>
        </g>

        <text
          :x="PAD.left + ci * bandW + bandW / 2"
          :y="H - PAD.bottom + 18"
          text-anchor="middle"
          :fill="INK.secondary"
          font-size="11"
        >
          {{ truncate(c) }}
        </text>
      </g>

      <!-- 基线 -->
      <line
        :x1="PAD.left"
        :x2="plotW - PAD.right"
        :y1="y(0)"
        :y2="y(0)"
        :stroke="INK.axis"
        stroke-width="1"
      />
    </svg>

    <!-- 多系列必须有图例：身份不能只靠颜色 -->
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

.bar {
  transition: opacity var(--duration-fast) var(--ease-out);
}

/* 轴文字用等宽数字，让刻度纵向对齐 */
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

/* 图例的色块是"文字旁边的有色标记"，文字本身不穿数据色 */
.swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
</style>
