<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorMessage } from '../api/http'
import ResultView from '../components/chart/ResultView.vue'

/**
 * 分析报告页。
 *
 * ## 它是一份文档，不是一个应用
 *
 * 与问数页的区别：问数页是交互式的（可以继续问、切图表、翻页），
 * 报告页是**只读的快照**。生成之后就不再变化。
 *
 * 所以这里没有输入框、没有按钮（除了打印）。而打印样式是重点——
 * 用户真正要的是「把这份东西发给别人」，最常见的形式是 PDF。
 *
 * ## 图表复用问数页的组件
 *
 * `ResultView` 接收 `columns` / `rows` / `chart`，而报告的 `StepSnapshot`
 * 正好有这三个。复用而不是重写：重写就会分叉，一边修了 bug 另一边没有。
 *
 * 代价是图表是**可交互的**（能切形态），而报告本该只读。这个取舍可以接受——
 * 切形态不影响数据，反而方便读者从不同角度看。
 */

interface StepSnapshot {
  label: string
  purpose: string | null
  columns: string[]
  rows: unknown[][]
  chart: {
    form: 'TABLE' | 'BAR' | 'LINE' | 'PIE' | 'STAT'
    categoryField: string | null
    valueFields: string[]
    seriesField: string | null
    reason: string
  }
  sqlDisplay: string | null
  scope: { metrics: string[]; dimensions: string[]; time: string | null; filters: string[] }
  rowCount: number
}

interface ReportSection {
  kind: 'ANALYSIS' | 'DATA'
  title: string
  purpose: string | null
  steps: StepSnapshot[]
  findings: Array<{ kind: string; text: string }>
}

interface ScopeGroup {
  scope: { metrics: string[]; dimensions: string[]; time: string | null; filters: string[] }
  stepCount: number
  labels: string[]
}

interface Report {
  id: string
  conversationId: string
  username: string
  title: string
  intent: string
  body: {
    conclusion: string | null
    sections: ReportSection[]
    dataSections: ReportSection[]
    scopeConflict: boolean
    scopeGroups: ScopeGroup[]
  }
  sourceMessageIds: string[]
  modelKey: string | null
  generatedAt: string
}

const route = useRoute()
const report = ref<Report | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/ask/reports/${route.params['id']}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (res.status === 404) {
      error.value = '报告不存在，或不属于当前账号'
      return
    }
    if (!res.ok) {
      error.value = `读取报告失败（HTTP ${res.status}）`
      return
    }
    report.value = (await res.json()) as Report
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

/** 章节序号：分析节在前、数据节在后，连续编号。 */
const numbered = computed(() => {
  const r = report.value
  if (!r) return []
  const out: Array<{ section: ReportSection; no: number }> = []
  let n = 1
  for (const s of r.body.sections) out.push({ section: s, no: n++ })
  for (const s of r.body.dataSections) out.push({ section: s, no: n++ })
  return out
})

/** 生成时间格式化。报告是历史文件，时间要写清到分钟。 */
function when(iso: string | null | undefined): string {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 16)
}

/** 口径描述。 */
function describeScope(s: ScopeGroup['scope']): string {
  const range = s.filters.length ? s.filters.join('、') : '全部范围'
  return `${range} · ${s.time ?? '不限时间'}`
}

function print(): void {
  window.print()
}
</script>

<template>
  <div class="report-page">
    <p v-if="loading" class="note">加载中…</p>
    <p v-else-if="error" class="note note-danger">{{ error }}</p>

    <template v-else-if="report">
      <!-- 工具栏：打印时不显示 -->
      <div class="toolbar no-print">
        <RouterLink to="/ask" class="back">← 回问数</RouterLink>
        <button type="button" class="btn-print" @click="print">打印 / 存为 PDF</button>
      </div>

      <article class="doc">
        <header class="doc-head">
          <h1>{{ report.title }}</h1>
          <p class="meta">
            生成于 {{ when(report.generatedAt) }} · {{ report.username }}
            <span v-if="report.modelKey"> · 模型 {{ report.modelKey }}</span>
          </p>
        </header>

        <!-- 口径冲突提示：放在最前，读者可能只看结论 -->
        <div v-if="report.body.scopeConflict" class="warn">
          <strong>本报告包含 {{ report.body.scopeGroups.length }} 组不同口径的数据</strong>
          <p>对比时请注意：不同组的筛选范围或时间范围不同，不能直接横向比较。</p>
        </div>

        <!-- 结论 -->
        <section v-if="report.body.conclusion" class="conclusion">
          <h2>结论</h2>
          <p v-for="(para, i) in report.body.conclusion.split('\n\n')" :key="i">{{ para }}</p>
        </section>

        <!-- 章节 -->
        <section
          v-for="{ section, no } in numbered"
          :key="no"
          class="section"
          :class="{ data: section.kind === 'DATA' }"
        >
          <h2>
            {{ no }}. {{ section.title }}
            <span v-if="section.kind === 'DATA'" class="tag">数据</span>
          </h2>

          <p v-if="section.purpose" class="purpose">{{ section.purpose }}</p>

          <!-- 事实 -->
          <ul v-if="section.findings.length" class="findings">
            <li v-for="(f, i) in section.findings" :key="i">{{ f.text }}</li>
          </ul>

          <!-- 图表 + 数据 -->
          <div v-for="(step, i) in section.steps" :key="i" class="step">
            <p v-if="section.steps.length > 1" class="step-label">{{ step.label }}</p>

            <ResultView
              :columns="step.columns"
              :rows="step.rows"
              :masked-columns="[]"
              :chart="step.chart"
            />

            <p v-if="step.rowCount > step.rows.length" class="truncated">
              仅显示前 {{ step.rows.length }} 行，共 {{ step.rowCount }} 行
            </p>

            <details v-if="step.sqlDisplay" class="sql">
              <summary>查看 SQL</summary>
              <pre>{{ step.sqlDisplay }}</pre>
            </details>
          </div>
        </section>

        <!-- 口径说明 -->
        <section class="section scope">
          <h2>数据口径</h2>
          <ul>
            <li v-for="(g, i) in report.body.scopeGroups" :key="i">
              {{ describeScope(g.scope) }}
              <span class="dim">（{{ g.stepCount }} 步）</span>
            </li>
          </ul>
          <p class="fine">
            报告是生成时刻的快照。之后语义层口径如有调整，本报告仍按当时的口径呈现。
          </p>
        </section>
      </article>
    </template>
  </div>
