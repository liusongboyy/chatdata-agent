<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  approveFeedback,
  listDomains,
  listFeedback,
  rejectFeedback,
  type Domain,
  type FeedbackRow,
  type FeedbackStatus,
} from '../../api/admin'
import { errorMessage } from '../../api/http'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'


/**
 * 反馈审核。
 *
 * 这是"反馈闭环"唯一能改变系统行为的地方：用户提交的修正 SQL
 * 必须在这里被确认，才会进入该域的范例库、参与之后相似问题的检索。
 *
 * 审核时服务端会再校验一次只读与表白名单——这里显示的是用户原话，
 * 不代表它一定能入库。若通过后还想调整 SQL，到对应数据域的
 * 「SQL 范例」面板里编辑即可。
 */

const { can } = usePermissions()
const STATUS_TABS: { value: FeedbackStatus | ''; label: string }[] = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: '', label: '全部' },
]

const status = ref<FeedbackStatus | ''>('PENDING')
const items = ref<FeedbackRow[]>([])
/** 用于把 domainId 显示成名字；取不到也不影响审核 */
const domains = ref<Domain[]>([])
const loading = ref(false)
const listError = ref('')
const notice = ref('')
const busyId = ref<number | null>(null)

const TYPE_LABEL: Record<FeedbackRow['feedbackType'], string> = {
  HELPFUL: '有帮助',
  WRONG_ANSWER: '答错了',
  SQL_CORRECTION: '修正 SQL',
}

const STATUS_LABEL: Record<FeedbackStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    items.value = await listFeedback(status.value)
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function switchStatus(next: FeedbackStatus | ''): Promise<void> {
  status.value = next
  notice.value = ''
  await load()
}

async function approve(item: FeedbackRow): Promise<void> {
  busyId.value = item.id
  listError.value = ''
  try {
    const exampleId = await approveFeedback(item.id)
    notice.value = exampleId
      ? `已通过，并写入范例 #${exampleId}。之后的相似问题会检索到它。`
      : '已通过。这条反馈没有修正 SQL，不会写入范例库。'
    await load()
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    busyId.value = null
  }
}

async function reject(item: FeedbackRow): Promise<void> {
  busyId.value = item.id
  listError.value = ''
  try {
    await rejectFeedback(item.id)
    notice.value = '已拒绝，不会写入范例库。'
    await load()
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    busyId.value = null
  }
}

function domainName(id: number): string {
  return domains.value.find((d) => d.id === id)?.name ?? `#${id}`
}

function formatTime(value: string | null): string {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN')
}

onMounted(async () => {
  // 域列表拿不到不影响审核，单独 catch
  listDomains()
    .then((d) => (domains.value = d))
    .catch(() => (domains.value = []))
  await load()
})
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">反馈审核</h1>
        <p class="sub">
          用户改的 SQL <strong>不会自动生效</strong>。只有在这里通过后，它才会进入对应数据域的
          SQL 范例库，参与之后相似问题的检索。
        </p>
      </div>
    </header>

    <div class="tabs" role="tablist" aria-label="反馈状态">
      <button
        v-for="tab in STATUS_TABS"
        :key="tab.value"
        type="button"
        class="tab"
        :class="{ 'tab-active': status === tab.value }"
        role="tab"
        :aria-selected="status === tab.value"
        @click="switchStatus(tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <p v-if="notice" class="note">{{ notice }}</p>
    <div v-if="loading" class="note">加载中…</div>
    <div v-else-if="items.length === 0" class="note">这个状态下没有反馈。</div>

    <div v-else class="cards">
      <article v-for="(item, i) in items" :key="item.id" class="card feedback-card">
        <div class="card-head">
          <span class="card-index mono">{{ String(i + 1).padStart(2, '0') }}</span>
          <h2 class="card-title">{{ item.question }}</h2>
          <span class="tag">{{ TYPE_LABEL[item.feedbackType] }}</span>
          <span class="tag" :class="item.status === 'PENDING' ? 'tag-warn' : 'tag-off'">
            {{ STATUS_LABEL[item.status] }}
          </span>
        </div>

        <dl class="meta">
          <div>
            <dt>提交人</dt>
            <dd>{{ item.username }}</dd>
          </div>
          <div>
            <dt>数据域</dt>
            <dd>{{ domainName(item.domainId) }}</dd>
          </div>
          <div>
            <dt>时间</dt>
            <dd>{{ formatTime(item.createdAt) }}</dd>
          </div>
          <div v-if="item.exampleId">
            <dt>范例</dt>
            <dd>#{{ item.exampleId }}</dd>
          </div>
        </dl>

        <p v-if="item.note" class="user-note">{{ item.note }}</p>

        <details v-if="item.generatedSql" class="sql-block">
          <summary>系统执行的 SQL</summary>
          <pre class="mono">{{ item.generatedSql }}</pre>
        </details>

        <details v-if="item.correctedSql" class="sql-block" open>
          <summary>用户修正的 SQL</summary>
          <pre class="mono">{{ item.correctedSql }}</pre>
        </details>

        <!-- 审核动作单独一个码：能看列表（ask.admin.feedback）不等于能通过/拒绝。
             通过一条 SQL_CORRECTION 会让它进入范例库，影响之后所有相似问题 -->
        <div
          v-if="item.status === 'PENDING' && can(P.ASK_ADMIN_FEEDBACK_REVIEW)"
          class="card-actions"
        >
          <button
            class="btn btn-primary btn-sm"
            type="button"
            :disabled="busyId === item.id"
            @click="approve(item)"
          >
            {{ busyId === item.id ? '处理中…' : '通过' }}
          </button>
          <button
            class="btn btn-danger btn-sm"
            type="button"
            :disabled="busyId === item.id"
            @click="reject(item)"
          >
            拒绝
          </button>
          <span v-if="item.feedbackType === 'SQL_CORRECTION'" class="sub">
            通过前服务端会校验只读与表白名单；通过后仍可在该域的 SQL 范例里编辑。
          </span>
        </div>

        <p v-else class="sub">
          {{ item.reviewedBy ? `${item.reviewedBy} 于 ${formatTime(item.reviewedAt)} 处理` : '已处理' }}
        </p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.tabs {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 16px;
  padding: 4px;
  border-radius: 11px;
  background: var(--surface-sunk);
}

.tab {
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 12px;
  cursor: pointer;
  transition:
    color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.tab:hover {
  color: var(--ink);
}

.tab-active {
  background: var(--surface);
  color: var(--accent-deep);
  font-weight: 650;
  box-shadow: 0 2px 8px rgba(46, 58, 120, 0.09);
}

.card-index {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 7px;
  background: var(--accent-soft);
  font-size: 10px;
  color: var(--accent-deep);
}

/* 用户的话用引文样式，不套提示条：提示条是"系统在说话"，
   这里恰恰是"人在说话"，两者混用会让审核时分不清哪句是谁说的 */
.user-note {
  margin: var(--space-2) 0;
  padding-left: var(--space-3);
  border-left: 2px solid var(--line-strong);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.7;
}

.sql-block {
  margin-top: var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  overflow: hidden;
}

.sql-block summary {
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  background: var(--surface-sunk);
}

.sql-block pre {
  margin: 0;
  padding: var(--space-3);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: var(--text-sm);
}

</style>
