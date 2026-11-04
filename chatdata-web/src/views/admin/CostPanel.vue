<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { costReport, type CostReport, type CostRow } from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * 成本看板。
 *
 * ## 为什么单独一个组件而不是塞进 HealthView
 *
 * 它有自己的时间范围（默认 30 天，健康度默认 7 天）和自己的失败姿态
 * （算不出来时**绝不显示一个错的金额**）。塞进去之后两套状态会互相牵制。
 *
 * ## 金额的三种"没有"
 *
 *   ① 模型没配单价     ② 老数据没采 token     ③ 这段时间没人提问
 *
 * 全部显示成"—"和一句原因说明，**不显示 ¥0.00**。
 * 0 元是"免费"，而上面三种都是"不知道"——把不知道写成 0，
 * 管理员会得出"成本很低"的结论，而这个结论是假的。
 */
const data = ref<CostReport | null>(null)
const loading = ref(false)
const error = ref('')
const days = ref(30)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await costReport(days.value)
  } catch (e) {
    error.value = `读取成本数据失败：${errorMessage(e)}`
  } finally {
    loading.value = false
  }
}

/**
 * 金额格式化。
 *
 * 用 4 位小数而不是 2 位：单次调用常见是 0.00xx 元，
 * 两位会全被舍成 0.00，看起来像"免费"。
 */
function money(v: number | null): string {
  if (v === null || v === undefined) return '—'
  if (v === 0) return '¥0'
  if (v < 0.01) return `¥${v.toFixed(4)}`
  if (v < 1) return `¥${v.toFixed(3)}`
  return `¥${v.toFixed(2)}`
}

