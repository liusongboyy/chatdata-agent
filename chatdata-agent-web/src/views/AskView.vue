<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  askStream,
  deleteConversation,
  fetchAssistants,
  fetchCapabilities,
  fetchConversation,
  listConversations,
  recompute,
  submitFeedback,
  type AskDsl,
  type AskEvent,
  type AskResultEvent,
  type AskStage,
  type ConversationMessage,
  type ConversationSummary,
  type FailureCode,
  type FeedbackType,
  type UsableAssistant,
} from '../api/ask'
import { errorMessage } from '../api/http'
import { logout as bffLogout } from '../api/auth'
import ResultView from '../components/chart/ResultView.vue'
import UserMenu from '../components/UserMenu.vue'
import UnderstandingBlock from '../components/understanding/UnderstandingBlock.vue'
import { session } from '../composables/useSession'
import { usePermissions } from '../composables/usePermissions'
import { P } from '../permissions'

const router = useRouter()
const { can } = usePermissions()

/** 当前域能问的字段。用于空状态直接回答"你能问哪些数据"。 */
const domainName = ref<string | null>(null)
const fields = ref<string[]>([])

/** 当前用户可用的助理。 */
const assistants = ref<UsableAssistant[]>([])
/** 选中的助理 id；空串表示不经过助理（直连域）。 */
const assistantId = ref('')

const currentAssistant = computed(
  () => assistants.value.find((a) => a.id === assistantId.value) ?? null,
)

const userInitial = computed(() => {
  const name = session.user.value?.realname?.trim() || session.user.value?.username || '我'
  return name.slice(0, 1).toUpperCase()
})

/** 按当前助理刷新"能问什么"。换助理就得重取——不同助理绑不同域。 */
async function refreshCapabilities(): Promise<void> {
  const caps = await fetchCapabilities(undefined, assistantId.value || undefined)
  domainName.value = caps.domainName
  fields.value = caps.fields
}

onMounted(async () => {
  assistants.value = await fetchAssistants()
  void loadHistory(1)
  // **默认选中第一个可用助理**，而不是留空。
  //
  // 留空时后端走"不经过助理"那条路，落点是「第一个启用中的域」——
  // 与任何助理无关。后果是进页面看到的域和助理对不上，
  // 而用户没有任何线索知道这件事，只觉得"切换没反应"。
  if (assistants.value.length > 0) {
    assistantId.value = assistants.value[0]!.id
  }
  await refreshCapabilities()
})

async function onAssistantChange(): Promise<void> {
  // 换助理 = 换语义域，历史上下文里的字段名在新域里可能不存在，
  // 或者更糟——**存在但是另一个口径的字段**，于是模型照着旧上下文答出一个
  // 语法正确、语义错误的查询。所以换助理必须同时开新会话。
  if (turns.value.length > 0) resetSession()
  await refreshCapabilities()
}

/** 一轮问答。 */
interface Turn {
  id: string
  /**
   * 用户问题，或重算的说明（如「去掉时间范围」）。
   *
   * 重算的标题由我们生成而不是用户写的，所以要用 `kind` 在界面上区分开——
   * 把一句系统生成的话当成"用户问过的问题"展示，会让人以为是自己打的。
   */
  question: string
  kind: 'question' | 'recompute'
  status: 'streaming' | 'done' | 'error'
  stage: AskStage | null
  stageMessage: string
  result: AskResultEvent | null
  failure: { code: FailureCode; message: string } | null
}

/**
 * 一个"放宽条件"的动作。
 *
 * v1 只给两种，都是 0 行最常见的原因。**不做"去掉某一条筛选"**：
 * 那需要按条列出筛选并让用户挑，而块头（理解回显）已经把条件摊开了，
 * 挑错一条再挑一条的成本比直接改问题更高。
 */
interface Relaxation {
  label: string
  /** 写进新一轮标题与审计的说法 */
  note: string
  apply: (dsl: AskDsl) => AskDsl
}

/**
 * 这次结果**有哪些条件可以放宽**。
 *
 * 关键是"没有就不给按钮"：一个点了什么都不改变的按钮，
 * 比没有按钮更让人困惑。真的一个条件都没有时，界面应当直说
 * "这个指标在库里可能确实没有数据"，而不是继续暗示"再试试"。
 */
function relaxationsFor(dsl: AskDsl): Relaxation[] {
  const out: Relaxation[] = []
  if (dsl.timeRange) {
    out.push({
      label: '去掉时间范围',
      note: '去掉时间范围',
      apply: (d) => ({ ...d, timeRange: null }),
    })
  }
  if (dsl.filters.length > 0) {
    out.push({
      label: `去掉 ${dsl.filters.length} 个筛选条件`,
      note: '去掉筛选条件',
      apply: (d) => ({ ...d, filters: [] }),
    })
  }
  return out
}

const turns = ref<Turn[]>([])
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

/**
 * 本次会话的 id。多轮追问靠它——后端按 (用户, 会话) 存取上下文，
 * 换个 id 就是一段全新的对话。
 *
 * **刻意不持久化**，刷新页面即开新会话。放 sessionStorage 能让刷新后续上，
 * 但那样用户会看到一个空白界面、问一句「那华北呢」，而系统那边还记着
 * 二十分钟前另一个标签页里的问题——上下文在，界面上却看不到，这比"忘了"更难理解。
 *
 * 隔离由后端保证：会话键含用户身份，所以这个 id 泄露出去也读不到别人的会话。
 */
const sessionId = ref(newSessionId())

