<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  healthOverview,
  type DomainHealth,
  type FailureBucket,
  type HealthOverview,
} from '../../api/admin'
import { errorMessage } from '../../api/http'
import CostPanel from './CostPanel.vue'
import QualityPanel from './QualityPanel.vue'

/**
 * 问数健康度。
 *
 * ## 为什么把审计数据和建模检查放一页
 *
 * 静态检查只看得出"配置形态缺失"，看不出它造成了多少真实失败；
 * 审计日志只知道"失败了"，不知道该怎么改。
 *
 * 实测：静态检查说「缺双登记」，日志说「配了聚合方式不能作为维度」52 次——
 * 分开看是两个数字，放一起才知道那 52 次是同一条配置问题造成的。
 */
const data = ref<HealthOverview | null>(null)
const loading = ref(false)
const error = ref('')
const days = ref(7)

/**
 * 当前标签。
 *
 * 两个视图回答的是同一类问题（这个系统好不好），分开放反而要来回切。
 */
const tab = ref<'overview' | 'quality' | 'cost'>('overview')

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await healthOverview(days.value)
  } catch (e) {
    error.value = `读取健康度失败：${errorMessage(e)}`
  } finally {
    loading.value = false
  }
}

function onRangeChange(): void {
  void load()
}

/** 失败条数占总失败的比例，用来画条形。 */
const totalFailures = computed(() =>
  data.value?.failures.reduce((n, f) => n + f.count, 0) ?? 0)

function barWidth(f: FailureBucket): string {
  if (totalFailures.value === 0) return '0%'
  const max = Math.max(...(data.value?.failures.map((x) => x.count) ?? [1]))
  return `${Math.round((f.count / max) * 100)}%`
}

/** 这个桶能不能定位到具体域去修。 */
function fixable(f: FailureBucket): boolean {
  return f.fixable
}

/** 成功率配色。低于 85% 报警，90% 以上算健康。 */
function rateClass(rate: number | null): string {
  if (rate === null) return 'rate-none'
  if (rate >= 90) return 'rate-good'
  if (rate >= 85) return 'rate-warn'
  return 'rate-bad'
}

/**
 * 没人用的域要单独标出来。
 *
 * 两种可能：没推广开，或者建完发现不好用。**静态检查看不出来这两种**，
 * 只有把审计数据接进来才看得见。
 */
function isUnused(d: DomainHealth): boolean {
  return d.asks === 0 && d.mode !== 'RAW'
}

/** 建模覆盖度徽章。null 表示直连域不做这项检查。 */
function coverageTone(d: DomainHealth): string {
  if (d.unmodeled === null) return 'cov-na'
  if (d.unmodeled === 0) return 'cov-ok'
  return d.unmodeled <= 5 ? 'cov-warn' : 'cov-bad'
}
</script>