</template>

<style scoped>
.report-page {
  max-width: 880px;
  margin: 0 auto;
  padding: 24px 20px 64px;
  color: #0f172a;
}

.note {
  color: #64748b;
  font-size: 0.86rem;
  padding: 12px 0;
}

.note-danger {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 9px;
  padding: 10px 14px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.back {
  color: #64748b;
  font-size: 0.85rem;
  text-decoration: none;
}

.back:hover {
  color: #0f172a;
}

.btn-print {
  padding: 7px 16px;
  border: 1px solid #cbd5e1;
  border-radius: 9px;
  background: #fff;
  font-size: 0.85rem;
  cursor: pointer;
}

.btn-print:hover {
  background: #f8fafc;
}

.doc-head h1 {
  font-size: 1.6rem;
  line-height: 1.35;
  margin: 0 0 8px;
}

.meta {
  color: #94a3b8;
  font-size: 0.78rem;
  margin: 0 0 24px;
}

.warn {
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 10px;
  padding: 14px 18px;
  margin-bottom: 24px;
  font-size: 0.84rem;
  color: #92400e;
}

.warn strong {
  display: block;
  margin-bottom: 4px;
}

.warn p {
  margin: 0;
  line-height: 1.6;
}

.conclusion {
  margin-bottom: 32px;
  padding: 18px 22px;
  background: #f8fafc;
  border-left: 3px solid #6366f1;
  border-radius: 4px 10px 10px 4px;
}

.conclusion h2 {
  font-size: 0.9rem;
  margin: 0 0 10px;
  color: #4f46e5;
}

.conclusion p {
  margin: 0 0 10px;
  line-height: 1.75;
  font-size: 0.92rem;
}

.conclusion p:last-child {
  margin-bottom: 0;
}

.section {
  margin-bottom: 36px;
}

.section h2 {
  font-size: 1.05rem;
  margin: 0 0 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.tag {
  font-size: 0.68rem;
  font-weight: 500;
  padding: 1px 7px;
  margin-left: 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #64748b;
  vertical-align: middle;
}

.purpose {
  color: #475569;
  font-size: 0.85rem;
  line-height: 1.7;
  margin: 0 0 14px;
}

.findings {
  margin: 0 0 16px;
  padding-left: 20px;
  font-size: 0.86rem;
  line-height: 1.75;
}

.step {
  margin-bottom: 20px;
}

.step-label {
  font-size: 0.8rem;
  color: #64748b;
  margin: 0 0 6px;
}

.truncated {
  font-size: 0.75rem;
  color: #94a3b8;
  margin: 6px 0 0;
}

.sql {
  margin-top: 8px;
  font-size: 0.78rem;
}

.sql summary {
  color: #64748b;
  cursor: pointer;
}

.sql pre {
  margin: 8px 0 0;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 0.75rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.scope ul {
  margin: 0 0 12px;
  padding-left: 20px;
  font-size: 0.85rem;
  line-height: 1.8;
}

.dim {
  color: #94a3b8;
}

.fine {
  color: #94a3b8;
  font-size: 0.76rem;
  line-height: 1.65;
  margin: 0;
}

/* ── 打印 ──
 * 报告最常见的用途是「发给别人」，而最常见的格式是 PDF。
 * 所以打印样式不是可选项。
 */
@media print {
  .no-print {
    display: none;
  }

  .report-page {
    max-width: none;
    padding: 0;
  }

  .section {
    break-inside: avoid;
  }

  /* SQL 默认收起，但打印时要展开——纸上没法点 */
  .sql[open] pre,
  .sql pre {
    display: block;
  }

  .sql summary {
    display: none;
  }
}
</style>
