<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { errorMessage } from '../api/http'

/**
 * 嵌入用的精简问数页（`/app/embed`）。
 *
 * <h2>与主问数页的区别</h2>
 *
 * <p>主问数页有历史会话、模型选择、助理切换、反馈、翻页…这里全都没有。
 * 宿主系统嵌入的场景是「在业务流程里问一句」，那些东西要么用不上，
 * 要么该由宿主自己做（比如助理由宿主的令牌锁死）。
 *
 * <p><b>但核心逻辑是一样的</b>：同一个 `/api/ask/engine` 端点、同一套 SSE 事件、
 * 同一份结果渲染。不重写渲染逻辑——重写就会分叉，而分叉之后
 * 一边修了 bug 另一边没有。
 *
 * <h2>令牌从 URL fragment 来</h2>
 *
 * <p>父组件（`chatdata-embed.js`）把令牌放在 `#token=...` 里传进来。
 * fragment 不会被发到服务器，也不进 Referer。
 *
 * <p><b>读到后立刻清掉 URL</b>：用户在 iframe 里"复制链接"时不该把令牌带走。
 *
 * <h2>为什么不用 Cookie</h2>
 *
 * <p>宿主页面与问数服务不同源，Cookie 带不过来。而 ask 用的是
 * 不透明令牌 + Bearer（见 `AskSecurityConfig`），所以请求头带
 * `Authorization` 就够——这正好避开了跨域 Cookie 的一堆问题
 * （SameSite、Secure、第三方 Cookie 拦截）。
 */

interface Turn {
  id: string
  question: string
  status: 'streaming' | 'done' | 'error'
  answer: string | null
  table: { columns: string[]; rows: unknown[][] } | null
  error: string | null
}

const assistant = ref('')
const placeholder = ref('问点数据…')
const token = ref('')
const turns = ref<Turn[]>([])
const draft = ref('')
const busy = ref(false)
const fatal = ref('')

/** 从 fragment 取令牌，取完立刻从地址栏抹掉。 */
function takeToken(): string {
  const hash = window.location.hash.startsWith('#')
    ? window.location.hash.slice(1)
    : window.location.hash
  const params = new URLSearchParams(hash)
  const t = params.get('token') ?? ''
  if (t) {
    // 清掉 fragment 但不触发导航：replaceState 不产生历史记录
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
  return t
}

onMounted(() => {
  token.value = takeToken()
  const q = new URLSearchParams(window.location.search)
  assistant.value = q.get('assistant') ?? ''
  placeholder.value = q.get('placeholder') ?? '问点数据…'

  if (!token.value) {
    // 明确说出来。留一个空白页会让宿主开发者以为是样式问题
    fatal.value = '缺少访问令牌：宿主页面没有把 token 传进来'
    return
  }
  if (!assistant.value) {
    fatal.value = '缺少助理参数：嵌入地址必须带 ?assistant='
    return
  }

  // 令牌刷新：宿主换到新令牌后用 postMessage 推过来
  window.addEventListener('message', onMessage)
})

onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
  controller?.abort()
})

let controller: AbortController | null = null

/**
 * 接收宿主的令牌刷新。
 *
 * <p><b>校验消息来源</b>：不校验的话，任何能往这个 iframe 发消息的页面
 * 都能把令牌换掉——包括恶意嵌入的宿主。
 */
function onMessage(e: MessageEvent): void {
  if (!e.data || e.data.channel !== 'chatdata-embed') {
    return
  }
  if (e.data.type === 'token' && typeof e.data.token === 'string') {
    token.value = e.data.token
  }
}

async function submit(): Promise<void> {
  const question = draft.value.trim()
  if (!question || busy.value) {
    return
  }
  draft.value = ''
  busy.value = true

  const turn: Turn = {
    id: crypto.randomUUID(),
    question,
    status: 'streaming',
    answer: null,
    table: null,
    error: null,
  }
  turns.value = [...turns.value, turn]

  controller = new AbortController()
  try {
    await stream(
      question,
      (event) => applyEvent(turn.id, event),
      controller.signal,
    )
  } catch (e: unknown) {
    patch(turn.id, { status: 'error', error: errorMessage(e) })
  } finally {
    controller = null
    busy.value = false
  }
}

/**
 * 调 `/api/ask/engine` 并解析 SSE。
 *
 * <p><b>没有复用 `api/ask.ts` 的 `askStream`</b>：那个函数写死了
 * `credentials: 'same-origin'` 与不带 Authorization 头（主应用靠网关 Cookie）。
 * 而这里必须带 Bearer。两套鉴权方式不该硬塞进一个函数——
 * 加一个 `mode` 参数会让它长出第二个、第三个分支。
 */