function newSessionId(): string {
  // randomUUID 需要安全上下文（https 或 localhost）。内网 http 部署下拿不到，
  // 这时退回 getRandomValues——**不能退回 Math.random**，那会让并发用户的会话 id 撞上
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** 开一段新对话。旧会话的上下文就此不再被引用，不会再影响后续提问。 */
function resetSession(): void {
  controller?.abort()
  sessionId.value = newSessionId()
  activeConversationId.value = null
  turns.value = []
}

/* ── 历史会话 ─────────────────────────────────────────────────────────── */

const HISTORY_PAGE_SIZE = 20
const historyOpen = ref(false)
const history = ref<ConversationSummary[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historyKeyword = ref('')
const historyLoading = ref(false)
const historyError = ref<string | null>(null)
/** 当前正在看的会话。null = 还没落库的新会话（或刚点了「新会话」）。 */
const activeConversationId = ref<string | null>(null)
let historyTimer: number | undefined

async function loadHistory(page = historyPage.value): Promise<void> {
  historyLoading.value = true
  historyError.value = null
  try {
    const data = await listConversations({
      page,
      size: HISTORY_PAGE_SIZE,
      keyword: historyKeyword.value,
    })
    history.value = data.items
    historyTotal.value = data.total
    historyPage.value = data.page
  } catch (e: unknown) {
    historyError.value = errorMessage(e)
  } finally {
    historyLoading.value = false
  }
}

function onHistorySearch(): void {
  window.clearTimeout(historyTimer)
  historyTimer = window.setTimeout(() => {
    void loadHistory(1)
  }, 300)
}

const historyPages = computed(() =>
  Math.max(1, Math.ceil(historyTotal.value / HISTORY_PAGE_SIZE)),
)

function historyGo(page: number): void {
  if (page < 1 || page > historyPages.value || page === historyPage.value) return
  void loadHistory(page)
}

/** 历史消息 → 界面轮次。USER 开一轮，紧跟的 ASSISTANT 填结果或失败。 */
function turnsFromMessages(messages: ConversationMessage[]): Turn[] {
  const out: Turn[] = []
  for (const m of messages) {
    if (m.role === 'USER') {
      out.push({
        id: `h-${m.id}`,
        question: m.content ?? '',
        kind: m.kind === 'recompute' ? 'recompute' : 'question',
        status: 'done',
        stage: null,
        stageMessage: '',
        result: null,
        failure: null,
      })
      continue
    }
    const turn = out[out.length - 1]
    if (!turn) continue
    if (m.status === 'FAILED' || !m.result) {
      turn.status = 'error'
      turn.failure = {
        code: (m.failureCode as FailureCode | null) ?? 'EXECUTION_FAILED',
        message: m.failureMessage ?? '处理失败',
      }
    } else {
      turn.status = 'done'
      turn.result = m.result
    }
  }
  return out
}

async function openConversation(id: string): Promise<void> {
  if (id === activeConversationId.value && turns.value.length > 0) {
    historyOpen.value = false
    return
  }
  controller?.abort()
  historyLoading.value = true
  historyError.value = null
  try {
    const detail = await fetchConversation(id)
    sessionId.value = detail.id
    activeConversationId.value = detail.id
    if (detail.assistantId && assistants.value.some((a) => a.id === detail.assistantId)) {
      assistantId.value = detail.assistantId
    }
    turns.value = turnsFromMessages(detail.messages)
    draft.value = ''
    await refreshCapabilities()
    await scrollToEnd()
    historyOpen.value = false
  } catch (e: unknown) {
    historyError.value = errorMessage(e)
  } finally {
    historyLoading.value = false
  }
}

async function removeConversation(id: string): Promise<void> {
  if (!window.confirm('删除这段会话？删除后不会出现在历史里。')) return
  try {
    await deleteConversation(id)
    if (activeConversationId.value === id) resetSession()
    await loadHistory(historyPage.value > 1 && history.value.length === 1
      ? historyPage.value - 1
      : historyPage.value)
  } catch (e: unknown) {
    historyError.value = errorMessage(e)
  }
}

/** 列表里的时间：今天只显示时分，今年显示月日，更早显示年月日。 */
function formatTime(raw: string): string {
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  if (d.toDateString() === now.toDateString()) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.getFullYear() === now.getFullYear()) return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const busy = computed(() => turns.value.some((t) => t.status === 'streaming'))
const canSubmit = computed(() => draft.value.trim().length > 0 && !busy.value)

/** 阶段的中文说明。后端也带了 message，但前端保留一份用于离线/降级场景。 */
const STAGE_LABEL: Record<AskStage, string> = {
  CHECKING_PERMISSION: '校验权限',
  GENERATING_DSL: '理解问题',
  RENDERING_SQL: '生成查询',
  EXECUTING: '查询数据',
}

const STAGE_ORDER: AskStage[] = [
  'CHECKING_PERMISSION',
  'GENERATING_DSL',
  'RENDERING_SQL',
  'EXECUTING',
]

function stageIndex(stage: AskStage | null): number {
  return stage ? STAGE_ORDER.indexOf(stage) : -1
}

async function scrollToEnd(): Promise<void> {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

// 新消息进来时滚到底。用 watch 而不是在每处 set 后手动调——
// 漏掉一处就会出现"新答案在屏幕外"。
watch(() => turns.value.length, scrollToEnd)

async function submit(): Promise<void> {
  if (!canSubmit.value) return

  const question = draft.value.trim()
  draft.value = ''

  const turn: Turn = {
    id: `${Date.now()}`,
    question,
    kind: 'question',
    status: 'streaming',
    stage: null,
    stageMessage: '',
    result: null,
    failure: null,
  }
  turns.value = [...turns.value, turn]
  await scrollToEnd()

  controller = new AbortController()
  try {
    await askStream(
      {
        question,
        sessionId: sessionId.value,
        ...(assistantId.value ? { assistantId: assistantId.value } : {}),
      },
      (event) => applyEvent(turn.id, event),
      controller.signal,
    )
    // 流正常结束但没收到 result/error——后端异常退出时会这样
    finishIfPending(turn.id, {
      code: 'EXECUTION_FAILED',
      message: '服务未返回结果，请重试',
    })
  } catch (e: unknown) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      finishIfPending(turn.id, { code: 'EXECUTION_FAILED', message: '已取消' })
    } else {
      finishIfPending(turn.id, { code: 'EXECUTION_FAILED', message: errorMessage(e) })
    }
  } finally {
    controller = null
    // 这一轮结束后刷新第一页：新会话出现、排序更新、预览更新
    void loadHistory(1)
  }
}

/** 把事件落到对应的那一轮上。**不可变更新**——直接改对象会让 Vue 的依赖追踪失效。 */
function applyEvent(turnId: string, event: AskEvent): void {
  // 后端在 result/error 里回传会话 id：新会话第一次提问后，本地 id 在这里落定
  const conversationId = 'conversationId' in event ? event.conversationId : undefined
  if (conversationId) {
    sessionId.value = conversationId
    activeConversationId.value = conversationId
  }
  // 每条结果都先准备好反馈状态：模板里 v-model 需要有稳定对象可写。
  // 在这里建而不是渲染时建——渲染函数里改状态会触发 Vue 的递归更新警告
  if (event.type === 'result' && !feedback.value[turnId]) {
    feedback.value = {
      ...feedback.value,
      [turnId]: {
        open: false,
        // 预填**参数已内联**的版本：用户要在此基础上改 SQL，
        // 给他一句带 `?` 的模板无法编辑
        sql: event.sqlDisplay ?? event.sql ?? '',
        note: '',
        submitting: false,
        done: false,
        error: null,
      },
    }
  }

  turns.value = turns.value.map((t) => {
    if (t.id !== turnId) return t

    switch (event.type) {
      case 'started':
        return { ...t }
      case 'progress':
        return { ...t, stage: event.stage, stageMessage: event.message }
      case 'result':
        return { ...t, status: 'done' as const, stage: null, result: event }
      case 'error':
        return {
          ...t,
          status: 'error' as const,
          stage: null,
          failure: { code: event.code, message: event.message },
        }
    }
  })
  void scrollToEnd()
}

/** 流结束时若那一轮还没结论，补一个失败。 */
function finishIfPending(turnId: string, failure: { code: FailureCode; message: string }): void {
  turns.value = turns.value.map((t) =>
    t.id === turnId && t.status === 'streaming'
      ? { ...t, status: 'error' as const, stage: null, failure }
      : t,
  )
}

/**
 * 放宽条件后重算（后端 FR-5.5，不经过模型）。
 *
 * **新起一轮而不是替换原结果**：替换会把"我试过什么"抹掉，
 * 而用户恰恰需要对比"原来 0 行、放宽后有了"来确认是条件卡住了。
 * 所以这里读起来像一段对话，而不是一次原地刷新。
 */
async function relax(from: Turn, action: Relaxation): Promise<void> {
  if (!from.result) return

  const turn: Turn = {
    id: `${Date.now()}-relax`,
    question: action.note,
    kind: 'recompute',
    status: 'streaming',
    stage: null,
    stageMessage: '正在按新条件重算',
    result: null,
    failure: null,
  }
  turns.value = [...turns.value, turn]
  await scrollToEnd()

  try {
    const result = await recompute({
      // 原提问只用于审计留痕——重算不是一次新提问
      question: from.question,
      sessionId: sessionId.value,
      ...(assistantId.value ? { assistantId: assistantId.value } : {}),
      dsl: action.apply(from.result.dsl!),
    })
    applyEvent(turn.id, result)
  } catch (e: unknown) {
    finishIfPending(turn.id, { code: 'EXECUTION_FAILED', message: errorMessage(e) })
  }
}

/**
 * 一次结果的反馈草稿。
 *
 * 按 turn 存而不是全局一份：用户可能对多条答案都提反馈，
 * 共用一份会让"刚改好的 SQL"被下一条答案覆盖。
 */
interface TurnFeedback {
  open: boolean
  /** 修正后的 SQL。初始值就是系统生成的 SQL，用户改了才算修正 */
  sql: string
  note: string
  submitting: boolean
  done: boolean
  error: string | null
}

const feedback = ref<Record<string, TurnFeedback>>({})

function patchFeedback(turn: Turn, patch: Partial<TurnFeedback>): void {
  const current = feedback.value[turn.id]
  if (!current) return
  feedback.value = { ...feedback.value, [turn.id]: { ...current, ...patch } }
}

function toggleFeedback(turn: Turn): void {
  const current = feedback.value[turn.id]
  if (!current) return
  patchFeedback(turn, { open: !current.open })
}

/** 正向反馈：一键提交，不打断阅读。 */
async function sendHelpful(turn: Turn): Promise<void> {
  await sendFeedback(turn, 'HELPFUL')
}

/**
 * 提交负向反馈。
 *
 * 判断类型的唯一依据是 **SQL 有没有被改动**：
 * - 改了 → SQL_CORRECTION，审核通过后会进范例库
 * - 没改 → WRONG_ANSWER，只用于统计
 *
 * 不用"用户点了哪个按钮"来判断：点了「答得不对」但只写了备注，
 * 说明他说不清正确写法，这时硬塞一条 SQL 只会给审核人添乱。
 */
async function sendWrongAnswer(turn: Turn): Promise<void> {
  const current = feedback.value[turn.id]
  if (!current) return
  // 与预填时用的同一个版本比较，否则「没改过」也会被判成 SQL_CORRECTION
  const generated = (turn.result?.sqlDisplay ?? turn.result?.sql ?? '').trim()
  const corrected = current.sql.trim()
  // 能力答复（「这里能分析哪些数据」）没有对应的数据问题，
  // 把它的修正 SQL 收进范例库会让下一个能力问题被教着写 SQL——与规则 6 相反
  const canCorrect = !turn.result?.answer
  const type: FeedbackType =
    canCorrect && corrected && corrected !== generated ? 'SQL_CORRECTION' : 'WRONG_ANSWER'
  await sendFeedback(turn, type)
}

async function sendFeedback(turn: Turn, type: FeedbackType): Promise<void> {
  if (!turn.result) return
  const current = feedback.value[turn.id]
  if (!current) return

  patchFeedback(turn, { submitting: true, error: null })
  try {
    await submitFeedback({
      // 反馈必须落在产生这条答案的域上；缺了它后端会拒绝
      domainId: turn.result.domainId ?? '',
      assistantId: assistantId.value || undefined,
      question: turn.question,
      // 与展示给用户的是同一份，管理员审核时才对得上
      generatedSql: turn.result.sqlDisplay ?? turn.result.sql ?? null,
      correctedSql: type === 'SQL_CORRECTION' ? current.sql.trim() : null,
      note: current.note.trim() || null,
      type,
    })
    patchFeedback(turn, { submitting: false, done: true, open: false })
  } catch (e: unknown) {
    patchFeedback(turn, { submitting: false, error: errorMessage(e) })
  }
}

function cancel(): void {
  controller?.abort()
}

async function logout(): Promise<void> {
  // 会话在网关，必须让网关清（只清本地状态的话，刷新一次又"登录"回来了）
  try {
    await bffLogout()
  } finally {
    session.clear()
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <div class="shell">
    <header class="masthead">
      <div class="masthead-brand">
        <span class="logo" aria-hidden="true">问</span>
        <span class="wordmark">问数</span>
        <span class="masthead-meta mono">{{ domainName ?? '—' }}</span>
      </div>
      <div class="who">
        <button
          class="btn btn-quiet btn-sm history-toggle"
          type="button"
          :aria-expanded="historyOpen"
          @click="historyOpen = !historyOpen"
        >
          历史
        </button>
        <!-- 新会话。它做的事只有一件：换掉会话 id，从而让上一段对话的上下文
             不再被带进下一个问题。没有这个按钮，用户唯一的"重新开始"办法
             是刷新页面——而刷新还会顺手清掉眼前的答案。 -->
        <button
          v-if="turns.length > 0"
          class="btn btn-quiet btn-sm"
          type="button"
          @click="resetSession"
        >
          新会话
        </button>
        <UserMenu
          :name="session.user.value?.realname || session.user.value?.username || '我'"
          :username="session.user.value?.username"
          :meta="session.user.value?.canConfig ? '管理台账号' : '业务账号'"
          @logout="logout"
        >
          <!-- 只有管理台角色才显示。与后端 requireAdmin 的判据一致，
               但**前端判断只是提前隐藏入口**——真正的闸门在服务端 -->
          <RouterLink
            v-if="session.user.value?.canConfig"
            class="user-pop-item"
            to="/admin"
          >
            管理台
          </RouterLink>
        </UserMenu>
      </div>
    </header>

    <div class="workspace">
      <aside class="history" :class="{ open: historyOpen }" aria-label="历史会话">
        <div class="history-head">
          <span class="history-title">历史会话</span>
          <button class="history-new" type="button" @click="resetSession(); historyOpen = false">
            ＋ 新会话
          </button>
        </div>
        <input
          v-model="historyKeyword"
          class="input history-search"
          type="search"
          placeholder="搜索问题或标题…"
          @input="onHistorySearch"
          @keydown.enter.prevent="loadHistory(1)"
        />
        <p v-if="historyError" class="note note-danger history-error" role="alert">
          {{ historyError }}
        </p>
        <div class="history-list">
          <p v-if="historyLoading && history.length === 0" class="history-empty">加载中…</p>
          <p v-else-if="history.length === 0" class="history-empty">
            {{ historyKeyword ? '没有匹配的会话' : '还没有历史会话' }}
          </p>
          <div
            v-for="c in history"
            :key="c.id"
            class="history-item"
            :class="{ active: c.id === activeConversationId }"
            role="button"
            tabindex="0"
            @click="openConversation(c.id)"
            @keydown.enter.prevent="openConversation(c.id)"
          >
            <span class="history-item-title">{{ c.title }}</span>
            <span class="history-item-time">{{ formatTime(c.updatedAt) }}</span>
            <p class="history-item-preview">{{ c.preview || '—' }}</p>
            <button
              class="history-del"
              type="button"
              aria-label="删除会话"
              title="删除会话"
              @click.stop="removeConversation(c.id)"
            >
              ×
            </button>
          </div>
        </div>
        <div v-if="historyPages > 1" class="history-foot">
          <button class="history-page" type="button" :disabled="historyPage <= 1" @click="historyGo(historyPage - 1)">
            上一页
          </button>
          <span class="history-page-info">{{ historyPage }} / {{ historyPages }}</span>
          <button
            class="history-page"
            type="button"
            :disabled="historyPage >= historyPages"
            @click="historyGo(historyPage + 1)"
          >
            下一页
          </button>
        </div>
      </aside>

      <div class="main-col">
    <!-- 助理选择。放在顶栏下方而不是塞进顶栏里——
         它带欢迎语，顶栏放不下，而欢迎语正是用户第一次进来该看到的东西 -->
    <div class="assistant-bar">
      <span class="assistant-label">助理</span>

      <template v-if="assistants.length > 1">
        <select id="as-pick" v-model="assistantId" class="assistant-pick" @change="onAssistantChange">
          <option v-for="a in assistants" :key="a.id" :value="a.id">
            {{ a.avatar || '🤖' }} {{ a.name }}
          </option>
        </select>
      </template>

      <!-- 只有一个助理时**不渲染下拉**：一个只有一个选项的下拉是纯噪音，
           而且会让人以为"切换坏了"。改成静态标签，并说清为什么只有它。
           一个都没有时也不能整条藏起来——那样用户只会看到一句莫名其妙的
           默认域提示，不知道是权限问题。 -->
      <template v-else-if="assistants.length === 1">
        <span class="assistant-only">
          {{ assistants[0]!.avatar || '🤖' }} {{ assistants[0]!.name }}
        </span>
      </template>

      <span v-else class="assistant-note">
        你的账号还没有可用的助理，请联系管理员授权
      </span>

      <!-- 当前落点。**常驻显示**——它此前只在空状态里出现过，
           于是有了问答之后，切换助理在页面上看不出任何变化 -->
      <span v-if="domainName" class="assistant-domain">
        正在问 <strong>{{ domainName }}</strong>
      </span>

      <span v-if="currentAssistant?.welcome" class="assistant-welcome">
        {{ currentAssistant.welcome }}
      </span>
      <span v-if="currentAssistant?.hasMultipleDomains" class="assistant-note">
        该助理绑定了多个数据域，当前用主域（智能路由尚未实现）
      </span>
    </div>

    <main ref="scroller" class="stream" aria-live="polite">
      <div v-if="turns.length === 0" class="empty reveal">
        <span class="empty-pill"><i aria-hidden="true" />智能问数 · 已就绪</span>
        <h1 class="empty-title">问点什么，<span>用你的话</span></h1>
        <p class="empty-sub">指标、维度、时间都可以直接写进去。AI 会先复述理解，再查数据。</p>

        <!-- 推荐问题（FR-4.5）。**这一段是"内容随助理变化"的主要体现**：
             此前这里写死了一句保险业务的例子，切到别的助理仍然照说，
             用户会以为切换根本没生效。现在按当前助理的配置给。
             助理没配推荐问题时不留空档——退回下面的字段引导。 -->
        <div v-if="currentAssistant?.questions.length" class="capability">
          <p class="capability-label">推荐问题</p>
          <ol class="ask-index">
            <li v-for="(q, i) in currentAssistant.questions" :key="q">
              <button type="button" class="index-item" @click="draft = q">
                <span class="index-no">{{ String(i + 1).padStart(2, '0') }}</span>
                <span class="index-text">{{ q }}</span>
                <span class="index-arrow" aria-hidden="true">→</span>
              </button>
            </li>
          </ol>
        </div>

        <!-- 直接回答"你能问哪些数据"。
             用户真的问过这个问题，而让模型把它翻译成 SQL 只会失败——
             那是能力问题，不是数据问题，界面直接说清楚。 -->
        <div v-if="fields.length" class="capability">
          <p class="capability-label">
            {{ domainName ? `可用字段 · ${domainName}` : '可用字段' }}
          </p>
          <ul class="field-list">
            <li v-for="f in fields" :key="f">
              <button type="button" class="field-item" @click="draft = `按${f}看看数据`">
                {{ f }}
              </button>
            </li>
          </ul>
          <p class="capability-note">
            点一下会填入输入框，你可以改成自己的说法再发送。
          </p>
        </div>
      </div>

      <article v-for="turn in turns" :key="turn.id" class="turn">
        <div class="asked" :class="{ 'asked-action': turn.kind === 'recompute' }">
          <span class="asked-badge" aria-hidden="true">
            {{ turn.kind === 'recompute' ? '调' : userInitial }}
          </span>
          <!-- 重算的标题是系统生成的，用另一种样式标出来，
               免得被当成"用户自己问过的问题" -->
          <p v-if="turn.kind === 'recompute'" class="question question-action">
            <span class="action-tag">调整条件</span>{{ turn.question }}
          </p>
          <p v-else class="question">{{ turn.question }}</p>
        </div>
        <div class="turn-body">

        <!-- 重算没有"理解问题"那一步，阶段只有两个，画步进器是噪音 -->
        <div v-if="turn.status === 'streaming' && turn.kind === 'recompute'" class="stages">
          <span class="sub">{{ turn.stageMessage }}…</span>
        </div>

        <!-- 进行中：阶段步进器。
             它比一个转圈的 spinner 更有信息量——用户知道卡在哪一步。 -->
        <div v-else-if="turn.status === 'streaming'" class="stages">
          <ol class="stage-list">
            <li
              v-for="(s, i) in STAGE_ORDER"
              :key="s"
              class="stage"
              :class="{
                'stage-done': i < stageIndex(turn.stage),
                'stage-active': i === stageIndex(turn.stage),
              }"
            >
              <span class="stage-dot" />
              <span class="stage-label">{{ STAGE_LABEL[s] }}</span>
            </li>
          </ol>
          <button class="btn btn-quiet btn-sm" type="button" @click="cancel">停止</button>
        </div>

        <p v-else-if="turn.failure" class="note note-danger" role="alert">
          {{ turn.failure.message }}
        </p>

        <template v-else-if="turn.result">
          <!-- 放在结果**之前**，且 0 行时也显示：它解释的是"我按什么去查的"，
               而"查不到"恰恰是最需要看这份解释的时候 -->
          <UnderstandingBlock
            v-if="turn.result.understanding"
            :understanding="turn.result.understanding"
          />
          <!-- 直连模式没有行/列权限，必须让用户知道——否则他会以为看到的
               数据范围和别人一样。这是该模式唯一的"能力降级"提示位 -->
          <p v-if="turn.result.mode === 'RAW'" class="note note-warn">
            本次由「直连模式」数据集回答：模型自己写 SQL，<strong>未启用行级/列级权限</strong>。
            可访问的表由数据集配置决定。
          </p>

          <!-- 能力答复：不是一次查数，所以既没有表格也不该说"无数据" -->
          <p v-if="turn.result.answer" class="answer">{{ turn.result.answer }}</p>

          <template v-else-if="turn.result.rowCount === 0">
            <p class="note">
              查询成功，但这个条件下没有数据。
            </p>

            <!-- 有可放宽的条件就给按钮；一个都没有时**不给按钮**，
                 而是直说原因——一个点了什么都不变的按钮比没有更糟 -->
            <div
              v-if="turn.result.dsl && relaxationsFor(turn.result.dsl).length"
              class="relax"
            >
              <span class="label relax-label">放宽条件再试</span>
              <button
                v-for="r in relaxationsFor(turn.result.dsl!)"
                :key="r.label"
                type="button"
                class="btn btn-quiet btn-sm"
                @click="relax(turn, r)"
              >
                {{ r.label }}
              </button>
            </div>
            <p v-else class="note">
              这次没有时间范围、也没有筛选条件，已经没有可放宽的东西了——
              多半是这个指标在库里确实没有数据。
            </p>
          </template>

          <ResultView
            v-else-if="!turn.result.answer"
            :columns="turn.result.columns"
            :rows="turn.result.rows"
            :masked-columns="turn.result.maskedColumns"
            :chart="turn.result.chart"
          />

          <details class="sql">
            <summary>
              查看 SQL
              <span class="sub">· {{ turn.result.rowCount }} 行 · {{ turn.result.durationMs }}ms</span>
            </summary>
            <!-- 展示**参数已内联**的版本：后端 `sql` 里是 `?` 占位符，
                 用户看不出查了什么条件（例如 `WHERE org_path LIKE ?`）。
                 旧后端没有 sqlDisplay，回退到 sql -->
            <pre class="mono">{{ turn.result.sqlDisplay ?? turn.result.sql }}</pre>
          </details>

          <!-- 反馈。**提交不等于生效**：修正 SQL 要管理员审核通过后才进范例库，
               这里把这一点直说，避免用户以为下一次问数就会变 -->
          <!-- 重算轮次不给反馈：它回答的是「放宽条件后会怎样」，
               不是对原问题的正确答案。收进范例库会教模型把原问题写成放宽后的查询 -->
          <div
            v-if="turn.kind !== 'recompute' && can(P.ASK_UI_FEEDBACK)"
            class="feedback"
          >
            <template v-if="feedback[turn.id]?.done">
              <span class="feedback-done">已收到反馈，管理员审核后会用于改进问数。</span>
            </template>
            <template v-else>
              <span class="label feedback-label">这条回答</span>
              <button
                class="btn btn-quiet btn-sm"
                type="button"
                :disabled="feedback[turn.id]?.submitting"
                @click="sendHelpful(turn)"
              >
                有帮助
              </button>
              <button
                class="btn btn-quiet btn-sm"
                type="button"
                :disabled="feedback[turn.id]?.submitting"
                @click="toggleFeedback(turn)"
              >
                答得不对
              </button>
            </template>

            <div v-if="feedback[turn.id]?.open && !feedback[turn.id]?.done" class="feedback-form">
              <label class="label" :for="`fb-note-${turn.id}`">哪里不对？（可选）</label>
              <textarea
                :id="`fb-note-${turn.id}`"
                v-model="feedback[turn.id]!.note"
                class="input"
                rows="2"
                placeholder="例如：漏了「有效」这个条件"
              />

              <!-- 能力答复没有可修正的查询对象，只收文字反馈 -->
              <!-- 修正 SQL 单独一个码：审核通过后它会进范例库，
                   成为之后相似问题的 few-shot，比"点个有帮助"强得多 -->
              <template v-if="!turn.result.answer && can(P.ASK_UI_FEEDBACK_CORRECT)">
                <label class="label" :for="`fb-sql-${turn.id}`">
                  修正后的 SQL（可选；改动了才会作为「修正 SQL」提交审核）
                </label>
                <textarea
                  :id="`fb-sql-${turn.id}`"
                  v-model="feedback[turn.id]!.sql"
                  class="input mono"
                  rows="5"
                  spellcheck="false"
                />
              </template>

              <div class="feedback-actions">
                <button
                  class="btn btn-primary btn-sm"
                  type="button"
                  :disabled="feedback[turn.id]?.submitting"
                  @click="sendWrongAnswer(turn)"
                >
                  {{ feedback[turn.id]?.submitting ? '提交中…' : '提交反馈' }}
                </button>
                <button class="btn btn-quiet btn-sm" type="button" @click="toggleFeedback(turn)">
                  取消
                </button>
                <span v-if="feedback[turn.id]?.error" class="note note-danger" role="alert">
                  {{ feedback[turn.id]!.error }}
                </span>
              </div>
            </div>
          </div>
        </template>
        </div>
      </article>
    </main>

    <footer class="composer">
      <form class="composer-form" @submit.prevent="submit">
        <label class="sr-only" for="q">提问</label>
        <div class="composer-box">
          <textarea
            id="q"
            v-model="draft"
            class="composer-input"
            rows="1"
            placeholder="用日常说法提问…"
            :disabled="busy"
            @keydown.enter.exact.prevent="submit"
          />
          <button
            class="send"
            type="submit"
            :disabled="!canSubmit"
            :aria-label="busy ? '查询中' : '发送'"
          >
            <span v-if="busy" class="send-spinner" aria-hidden="true" />
            <span v-else aria-hidden="true">↑</span>
          </button>
        </div>
        <p class="composer-hint">
          Enter 发送 · Shift + Enter 换行
          <span v-if="domainName"> · 当前域 {{ domainName }}</span>
        </p>
      </form>
    </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shell {
  height: 100dvh;
  display: grid;
  /* 报头常驻，下面整块（侧栏 + 主列）占满剩余高度。
     主列内部再分 助理条 / 消息流 / 输入区，消息流是唯一可滚动的那一行。 */
  grid-template-rows: auto minmax(0, 1fr);
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

.workspace {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 20px;
  min-height: 0;
}

.main-col {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 0;
}

/* ── 历史会话侧栏 ── */
.history {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 16px;
  padding: 14px 12px 10px;
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px 10px;
}

.history-title {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ink-soft);
}

.history-new {
  padding: 3px 8px;
  border: 0;
  border-radius: 7px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: 10.5px;
  font-weight: 650;
  cursor: pointer;
}

.history-search {
  margin: 0 2px 10px;
  padding: 7px 10px;
  font-size: 11.5px;
}

.history-error {
  margin: 0 2px 8px;
  font-size: 10.5px;
}

.history-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 2px;
}

.history-empty {
  padding: 22px 8px;
  text-align: center;
  font-size: 11px;
  color: var(--ink-faint);
}

.history-item {
  position: relative;
  padding: 8px 26px 8px 9px;
  border-radius: 9px;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out);
}

