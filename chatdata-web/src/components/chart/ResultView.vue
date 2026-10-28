<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BarChart from './BarChart.vue'
import LineChart from './LineChart.vue'
import PieChart from './PieChart.vue'
import StatTiles from './StatTiles.vue'
import { full, toNumber } from './format'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'

/**
 * 问数结果的展示：表格 + 图，带形态切换。
 *
 * ## 后端推荐默认形态，用户可切
 *
 * 推荐由后端给（它知道 DSL 意图与列类型），但**不强制**——
 * 同一个结果既可能想看量级（柱）也可能想看占比（饼）。
 * 只禁用数据不支持的形态（如脱敏时不能画图、段数过多时不给饼）。
 *
 * ## 表格永远可用
 *
 * 它不只是兜底：dataviz 的对比度 WARN 要求"可见标签或表格视图"作为补偿，
 * 而表格是那条补偿里最可靠的一半。
 */
type Form = 'TABLE' | 'BAR' | 'LINE' | 'PIE' | 'STAT'

const props = defineProps<{
  columns: string[]
  rows: unknown[][]
  maskedColumns: string[]
  chart: {
    form: Form
    categoryField: string | null
    valueFields: string[]
    seriesField: string | null
    reason: string
  }
}>()

/** 当前形态。初始用后端推荐的，用户切了就听用户的。 */
const form = ref<Form>(props.chart.form)
watch(
  () => props.chart.form,
  (f) => {
    form.value = f
  },
)

const colIndex = (name: string): number => props.columns.indexOf(name)

const masked = computed(() =>
  props.chart.valueFields.some((v) => props.maskedColumns.includes(v)),
)

/** 分类轴取值，按行顺序去重。 */
const categories = computed<string[]>(() => {
  const ci = props.chart.categoryField ? colIndex(props.chart.categoryField) : -1
  if (ci < 0) return []
  const seen: string[] = []
  for (const r of props.rows) {
    const v = r[ci] === null || r[ci] === undefined ? '—' : String(r[ci])
    if (!seen.includes(v)) seen.push(v)
  }
  return seen
})

/** 数值系列。有第二维度时按它分组，否则每个指标一条。 */
const series = computed<{ name: string; values: (number | null)[] }[]>(() => {
  const ci = props.chart.categoryField ? colIndex(props.chart.categoryField) : -1
  const si = props.chart.seriesField ? colIndex(props.chart.seriesField) : -1

  if (si < 0) {
    return props.chart.valueFields.map((name) => {
      const vi = colIndex(name)
      return {
        name,
        values: categories.value.map((cat) => {
          const row = props.rows.find((r) => String(r[ci]) === cat)
          return row && vi >= 0 ? toNumber(row[vi]) : null
        }),
      }
    })
  }

  // 两个维度：第一个作分类轴，第二个作系列
  const seriesNames: string[] = []
  for (const r of props.rows) {
    const v = r[si] === null || r[si] === undefined ? '—' : String(r[si])
    if (!seriesNames.includes(v)) seriesNames.push(v)
  }
  const vi = props.chart.valueFields[0] !== undefined ? colIndex(props.chart.valueFields[0]) : -1
  return seriesNames.map((sn) => ({
    name: sn,
    values: categories.value.map((cat) => {
      const row = props.rows.find(
        (r) => String(r[ci]) === cat && String(r[si]) === sn,
      )
      return row && vi >= 0 ? toNumber(row[vi]) : null
    }),
  }))
})

/** 饼图只取第一个指标、第一个系列——它表达的是"一个整体的构成"。 */
const pieValues = computed<(number | null)[]>(() => series.value[0]?.values ?? [])

/** 数字卡的取值：单行时每个指标一张卡。 */
const statItems = computed(() => {
  if (props.rows.length !== 1) return []
  const row = props.rows[0]!
  return props.columns
    .map((c, i) => ({ label: c, value: row[i] }))
    .filter((x) => toNumber(x.value) !== null)
    .map((x) => ({ label: x.label, value: full(x.value) }))
})

/** 数据支不支持某个形态。 */
const availability = computed<Record<Form, boolean>>(() => ({
  TABLE: true,
  STAT: statItems.value.length > 0,
  BAR: categories.value.length > 0 && series.value.length > 0 && !masked.value,
  LINE: categories.value.length > 1 && series.value.length > 0 && !masked.value,
  PIE:
    categories.value.length > 0 &&
    categories.value.length <= 6 &&
    series.value.length === 1 &&
    !masked.value,
}))