/** token 数用 K / M 缩写。精确值在 tooltip 里。 */
function tokens(v: number): string {
  if (v === 0) return '—'
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`
  if (v >= 1_000) return `${(v / 1_000).toFixed(0)}K`
  return String(v)
}

function rate(v: number | null): string {
  return v === null ? '—' : `${v}%`
}

/**
 * 这一行的金额可不可信。
 *
 * 部分计价时给一个明确的标记——报告里混着"完整"和"覆盖一半"两个数字，
 * 不加标记就没人看得出来。
 */
function amountNote(r: CostRow): string {
  if (r.asks === 0) return '尚未使用'
  if (r.unpriced) return '未配单价'
  if (r.partial) return `覆盖 ${r.pricedAsks}/${r.asks} 次`
  return ''
}

/** 有模型一条都算不出钱 → 整页顶部的提示。 */
const anyUnpriced = computed(() =>
  (data.value?.byModel ?? []).some((m) => m.unpriced || m.partial))

function barWidth(r: CostRow): string {
  const max = Math.max(...(data.value?.byModel ?? []).map((m) => m.amount ?? 0), 1)
  if (!r.amount) return '0%'
  return `${Math.round((r.amount / max) * 100)}%`
}
</script>

<template>
  <div class="cost">
    <div class="head">
      <p class="sub">
        所有金额按「模型配置里的单价 × 实测 token」算出。单价以管理台配置为准，
        改单价只影响之后的统计——历史记录已经按当时的 token 存下来了，重算即可回看。
      </p>
      <select v-model.number="days" class="range" @change="load">
        <option :value="7">最近 7 天</option>
        <option :value="30">最近 30 天</option>
        <option :value="90">最近 90 天</option>
      </select>
    </div>

    <div v-if="loading" class="note">加载中…</div>
    <p v-else-if="error" class="note note-danger">{{ error }}</p>

    <template v-else-if="data">
      <p v-if="data.notice" class="notice">{{ data.notice }}</p>

      <!-- 只要有一条算不出钱，就在顶部说清楚。藏在行里的话没人看 -->
      <p v-if="anyUnpriced" class="warn">
        <strong>部分记录未计价</strong>
        有模型的单价没配，或这期间的记录没有 token。金额只覆盖能算出来的那部分，
        不要拿它当完整账单。
      </p>

      <div class="stats">
        <div class="stat">
          <b>{{ money(data.total.amount) }}</b>
          <span>总计</span>
        </div>
        <div class="stat">
          <b>{{ data.total.asks.toLocaleString() }}</b>
          <span>提问数</span>
        </div>
        <div class="stat">
          <b>{{ money(data.total.avgPerAsk) }}</b>
          <span>单次均价</span>
        </div>
        <div class="stat">
          <b>{{ rate(data.total.cacheHitRate) }}</b>
          <span>缓存命中率</span>
        </div>
      </div>
      <p class="footnote">
        单次均价 = 总额 / <strong>能算出钱的</strong>提问数（{{ data.total.pricedAsks }} 次），
        不是除以总提问数——否则会被未计价的记录拉低。
      </p>

      <!-- 按模型 -->
      <div class="panel">
        <h2 class="panel-title">
          按模型
          <span class="panel-sub">配了没人用的也会列出来</span>
        </h2>
        <table>
          <thead>
            <tr>
              <th>模型</th>
              <th class="num">提问</th>
              <th class="num">输入</th>
              <th class="num">缓存命中</th>
              <th class="num">输出</th>
              <th class="num">费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in data.byModel" :key="m.key" :class="{ idle: m.asks === 0 }">
              <td>
                {{ m.label }}
                <span v-if="amountNote(m)" class="tag" :class="{ warn: m.unpriced }">
                  {{ amountNote(m) }}
                </span>
                <span class="bar-wrap"><span class="bar" :style="{ width: barWidth(m) }" /></span>
              </td>
              <td class="num">{{ m.asks === 0 ? '—' : m.asks }}</td>
              <td class="num">{{ tokens(m.inputTokens) }}</td>
              <td class="num">{{ tokens(m.cachedTokens) }}</td>
              <td class="num">{{ tokens(m.outputTokens) }}</td>
              <td class="num amount">{{ money(m.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 按域 -->
      <div class="panel">
        <h2 class="panel-title">按数据域</h2>
        <table>
          <thead>
            <tr>
              <th>数据域</th>
              <th class="num">提问</th>
              <th class="num">输入</th>
              <th class="num">输出</th>
              <th class="num">费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in data.byDomain" :key="d.key">
              <td>
                {{ d.label }}
                <span v-if="amountNote(d)" class="tag" :class="{ warn: d.unpriced }">
                  {{ amountNote(d) }}
                </span>
              </td>
              <td class="num">{{ d.asks }}</td>
              <td class="num">{{ tokens(d.inputTokens) }}</td>
              <td class="num">{{ tokens(d.outputTokens) }}</td>
              <td class="num amount">{{ money(d.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 按用户。非管理员时服务端返回空数组，这里整块不渲染 -->
      <div v-if="data.byUser.length" class="panel">
        <h2 class="panel-title">
          按用户
          <span class="panel-sub">前 20 位 · 仅管理员可见</span>
        </h2>
        <table>
          <thead>
            <tr>
              <th>用户</th>
              <th class="num">提问</th>
              <th>常用模型</th>
              <th class="num">费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in data.byUser" :key="u.username">
              <td class="mono">{{ u.username }}</td>
              <td class="num">{{ u.asks }}</td>
              <td class="mono dim">{{ u.topModelKey ?? '—' }}</td>
              <td class="num amount">{{ money(u.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.sub { color: #64748b; font-size: 0.82rem; margin: 0; max-width: 70ch; line-height: 1.65; }
.range { padding: 7px 12px; border: 1px solid #cbd5e1; border-radius: 9px; font-size: 0.85rem; flex-shrink: 0; }

.note { color: #64748b; font-size: 0.86rem; padding: 12px 0; }
.note-danger { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; border-radius: 9px; padding: 10px 14px; }
.notice {
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px;
  padding: 12px 16px; font-size: 0.83rem; color: #92400e; line-height: 1.65; margin-bottom: 14px;
}
.warn {
  background: #fff7ed; border: 1px solid #fed7aa; border-radius: 10px;
  padding: 12px 16px; font-size: 0.82rem; color: #9a3412; line-height: 1.7; margin-bottom: 16px;
}
.warn strong { display: block; margin-bottom: 2px; }

.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 10px; }
.stat { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px 18px; }
.stat b { display: block; font-size: 1.5rem; line-height: 1.25; font-variant-numeric: tabular-nums; }
.stat span { color: #64748b; font-size: 0.78rem; }
.footnote { color: #94a3b8; font-size: 0.78rem; margin: 0 0 22px; line-height: 1.6; }

.panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px 20px; margin-bottom: 18px; }
.panel-title { font-size: 0.95rem; margin: 0 0 14px; font-weight: 600; }
.panel-sub { color: #94a3b8; font-weight: 400; font-size: 0.8rem; margin-left: 8px; }

table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
th { text-align: left; padding: 8px 10px; color: #475569; font-weight: 600; font-size: 0.78rem; border-bottom: 1px solid #e2e8f0; }
td { padding: 9px 10px; border-bottom: 1px solid #f8fafc; vertical-align: middle; }
tr.idle td { color: #94a3b8; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.amount { font-weight: 600; color: #0f172a; }
tr.idle .amount { color: #cbd5e1; font-weight: 400; }
.mono { font-family: ui-monospace, monospace; font-size: 0.8rem; }
.dim { color: #94a3b8; }

.tag {
  font-size: 0.68rem; border-radius: 4px; padding: 1px 6px; margin-left: 6px;
  background: #f1f5f9; color: #64748b; white-space: nowrap;
}
.tag.warn { background: #fef3c7; color: #b45309; }

.bar-wrap { display: block; height: 3px; background: #f1f5f9; border-radius: 2px; margin-top: 4px; max-width: 220px; }
.bar { display: block; height: 100%; background: #a5b4fc; border-radius: 2px; }
</style>