.history-item:hover {
  background: var(--surface-sunk);
}

.history-item.active {
  background: var(--accent-soft);
}

.history-item-title {
  display: block;
  overflow: hidden;
  font-size: 11.5px;
  font-weight: 650;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-time {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 9.5px;
  color: var(--ink-faint);
}

.history-item-preview {
  overflow: hidden;
  margin: 3px 0 0;
  font-size: 10.5px;
  line-height: 1.45;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item:hover .history-item-time {
  opacity: 0;
}

.history-del {
  position: absolute;
  top: 6px;
  right: 5px;
  display: none;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-faint);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.history-item:hover .history-del {
  display: block;
}

.history-del:hover {
  background: var(--danger-soft, #fee);
  color: var(--danger-ink, #b42318);
}

.history-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}

.history-page {
  padding: 3px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 10px;
  cursor: pointer;
}

.history-page:disabled {
  opacity: 0.4;
  cursor: default;
}

.history-page-info {
  font-size: 10px;
  color: var(--ink-faint);
}

/* 窄屏才把侧栏收成浮层；宽屏常驻 */
.history-toggle {
  display: none;
}

/* ── 报头 ──
 * 白底 + 轻微模糊，和页面浅蓝灰底分层。 */
.masthead {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 15px 0 11px;
  background: color-mix(in srgb, var(--paper) 86%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
}

.masthead-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
  box-shadow: 0 7px 16px rgba(0, 0, 0, 0.14);
}

.wordmark {
  font-family: var(--font-display);
  font-size: var(--text-base);
  font-weight: 750;
  letter-spacing: 0.04em;
  color: var(--ink);
  line-height: 1;
}

.masthead-meta {
  overflow: hidden;
  margin-left: 4px;
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.who {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ── 助理选择 ── */
.assistant-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0 0;
  padding: 11px 14px;
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  flex-wrap: wrap;
}

.assistant-label {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-faint);
}

.assistant-pick {
  padding: 6px 10px;
  background: var(--surface-sunk);
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  font-size: 12px;
  color: var(--ink);
  transition: border-color var(--duration-fast) var(--ease-out);
}

.assistant-pick:hover {
  border-color: var(--ink);
}

/* 单助理时的静态标签，与下拉占同样的位置，视觉上不跳 */
.assistant-only {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
  font-size: 11.5px;
  font-weight: 650;
  color: var(--accent-deep);
}

/* 当前落点。靠右一点，与"选哪个助理"区分开——前者是输入，后者是结果 */
.assistant-domain {
  margin-left: auto;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--ok-soft);
  font-size: 10.5px;
  color: var(--ok-ink);
}

.assistant-domain strong {
  font-weight: 700;
}

.assistant-welcome {
  flex-basis: 100%;
  font-size: 11.5px;
  color: var(--ink-soft);
}

/* 多域提示：说清楚"为什么同一个问题有时落这个域" */
.assistant-note {
  flex-basis: 100%;
  font-size: 10.5px;
  color: var(--warn-ink);
}

/* ── 消息流 ── */
.stream {
  overflow-y: auto;
  padding: 20px 0 24px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  min-height: 0;
}

/* 空状态：一句话主张 + 可点的推荐问题卡片 */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin: auto 0;
  padding: 30px 0 20px;
  text-align: center;
}

.empty-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border-radius: var(--radius-pill);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  font-size: 10.5px;
  color: var(--ink-soft);
}