<template>
  <section class="health">
    <header class="page-head">
      <div>
        <h1>问数健康度</h1>
        <p class="sub">
          把运行数据（谁在问、成不成功）和配置数据（域建得够不够）放在一起看。
          两边单独看都会误判：成功率低可能是用户乱问，建模覆盖度差可能没人用。
        </p>
      </div>
      <select v-model.number="days" class="range" @change="onRangeChange">
        <option :value="1">最近 1 天</option>
        <option :value="7">最近 7 天</option>
        <option :value="30">最近 30 天</option>
      </select>
    </header>

    <nav class="tabs">
      <button
        type="button"
        class="tab"
        :class="{ active: tab === 'overview' }"
        @click="tab = 'overview'"
      >
        概览
      </button>
      <button
        type="button"
        class="tab"
        :class="{ active: tab === 'quality' }"
        @click="tab = 'quality'"
      >
        质量分析
      </button>
      <button
        type="button"
        class="tab"
        :class="{ active: tab === 'cost' }"
        @click="tab = 'cost'"
      >
        成本
      </button>
    </nav>

    <QualityPanel v-if="tab === 'quality'" />
    <CostPanel v-else-if="tab === 'cost'" />

    <template v-else-if="tab === 'overview'">
    <div v-if="loading" class="note">加载中…</div>
    <p v-else-if="error" class="note note-danger">{{ error }}</p>

    <template v-else-if="data">
      <!-- 数据量大时的提示。不显示的话，用户只会看到转圈然后怀疑页面坏了 -->
      <p v-if="data.notice" class="notice">
        <strong>这个页面现在比较慢</strong>
        {{ data.notice }}
      </p>

      <!-- 概览 -->
      <div class="stats">
        <div class="stat">
          <b>{{ data.summary.domains }}</b>
          <span>语义域</span>
        </div>
        <div class="stat">
          <b>{{ data.summary.questions.toLocaleString() }}</b>
          <span>提问数</span>
        </div>
        <div class="stat">
          <b :class="rateClass(data.summary.successRate)">
            {{ data.summary.successRate === null ? '—' : data.summary.successRate + '%' }}
          </b>
          <span>成功率</span>
        </div>
        <div class="stat">
          <b :class="{ warn: data.summary.pendingFixes > 0 }">{{ data.summary.pendingFixes }}</b>
          <span>待修配置项</span>
        </div>
      </div>
      <p class="footnote">
        成功率 = 成功 /（成功 + 失败）。<strong>权限拒绝不计入</strong>——
        那是正确行为，算成失败会让「权限收紧」看起来像「系统变差」。
      </p>

      <!-- 失败原因排行 -->
      <div class="panel">
        <h2 class="panel-title">
          失败原因
          <span class="panel-sub">{{ totalFailures }} 次 · 最近 {{ days }} 天</span>
        </h2>

        <p v-if="data.failures.length === 0" class="empty">这个时间段没有失败。</p>
        <div v-else class="failures">
          <div v-for="f in data.failures" :key="f.key" class="failure">
            <span class="count">{{ f.count }}</span>
            <span class="bar-wrap">
              <span class="bar" :class="{ fixable: fixable(f) }" :style="{ width: barWidth(f) }" />
            </span>
            <span class="label">{{ f.label }}</span>
            <RouterLink
              v-if="fixable(f)"
              class="fix-link"
              :to="`/admin/health`"
              :title="'这类问题能靠改建模配置解决'"
            >
              去修 →
            </RouterLink>
            <span v-else class="fix-none" title="改配置解决不了这类问题">—</span>
          </div>
        </div>
      </div>

      <!-- 逐域 -->
      <div class="panel">
        <h2 class="panel-title">各域状态</h2>
        <table class="domains">
          <thead>
            <tr>
              <th>域</th>
              <th>模式</th>
              <th class="num">提问</th>
              <th class="num">成功率</th>
              <th class="num">建模覆盖度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in data.domains" :key="d.id" :class="{ unused: isUnused(d) }">
              <td>
                {{ d.name }}
                <span v-if="isUnused(d)" class="unused-tag" title="建了但零提问：要么没推广，要么建完发现不好用">
                  没人用
                </span>
              </td>
              <td class="mono dim">{{ d.mode }}</td>
              <td class="num">{{ d.asks === 0 ? '—' : d.asks }}</td>
              <td class="num">
                <span :class="rateClass(d.successRate)">
                  {{ d.successRate === null ? '—' : d.successRate + '%' }}
                </span>
              </td>
              <td class="num">
                <span class="cov" :class="coverageTone(d)">
                  {{ d.unmodeled === null ? '—' : d.unmodeled }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    </template>
  </section>
</template>

<style scoped>
.health { padding: 24px; max-width: 1100px; }
.tabs {
  display: flex; gap: 4px; margin-bottom: 18px;
  border-bottom: 1px solid #e2e8f0;
}
.tab {
  background: none; border: none; border-bottom: 2px solid transparent;
  padding: 8px 16px; font-size: 0.88rem; color: #64748b;
  cursor: pointer; margin-bottom: -1px;
}
.tab:hover { color: #0f172a; }
.tab.active { color: #0f172a; border-bottom-color: #6366f1; font-weight: 600; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 20px; }
.page-head h1 { font-size: 1.35rem; margin: 0 0 6px; }
.sub { color: #64748b; font-size: 0.85rem; margin: 0; max-width: 68ch; line-height: 1.6; }
.range { padding: 7px 12px; border: 1px solid #cbd5e1; border-radius: 9px; font-size: 0.85rem; flex-shrink: 0; }

.note { color: #64748b; font-size: 0.86rem; padding: 12px 0; }
.notice {
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px;
  padding: 12px 16px; font-size: 0.83rem; color: #92400e;
  line-height: 1.65; margin-bottom: 16px;
}
.notice strong { display: block; margin-bottom: 2px; }
.note-danger { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; border-radius: 9px; padding: 10px 14px; }

.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 10px; }
.stat { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px 18px; }
.stat b { display: block; font-size: 1.6rem; line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat b.warn { color: #b45309; }
.stat span { color: #64748b; font-size: 0.78rem; }

.rate-good { color: #047857; }
.rate-warn { color: #b45309; }
.rate-bad { color: #b91c1c; }
.rate-none { color: #cbd5e1; }

.footnote { color: #94a3b8; font-size: 0.78rem; margin: 0 0 22px; line-height: 1.6; }

.panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px 20px; margin-bottom: 18px; }
.panel-title { font-size: 0.95rem; margin: 0 0 14px; font-weight: 600; }
.panel-sub { color: #94a3b8; font-weight: 400; font-size: 0.8rem; margin-left: 8px; }
.empty { color: #94a3b8; font-size: 0.85rem; margin: 0; padding: 16px 0; text-align: center; }

.failures { display: flex; flex-direction: column; gap: 2px; }
.failure {
  display: grid;
  grid-template-columns: 48px 1fr 1fr 70px;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  font-size: 0.84rem;
}
.count { text-align: right; font-variant-numeric: tabular-nums; color: #334155; font-weight: 600; }
.bar-wrap { height: 18px; background: #f1f5f9; border-radius: 4px; overflow: hidden; }
.bar { display: block; height: 100%; background: #cbd5e1; border-radius: 4px; transition: width 0.3s; }
.bar.fixable { background: #fbbf24; }
.label { color: #334155; }
.fix-link { color: #4f46e5; font-size: 0.8rem; text-decoration: underline; }
.fix-none { color: #cbd5e1; font-size: 0.8rem; text-align: center; }

.domains { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.domains th { text-align: left; padding: 8px 10px; color: #475569; font-weight: 600; font-size: 0.78rem; border-bottom: 1px solid #e2e8f0; }
.domains td { padding: 8px 10px; border-bottom: 1px solid #f8fafc; }
.domains tr.unused td { background: #fffbeb; }
.domains .num { text-align: right; font-variant-numeric: tabular-nums; }
.dim { color: #94a3b8; }
.mono { font-family: ui-monospace, monospace; }
.unused-tag { font-size: 0.7rem; background: #fef3c7; color: #b45309; border-radius: 4px; padding: 1px 6px; margin-left: 6px; }
.cov { display: inline-block; min-width: 22px; text-align: center; font-size: 0.75rem; font-weight: 700; border-radius: 999px; padding: 2px 7px; }
.cov-ok { background: #d1fae5; color: #047857; }
.cov-warn { background: #fef3c7; color: #b45309; }
.cov-bad { background: #fee2e2; color: #b91c1c; }
.cov-na { color: #cbd5e1; }
</style>
