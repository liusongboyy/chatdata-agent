<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  qualityReport,
  type QualityReport,
  type QuestionStat,
} from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * 问数质量：按问题类型看。
 *
 * ## 与「概览」标签的分工
 *
 * 概览回答「整体好不好」——成功率、失败原因。
 * 这一页回答「**哪类问题**不好」。
 *
 * 实测差距：整体失败率 9%，但体量小的问法里藏着 100% 失败的情况。
 * 只看总量会被大量成功掩盖。
 */
const data = ref<QualityReport | null>(null)
const loading = ref(false)
const error = ref('')
const days = ref(30)

/** 展开的类型。同时只展开一个——两个都展开会让页面很长且难以对照 */
const expanded = ref<string | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await qualityReport(days.value)
    // 默认展开失败数最多的那一类——那是用户最该先看的
    const worst = data.value?.types
      .filter((t) => t.failures > 0)
      .sort((a, b) => b.failures - a.failures)[0]
    expanded.value = worst?.key ?? data.value?.types[0]?.key ?? null
  } catch (e) {
    error.value = `读取质量分析失败：${errorMessage(e)}`
  } finally {
    loading.value = false
  }
}

function toggle(key: string): void {
  expanded.value = expanded.value === key ? null : key
}

function questionsOf(key: string): QuestionStat[] {
  return data.value?.questionsByType[key] ?? []
}

/** 未分类占比。超过 20% 说明分类规则不够用。 */
const unknownRatio = computed(() => {
  if (!data.value || data.value.totalAsks === 0) return 0
  return Math.round((data.value.unknownAsks / data.value.totalAsks) * 1000) / 10
})

/** 成功率配色：与概览页同一套。 */
function rateClass(rate: number): string {
  if (rate >= 90) return 'rate-good'
  if (rate >= 70) return 'rate-warn'
  return 'rate-bad'
}

/** 耗时配色。超过 5 秒已经让用户等得不耐烦了。 */
function msClass(ms: number): string {
  if (ms <= 2000) return ''
  if (ms <= 5000) return 'ms-slow'
  return 'ms-very-slow'
}

</script>

<template>
  <div class="quality">
    <div v-if="loading" class="note">加载中…</div>
    <p v-else-if="error" class="note note-danger">{{ error }}</p>

    <template v-else-if="data">
      <div class="toolbar">
        <span class="total">
          最近 {{ data.days }} 天共 <b>{{ data.totalAsks.toLocaleString() }}</b> 次提问
        </span>
        <select v-model.number="days" class="range" @change="load">
          <option :value="7">最近 7 天</option>
          <option :value="30">最近 30 天</option>
          <option :value="90">最近 90 天</option>
        </select>
      </div>

      <!-- 未分类占比过高时提醒。它是信号：规则没覆盖的新问法模式 -->
      <p v-if="unknownRatio > 20" class="notice">
        <strong>未分类占比 {{ unknownRatio }}%，偏高</strong>
        说明有一批问法没被分类规则覆盖。它本身不影响问数，
        但会让这一页看不清全貌——该提炼新规则了。
      </p>

      <p v-if="data.notice" class="notice">
        <strong>这个页面现在比较慢</strong>
        {{ data.notice }}
      </p>

      <table class="types">
        <thead>
          <tr>
            <th>问题类型</th>
            <th class="num">提问</th>
            <th class="num">失败率</th>
            <th class="num">平均耗时</th>
            <th class="sample">典型问题</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="t in data.types" :key="t.key">
            <tr
              class="type-row"
              :class="{ open: expanded === t.key, unknown: t.key === 'UNKNOWN' }"
              @click="toggle(t.key)"
            >
              <td>
                <span class="caret">{{ expanded === t.key ? '▾' : '▸' }}</span>
                {{ t.label }}
                <span v-if="t.failures > 0" class="fail-badge">{{ t.failures }} 失败</span>
              </td>
              <td class="num">{{ t.asks.toLocaleString() }}</td>
              <td class="num">
                <span :class="rateClass(100 - t.successRate)">{{ t.successRate }}%</span>
              </td>
              <td class="num" :class="msClass(t.avgMs)">{{ (t.avgMs / 1000).toFixed(1) }}s</td>
              <td class="sample dim">{{ t.sampleQuestion || '—' }}</td>
            </tr>

            <!-- 展开：这一类型里的高频问法 -->
            <tr v-if="expanded === t.key" class="detail-row">
              <td colspan="5">
                <table class="questions">
                  <thead>
                    <tr>
                      <th>问法</th>
                      <th class="num">次数</th>
                      <th class="num">失败</th>
                      <th class="num">成功率</th>
                      <th class="num">平均耗时</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="q in questionsOf(t.key)"
                      :key="q.question"
                      :class="{ bad: q.successRate < 70 }"
                    >
                      <td>{{ q.question }}</td>
                      <td class="num">{{ q.asks }}</td>
                      <td class="num">{{ q.failures }}</td>
                      <td class="num">
                        <span :class="rateClass(q.successRate)">{{ q.successRate }}%</span>
                      </td>
                      <td class="num" :class="msClass(q.avgMs)">
                        {{ (q.avgMs / 1000).toFixed(1) }}s
                      </td>
                    </tr>
                    <tr v-if="questionsOf(t.key).length === 0">
                      <td colspan="5" class="empty-cell">
                        这个类型里没有出现 5 次以上的问法（样本太少看不出规律）。
                      </td>
                    </tr>
                  </tbody>
                </table>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <p class="footnote">
        <strong>为什么按次数排序而不是失败率</strong>：只出现一次且失败的问题没有统计意义，
        而出现 39 次失败 20 次的才是真在影响用户。
        出现不足 5 次的问法不显示——样本太少时成功率是噪声。
      </p>
    </template>
  </div>