.empty-pill i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
}

.empty-title {
  margin-top: 18px;
  font-size: clamp(1.9rem, 1.2rem + 2.4vw, 2.8rem);
  font-weight: 750;
  letter-spacing: -0.02em;
  color: var(--ink);
}

.empty-title span {
  /* 淡黄绿做**背景高亮**，文字仍是近黑——这是强调色唯一安全又有辨识度的用法 */
  background: var(--accent);
  color: var(--ink);
  padding: 0 0.16em;
  border-radius: 3px;
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
}

.empty-sub {
  max-width: 48ch;
  margin-top: 12px;
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.75;
}

/* ── 能力面板 ──
 * 空状态里最有用的东西。用户不知道能问什么时，列出来比让他猜要好。 */
.capability {
  width: 100%;
  max-width: 760px;
  margin-top: 28px;
  text-align: left;
}

.capability-label {
  margin-bottom: 10px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ink-faint);
}

/* 推荐问题：两列卡片，点一下填进输入框 */
.ask-index {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.index-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 100%;
  padding: 13px 14px;
  background: var(--surface);
  border: none;
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  text-align: left;
  transition:
    transform var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.index-item:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow);
}

.index-no {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: var(--accent-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent-deep);
}

.index-text {
  font-size: 12px;
  line-height: 1.55;
  color: var(--ink);
}