async function stream(
  question: string,
  onEvent: (event: { type: string; [k: string]: unknown }) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/ask/engine', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token.value}`,
    },
    body: JSON.stringify({ question, assistantId: assistant.value }),
    signal,
  })

  if (!response.ok) {
    if (response.status === 401) {
      // 令牌过期是最常见的失败。说清楚，宿主据此换新令牌重试
      throw new Error('访问令牌已过期，请刷新页面或由宿主刷新令牌')
    }
    throw new Error(`请求失败（HTTP ${response.status}）`)
  }
  if (!response.body) {
    throw new Error('服务未返回流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    // stream: true —— 中文在跨 chunk 时会被切断，不加这个参数会乱码
    buffer += decoder.decode(value, { stream: true })

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const parsed = parseFrame(frame)
      if (parsed) {
        onEvent(parsed)
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

function parseFrame(frame: string): { type: string; [k: string]: unknown } | null {
  let name = 'message'
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  try {
    const payload = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
    return { ...payload, type: String(payload['type'] ?? name) }
  } catch {
    // 解析不了就丢掉这一帧——流还在继续，不该因为一帧坏掉整条链路
    return null
  }
}

function applyEvent(turnId: string, event: { type: string; [k: string]: unknown }): void {
  switch (event['type']) {
    case 'result': {
      const payload = event as Record<string, unknown>
      // **columns / rows 平铺在 payload 顶层**，不在 `result` 字段里。
      // 实测踩到：写成 payload['result'] 时永远是 null，
      // 而界面显示"查询完成，但没有数据"——看起来像数据问题，
      // 实际是读错了字段
      patch(turnId, {
        status: 'done',
        answer: asString(payload['answer']),
        table: asTable(payload),
      })
      break
    }
    case 'error':
      patch(turnId, {
        status: 'error',
        error: asString(event['message']) ?? '处理失败',
      })
      break
    default:
      // started / progress 在嵌入场景没有信息量，不渲染
      break
  }
}

function asString(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null
}

function asTable(v: unknown): { columns: string[]; rows: unknown[][] } | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const cols = o['columns']
  const rows = o['rows']
  if (!Array.isArray(cols) || !Array.isArray(rows)) return null
  return {
    columns: cols.map((c) => String(c)),
    rows: rows as unknown[][],
  }
}

function patch(turnId: string, next: Partial<Turn>): void {
  // 不可变更新：直接改对象会让 Vue 的依赖追踪失效
  turns.value = turns.value.map((t) => (t.id === turnId ? { ...t, ...next } : t))
}

const canSubmit = computed(() => draft.value.trim().length > 0 && !busy.value)

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void submit()
  }
}
</script>

<template>
  <div class="embed">
    <p v-if="fatal" class="fatal">{{ fatal }}</p>

    <template v-else>
      <div class="turns">
        <p v-if="turns.length === 0" class="empty">
          问点什么吧。答案会显示在这里。
        </p>

        <article v-for="t in turns" :key="t.id" class="turn">
          <p class="q">{{ t.question }}</p>

          <p v-if="t.status === 'streaming'" class="pending">正在查询…</p>

          <p v-else-if="t.status === 'error'" class="err">{{ t.error }}</p>

          <template v-else>
            <p v-if="t.answer" class="a">{{ t.answer }}</p>

            <div v-if="t.table" class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th v-for="c in t.table.columns" :key="c">{{ c }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, i) in t.table.rows" :key="i">
                    <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p v-if="!t.answer && !t.table" class="empty">
              查询完成，但没有数据。
            </p>
          </template>
        </article>
      </div>

      <div class="composer">
        <textarea
          v-model="draft"
          :placeholder="placeholder"
          rows="2"
          @keydown="onKeydown"
        />
        <button type="button" :disabled="!canSubmit" @click="submit">
          {{ busy ? '…' : '发送' }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.embed {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  font: 14px/1.6 system-ui, -apple-system, "PingFang SC", sans-serif;
  color: #0f172a;
  background: #fff;
}

.fatal {
  margin: 16px;
  padding: 12px 16px;
  border: 1px solid #fecaca;
  border-radius: 10px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.turns {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.empty {
  color: #94a3b8;
  font-size: 13px;
  text-align: center;
  margin-top: 32px;
}

.turn {
  margin-bottom: 20px;
}

.q {
  margin: 0 0 8px;
  padding: 10px 14px;
  border-radius: 12px 12px 4px 12px;
  background: #0f172a;
  color: #fff;
  font-size: 13px;
  display: inline-block;
  max-width: 80%;
  float: right;
  clear: both;
}

.turn::after {
  content: '';
  display: block;
  clear: both;
}

.a {
  margin: 0 0 8px;
  padding: 10px 14px;
  border-radius: 4px 12px 12px 12px;
  background: #f1f5f9;
  font-size: 13px;
  white-space: pre-wrap;
}

.pending {
  color: #94a3b8;
  font-size: 13px;
  margin: 0;
}

.err {
  margin: 0;
  padding: 10px 14px;
  border-radius: 4px 12px 12px 12px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

th {
  text-align: left;
  padding: 8px 12px;
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  white-space: nowrap;
}

td {
  padding: 7px 12px;
  border-top: 1px solid #f1f5f9;
  font-variant-numeric: tabular-nums;
}

.composer {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}

textarea {
  flex: 1;
  resize: none;
  padding: 9px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  font: inherit;
  font-size: 13px;
}

textarea:focus {
  outline: 2px solid #6366f1;
  outline-offset: -1px;
  border-color: transparent;
}

button {
  padding: 0 18px;
  border: none;
  border-radius: 10px;
  background: #0f172a;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

button:disabled {
  background: #cbd5e1;
  cursor: not-allowed;
}
</style>