</template>

<style scoped>
.quality { padding: 4px 0; }
.note { color: #64748b; font-size: 0.86rem; padding: 12px 0; }
.note-danger { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; border-radius: 9px; padding: 10px 14px; }

.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.total { color: #64748b; font-size: 0.85rem; }
.total b { color: #0f172a; font-size: 1rem; }
.range { padding: 6px 11px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 0.84rem; }

.notice {
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px;
  padding: 11px 15px; font-size: 0.83rem; color: #92400e;
  line-height: 1.6; margin-bottom: 14px;
}
.notice strong { display: block; margin-bottom: 2px; }

.types { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.types th { text-align: left; padding: 9px 10px; color: #475569; font-weight: 600; font-size: 0.78rem; border-bottom: 1px solid #e2e8f0; }
.types td { padding: 10px; border-bottom: 1px solid #f1f5f9; }
.types .num { text-align: right; font-variant-numeric: tabular-nums; }
.types .sample { max-width: 340px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.type-row { cursor: pointer; }
.type-row:hover { background: #f8fafc; }
.type-row.open { background: #f1f5f9; }
.type-row.unknown td { color: #b45309; }
.caret { display: inline-block; width: 14px; color: #94a3b8; }
.fail-badge { font-size: 0.7rem; background: #fee2e2; color: #b91c1c; border-radius: 4px; padding: 1px 6px; margin-left: 6px; }

.detail-row td { padding: 0 0 8px 24px; background: #f8fafc; }
.questions { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.questions th { padding: 6px 10px; color: #94a3b8; font-weight: 500; font-size: 0.74rem; border-bottom: 1px solid #e2e8f0; }
.questions td { padding: 6px 10px; border-bottom: 1px solid #f1f5f9; }
.questions .num { text-align: right; font-variant-numeric: tabular-nums; }
.questions tr.bad td { background: #fef2f2; }
.empty-cell { color: #94a3b8; font-size: 0.78rem; text-align: center; padding: 14px; }

.rate-good { color: #047857; font-weight: 600; }
.rate-warn { color: #b45309; font-weight: 600; }
.rate-bad { color: #b91c1c; font-weight: 700; }
.ms-slow { color: #b45309; }
.ms-very-slow { color: #b91c1c; }

.dim { color: #94a3b8; }
.footnote { color: #94a3b8; font-size: 0.78rem; margin-top: 16px; line-height: 1.7; }
.footnote strong { color: #64748b; }
</style>