.index-arrow {
  color: var(--ink-faint);
  transition:
    color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.index-item:hover .index-arrow {
  color: var(--accent-deep);
  transform: translateX(3px);
}

/* 字段是轻量的可点胶囊 */
.field-list {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.field-item {
  padding: 5px 10px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  font-size: 11px;
  color: var(--ink-soft);
  transition:
    color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out);
}

.field-item:hover {
  background: var(--accent-soft);
  border-color: transparent;
  color: var(--accent-deep);
}

.capability-note {
  margin-top: 10px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* 一条问答 = 一条用户气泡 + 一张答案卡片 */
.turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.asked {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 9px;
}

.asked-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  order: 2;
  border-radius: 50%;
  background: var(--off-soft);
  color: var(--accent-deep);
  font-size: 10.5px;
  font-weight: 700;
}

/* 提问是用户自己打的字，靠右成气泡；重算的说明是系统生成，靠左且更轻 */
.question {
  order: 1;
  max-width: 78%;
  padding: 10px 15px;
  border-radius: var(--radius-lg) var(--radius-lg) var(--radius-sm) var(--radius-lg);
  background: var(--gradient-accent);
  color: #fff;
  font-size: 13px;
  font-weight: 550;
  line-height: 1.6;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.12);
}

.question-action {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--surface);
  color: var(--ink-soft);
  font-weight: 500;
  box-shadow: var(--shadow-sm);
  border-radius: 12px;
}