const FORMS: { key: Form; label: string }[] = [
  { key: 'TABLE', label: '表格' },
  { key: 'BAR', label: '柱状' },
  { key: 'LINE', label: '折线' },
  { key: 'PIE', label: '饼图' },
  { key: 'STAT', label: '数字卡' },
]

/**
 * 有没有切换图表形态的权限（`ask.ui.charts`）。
 *
 * 没有时**整条切换器隐藏**，并且**强制回到表格**——只隐藏切换器是不够的：
 * 后端推荐的默认形态可能是饼图，那样结果区照样画出一张饼图，
 * 权限等于没生效。
 */
const { can } = usePermissions()
const canSwitchCharts = computed(() => can(P.ASK_UI_CHARTS))

const visibleForms = computed(() => (canSwitchCharts.value ? FORMS : []))

/** 当前形态不可用时自动退到表格——不显示一个空图。 */
watch(
  [availability, canSwitchCharts],
  ([a, canCharts]) => {
    if (!canCharts) {
      form.value = 'TABLE'
      return
    }
    if (!a[form.value]) form.value = 'TABLE'
  },
  { immediate: true },
)
</script>

<template>
  <div class="result">
    <!-- 形态切换。数据不支持的形态禁用而不是隐藏——隐藏会让用户
         以为"没有这个功能"，禁用加 title 说明原因才说得清。
         没有 ask.ui.charts 时整条隐藏（表格仍然可用，它是结果区的地板） -->
    <div v-if="visibleForms.length > 0" class="switcher" role="tablist" aria-label="展示形态">
      <button
        v-for="f in visibleForms"
        :key="f.key"
        type="button"
        role="tab"
        class="switch"
        :class="{ 'switch-on': form === f.key }"
        :disabled="!availability[f.key]"
        :title="availability[f.key] ? '' : '当前数据不适合这种形态'"
        :aria-selected="form === f.key"
        @click="form = f.key"
      >
        {{ f.label }}
      </button>
      <span class="reason">{{ chart.reason }}</span>
    </div>

    <!-- 表格：任何时候都能看 -->
    <div v-if="form === 'TABLE'" class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col" scope="col">
              {{ col }}
              <span
                v-if="maskedColumns.includes(col)"
                class="mask-tag"
                title="该列按权限规则脱敏，真实值未离开数据库"
                >已脱敏</span
              >
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="ri">
            <td
              v-for="(cell, ci) in row"
              :key="ci"
              :class="{ num: typeof cell === 'number' }"
            >
              {{ cell === null || cell === undefined ? '—' : cell }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BarChart
      v-else-if="form === 'BAR'"
      :categories="categories"
      :series="series"
      :masked="masked"
    />

    <LineChart
      v-else-if="form === 'LINE'"
      :categories="categories"
      :series="series"
      :masked="masked"
    />

    <PieChart
      v-else-if="form === 'PIE'"
      :categories="categories"
      :values="pieValues"
      :value-name="chart.valueFields[0] ?? ''"
      :masked="masked"
    />

    <StatTiles v-else-if="form === 'STAT'" :items="statItems" />
  </div>
</template>

<style scoped>
.result {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.switcher {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 3px;
  align-self: flex-start;
  border-radius: 9px;
  background: var(--surface-sunk);
}

.switch {
  padding: 5px 11px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 10.5px;
  font-weight: 550;
  color: var(--ink-soft);
  transition:
    background-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.switch:not(:disabled):hover {
  color: var(--ink);
}

.switch:disabled {
  color: var(--line-strong);
  cursor: not-allowed;
}

.switch-on {
  background: var(--surface);
  color: var(--accent-deep);
  font-weight: 650;
  box-shadow: 0 2px 6px rgba(46, 58, 120, 0.1);
}

/* 推荐理由：解释"为什么默认是这个形态"，用户不必猜 */
.reason {
  margin-left: 6px;
  padding-right: 6px;
  font-size: 10px;
  color: var(--ink-faint);
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
}

.data {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.data th,
.data td {
  padding: 9px 14px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.data th {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--ink-faint);
  background: var(--surface-sunk);
}

.data tbody tr:last-child td {
  border-bottom: none;
}

.data tbody tr:hover td {
  background: var(--surface-sunk);
}

/* 表格里的数字右对齐 + 等宽：这里是**需要纵向对齐**的场景，
   等宽数字是对的（与数字卡的大号数字相反） */
.data td.num {
  text-align: right;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.mask-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 5px;
  background: var(--warn-soft);
  color: var(--warn-ink);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0;
}
</style>