.asked-action {
  justify-content: flex-start;
}

.asked-action .asked-badge {
  order: 0;
  background: var(--accent-soft);
}

.action-tag {
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: 9.5px;
  font-weight: 600;
}

.turn-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 16px 18px;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

/* 卡片内的提示条：白底上再放白卡会看不见边界，改用凹陷底色 */
.turn-body :deep(.note:not(.note-danger):not(.note-warn)) {
  background: var(--surface-sunk);
  border-color: transparent;
}

/* 能力答复：就是一段话 */
.answer {
  margin: 0;
  padding: 2px 0 2px 13px;
  border-left: 3px solid var(--accent-soft);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
}

/* ── 放宽条件 ── */
.relax {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.relax-label {
  color: var(--ink-faint);
}

/* ── 阶段步进器 ── */
.stages {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface-sunk);
}

.stage-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.stage {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: var(--ink-faint);
  transition: color var(--duration) var(--ease-out);
}

.stage-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--line-strong);
  transition:
    background-color var(--duration) var(--ease-out),
    transform var(--duration) var(--ease-out);
}

.stage-done {
  color: var(--ink-soft);
}

.stage-done .stage-dot {
  background: var(--ok);
}

/* 当前阶段：点放大 + 呼吸。动的是 transform 与 opacity，都在合成层 */
.stage-active {
  color: var(--accent-deep);
  font-weight: 600;
}

.stage-active .stage-dot {
  /* 淡黄绿在白底上几乎看不见；指示点必须是强信号 */
  background: var(--ink);
  transform: scale(1.5);
  animation: pulse 1.4s var(--ease-in-out) infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50%      { opacity: 0.35; }
}

/* ── 数据表 ── */
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
  padding: var(--space-3) var(--space-4);
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
  position: sticky;
  top: 0;
}

.data tbody tr:last-child td {
  border-bottom: none;
}

.data tbody tr:hover td {
  background: var(--surface-sunk);
}

/* 数字右对齐 + 等宽：让量级可以竖向比较。
   这是数据表的实际需要，不是装饰。 */
.data td.num {
  text-align: right;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.mask-tag {
  display: inline-block;
  margin-left: var(--space-2);
  padding: 0.1em 0.45em;
  border-radius: var(--radius-sm);
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 0.85em;
  font-weight: 600;
  letter-spacing: 0;
}

/* ── SQL ── */
.sql {
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: transparent;
}

.sql summary {
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink-soft);
  user-select: none;
}

.sql summary:hover {
  color: var(--ink);
}

.sql pre {
  margin: 0;
  padding: var(--space-4);
  border-top: 1px solid var(--line);
  background: var(--surface-sunk);
  border-radius: 0 0 var(--radius-sm) var(--radius-sm);
  overflow-x: auto;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ── 输入区 ── */
.composer {
  position: sticky;
  bottom: 0;
  z-index: 4;
  padding: 10px 0 18px;
  background: linear-gradient(to top, var(--paper) 70%, transparent);
}

.composer-form {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 10px 10px 16px;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  transition: box-shadow var(--duration-fast) var(--ease-out);
}

.composer-box:focus-within {
  box-shadow: var(--shadow), var(--focus-ring);
}

.composer-input {
  flex: 1;
  min-width: 0;
  resize: none;
  min-height: 30px;
  max-height: 180px;
  padding: 6px 0;
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
  font-size: 13px;
  line-height: 1.65;
  field-sizing: content;
}

.composer-input:focus,
.composer-input:focus-visible {
  box-shadow: none;
  outline: none;
}

.composer-input:disabled {
  opacity: 0.6;
}

.send {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: 12px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.18);
  transition:
    transform var(--duration-fast) var(--ease-out),
    opacity var(--duration-fast) var(--ease-out);
}

.send:hover:not(:disabled) {
  transform: translateY(-1px);
}

.send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  box-shadow: none;
}

.send-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.composer-hint {
  padding: 0 4px;
  text-align: center;
  font-size: 10px;
  color: var(--ink-faint);
}

/* ── 反馈 ── */
.feedback {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-top: 2px;
  padding-top: 11px;
  border-top: 1px solid var(--line);
}

.feedback-label {
  margin-right: var(--space-1);
}

.feedback-done {
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.feedback-form {
  flex-basis: 100%;
  display: grid;
  gap: 8px;
  margin-top: 6px;
  padding: 14px;
  border-radius: 12px;
  background: var(--surface-sunk);
}

.feedback-form .input {
  resize: vertical;
}

.feedback-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

@media (max-width: 900px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }

  .history-toggle {
    display: inline-flex;
  }

  .history {
    display: none;
    position: fixed;
    inset: 66px 14px auto 14px;
    z-index: 20;
    max-height: min(520px, calc(100dvh - 96px));
    margin-top: 0;
    box-shadow: var(--shadow);
  }

  .history.open {
    display: flex;
  }
}

@media (max-width: 720px) {
  .shell {
    padding: 0 14px;
  }

  .masthead {
    padding-top: 12px;
  }

  .masthead-meta {
    display: none;
  }

  .assistant-bar {
    margin-top: 12px;
  }

  .assistant-welcome {
    display: none;
  }

  .ask-index {
    grid-template-columns: 1fr;
  }

  .question {
    max-width: 86%;
    font-size: 12.5px;
  }

  .turn-body {
    padding: 14px;
  }

  .stages {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .composer-hint {
    display: none;
  }
}
</style>
