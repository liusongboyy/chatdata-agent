import { ApiError, csrfToken, del, get, post } from './http'

/**
 * 问数接口。**流式**。
 *
 * ## 为什么不用 EventSource
 *
 * `EventSource` 只支持 GET，而这个端点需要 POST（问题可能很长，放 URL 里不合适，
 * 也会进访问日志）。所以用 fetch + ReadableStream 手工解析 SSE 帧。
 *
 * ## 事件契约
 *
 * 与后端 `AskEngineStreamController` 的 `toPayload` 一一对应：
 * `started` → `progress`（可多次）→ `result` | `error`。
 */

/** 处理阶段。与后端 `AskEvent.Stage` 对齐。 */
export type AskStage =
  | 'CHECKING_PERMISSION'
  | 'GENERATING_DSL'
  | 'RENDERING_SQL'
  /** 分析编排：把问题拆成若干次取数 */
  | 'PLANNING'
  | 'EXECUTING'
  /** 分析编排：根据算好的事实写结论 */
  | 'SYNTHESIZING'

/** 失败码。与后端 `AskEvent.FailureCode` 对齐。 */
export type FailureCode =
  | 'DENIED'
  | 'NOT_UNDERSTOOD'
  | 'DSL_FAILED'
  | 'SEMANTIC_ERROR'
  | 'EXECUTION_FAILED'
  /** 引擎没配置好（模型密钥缺失等）。**重试不会成功**，与可重试的失败分开。 */
  | 'ENGINE_NOT_READY'

export interface AskStartedEvent {
  type: 'started'
}

export interface AskProgressEvent {
  type: 'progress'
  stage: AskStage
  message: string
}

export interface AskResultEvent {
  type: 'result'
  /** 本轮归属的会话 id。新会话第一次提问后后端才分配，前端据此落定本地 id。 */
  conversationId?: string
  /**
   * 产生这条答案的数据域。
   *
   * 反馈必须落在**这个域**上：域错了，审核通过后写入的范例会被别的域检索到。
   * 能力答复与数据问题都有值。
   */
  domainId: string | null
  columns: string[]
  rows: unknown[][]
  rowCount: number
  maskedColumns: string[]
  durationMs: number
  sql: string
  /**
   * **参数已内联**的 SQL，界面展示与复制用。
   *
   * `sql` 里是 `?` 占位符（参数化执行语句），直接展示用户看不出查了什么条件——
   * 比如 `WHERE org_path LIKE ?` 说明不了查的是哪个组织。
   *
   * 兼容旧后端：可能为空，取不到时回退到 `sql`。
   */
  sqlDisplay?: string
  /** 后端推荐的图表形态。前端据此选默认视图，用户可切换。 */
  chart: ChartSpec
  /**
   * 文字答复。**有值时说明这不是一次查数**，而是"能力问题"的回答
   * （如"这里能分析哪些数据"）——此时 `rows` 为空，界面不该显示表格或"无数据"提示。
   */
  answer: string | null
  /**
   * 系统实际理解到的事实。见后端 AskUnderstanding。
   *
   * **直连模式为 null**：那一模式没有 DSL，也就没有可回显的结构化事实。
   */
  understanding: AskUnderstanding | null
  /**
   * 本次结果来自哪种模式。
   *
   * `RAW` = 直连模式：只勾了表、模型自己写 SQL，**没有行级/列级权限**。
   * 界面必须把这个降级明示给用户——否则他会以为看到的范围和别人一样。
   */
  mode: 'MODELED' | 'RAW'
  /**
   * 本次实际生效的 DSL。**直连模式为 null**——那一模式没有中间表示。
   *
   * 回传它是为了让前端能**改掉一个条件再算一次**（`recompute`）。
   * 它不是权限凭据——重算时判定主体仍从令牌解析，见后端 `AskService.recompute`。
   */
  dsl: AskDsl | null
  /** 分析编排结果；单查询为 null */
  analysis?: AnalysisPayload | null
  /** 降级说明（如"分析计划未能生成，已回退为单次查询"）。有值时必须展示 */
  notice?: string | null
}

/** 问数 DSL。与后端 `AskDsl` 对齐。 */
export interface AskDsl {
  metrics: string[]
  dimensions: string[]
  timeRange: { kind: string | null; start: string | null; end: string | null } | null
  filters: { field: string; op: string; value: string | null }[]
  granularity: 'DAY' | 'MONTH' | 'YEAR'
}

/**
 * 「我这次理解成了什么」。
 *
 * **四个槽位永远都在，空的就是空数组/null** —— 界面必须把空槽也画出来。
 * 这一块唯一的用途是让用户发现"它漏了我说的条件"，而漏掉的那一项
 * 如果因为空就不显示，用户就永远看不出来。见后端 AskUnderstanding 的说明。
 */
export interface AskUnderstanding {
  metrics: string[]
  dimensions: string[]
  /** null 表示**这次查询没有时间限制**——这本身是要传达的信息，不能隐藏 */
  time: { kind: string | null; from: string | null; to: string | null } | null
  filters: { field: string; op: string; value: string | null }[]
  /** 行级权限决定的数据范围。restricted=false 表示能看到全部数据 */
  dataScope: { restricted: boolean; text: string | null }
  /** 「同比」/「环比」；null 表示这次没做对比 */
  compare: string | null
}

export interface ChartSpec {
  form: 'TABLE' | 'BAR' | 'LINE' | 'PIE' | 'STAT'
  categoryField: string | null
  valueFields: string[]
  seriesField: string | null
  reason: string
}

export interface AskErrorEvent {
  type: 'error'
  code: FailureCode
  message: string
  /** 失败时也带会话 id：否则「首次提问就失败」会让下一次重试开出新会话。 */
  conversationId?: string
}

export type AskEvent = AskStartedEvent | AskProgressEvent | AskResultEvent | AskErrorEvent

export interface AskParams {
  question: string
  /** 数据域 id。省略时后端取助理的主域，或第一个启用中的域。 */
  domainId?: string
  sessionId?: string
  /** 助理 id。带上它会先过助理层的权限闸门。 */
  assistantId?: string
  /**
   * 是否走分析编排（拆成多次查询 + 结论）。
   *
   * 缺省 false：编排会多花几次查询与两次模型调用，不该由系统替用户决定。
   */
  analyze?: boolean
}

/** 一个分析步骤的展示形态。 */
export interface AnalysisStep {
  id: string
  label: string
  purpose: string
  status: 'OK' | 'FAILED'
  error: string | null
  columns: string[]
  rows: unknown[][]
  rowCount: number
  maskedColumns: string[]
  /** 参数化 SQL，排查用 */
  sql: string | null
  /** 参数已内联的 SQL，展示用 */
  sqlDisplay: string | null
  dsl: AskDsl | null
  durationMs: number
  /** 这一步的图表推荐（后端按该步的 DSL 与数据形状给） */
  chart: ChartSpec | null
}

/** 由 Java 算出来的事实。前端只展示，不重新计算。 */
export interface AnalysisFinding {
  kind: 'TREND' | 'CONTRIBUTION' | 'COMPARE' | 'OUTLIER'
  stepId: string
  text: string
  evidence: Record<string, unknown>
}

export interface AnalysisPayload {
  intent: string
  /** 结论。为空时前端只展示 facts 与步骤 */
  narrative: string | null
  queryCount: number
  /** 因行数/耗时上限被截断——必须在界面上说明 */
  truncated: boolean
  findings: AnalysisFinding[]
  steps: AnalysisStep[]
}

/**
 * 发起一次问数，逐个事件回调。
 *
 * @param onEvent 每收到一个事件调用一次
 * @param signal  用于取消（用户离开页面、或点了停止）
 * @throws ApiError 请求本身失败（未登录、服务繁忙等）。**问数过程中的失败走 error 事件**，
 *                 不抛异常——那是业务结果，不是传输故障
 */
export async function askStream(
  params: AskParams,
  onEvent: (event: AskEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const csrf = csrfToken()
  const response = await fetch('/api/ask/engine', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
    },
    body: JSON.stringify(params),
    ...(signal ? { signal } : {}),
  })

  if (!response.ok) {
    throw new ApiError(response.status, describeStatus(response.status))
  }
  if (!response.body) {
    throw new ApiError(500, '服务未返回流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      // stream: true 让多字节的 UTF-8 字符（中文）在跨 chunk 时正确拼接。
      // 不加这个参数会在字符中间切断，中文变乱码。
      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行分隔帧
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)

        const event = parseFrame(frame)
        if (event) onEvent(event)

        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    reader.releaseLock()
  }
}

/** 解析一帧 SSE。格式：`event:名字\ndata:JSON`。 */
function parseFrame(frame: string): AskEvent | null {
  let data = ''
  for (const line of frame.split('\n')) {
    if (line.startsWith('data:')) {
      data += line.slice(5).trim()
    }
  }
  if (!data) return null

  try {
    return JSON.parse(data) as AskEvent
  } catch {
    // 单帧解析失败不该中断整条流——后面的帧还有用。
    // 但要留下痕迹，否则"结果莫名丢失"会无从排查。
    console.warn('[ask] 事件帧解析失败，已跳过', data.slice(0, 120))
    return null
  }
}

function describeStatus(status: number): string {
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 503) return '服务繁忙，请稍后重试'
  return `请求失败（HTTP ${status}）`
}

/** 反馈类型。与后端 `FeedbackType` 对齐。 */
export type FeedbackType = 'HELPFUL' | 'WRONG_ANSWER' | 'SQL_CORRECTION'

export interface FeedbackParams {
  domainId: string
  /** 显式允许 undefined：tsconfig 开了 exactOptionalPropertyTypes */
  assistantId?: string | undefined
  question: string
  /** 系统实际执行的 SQL；能力答复为 null。 */
  generatedSql?: string | null | undefined
  /** 用户修正后的 SQL；仅 SQL_CORRECTION 使用。 */
  correctedSql?: string | null | undefined
  note?: string | null | undefined
  type: FeedbackType
}

/**
 * 提交一条反馈。
 *
 * 提交后一律是待审状态——**不会立刻影响之后的回答**。
 * 管理员审核通过后，修正 SQL 才会进入该域的范例库。
 */
export async function submitFeedback(params: FeedbackParams): Promise<{ id: number }> {
  return await post<{ id: number }>('/api/ask/feedback', params)
}

/** 当前域的能力说明。 */
export interface Capabilities {
  domainName: string | null
  fields: string[]
}

/**
 * 取当前域能问的字段。
 *
 * 存在的理由：用户会问「你能问哪些数据」。那**不是数据问题，是能力问题**——
 * 让模型把它翻译成 SQL 只会得到一个语法正确但没有意义的查询，然后失败。
 * 界面直接回答它。
 */
export async function fetchCapabilities(domainId?: string, assistantId?: string): Promise<Capabilities> {
  const params = new URLSearchParams()
  if (domainId) params.set('domainId', domainId)
  if (assistantId) params.set('assistantId', assistantId)
  const query = params.toString() ? `?${params.toString()}` : ''
  const response = await fetch(`/api/ask/capabilities${query}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    return { domainName: null, fields: [] }
  }
  return (await response.json()) as Capabilities
}

/** 当前用户可用的助理。 */
export interface UsableAssistant {
  id: string
  name: string
  avatar: string | null
  welcome: string | null
  showSql: boolean
  hasMultipleDomains: boolean
  /** 推荐问题（FR-4.5）。空状态用它做引导，这样内容随助理变化。 */
  questions: string[]
}

/**
 * 取当前用户**能用**的助理。
 *
 * 后端只返回能用的——把不能用的名字发过来等于告诉无权限的人"存在哪些助理"。
 * 未登录时返回空数组而不是报错，登录页也要能正常加载。
 */
export async function fetchAssistants(): Promise<UsableAssistant[]> {
  const response = await fetch('/api/ask/assistants', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) return []
  return (await response.json()) as UsableAssistant[]
}

/**
 * 基于给定的 DSL 重算，**不经过模型**（后端 FR-5.5）。
 *
 * 用途是"改掉一个条件再算一次"：查出 0 行时，最常见的下一步是去掉时间范围
 * 或某个筛选重试。走模型重问一次既慢又贵，而且模型很可能把同一个问题
 * 翻译成同一个 DSL——原地打转。
 *
 * 失败按 HTTP 状态码抛 {@link ApiError}（与流式端点不同：那边问数过程中的失败
 * 走 `error` 事件，因为那属于业务结果；这里是请求本身失败）。
 */
export async function recompute(params: {
  /** 原提问，仅用于审计留痕——重算不是一次新提问 */
  question?: string
  domainId?: string
  sessionId?: string
  assistantId?: string
  dsl: AskDsl
}): Promise<AskResultEvent> {
  const response = await fetch('/api/ask/recompute', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(params),
  })

  if (!response.ok) {
    // 后端在错误体里带了给用户看的说明（权限拒绝、"没听懂"这类用户能自己处理的），
    // 优先用它；拿不到才退回按状态码的通用说明
    const detail = (await response.json().catch(() => null)) as { message?: string } | null
    throw new ApiError(response.status, detail?.message ?? describeStatus(response.status))
  }

  return (await response.json()) as AskResultEvent
}


/* ── 会话历史 ───────────────────────────────────────────────────────────── */

export interface ConversationSummary {
  id: string
  assistantId: string | null
  title: string
  preview: string | null
  messageCount: number
  createdAt: string
  updatedAt: string
}

export interface ConversationPage {
  items: ConversationSummary[]
  total: number
  page: number
  size: number
}

export interface ConversationMessage {
  id: number
  seq: number
  role: 'USER' | 'ASSISTANT'
  kind: 'question' | 'recompute'
  content: string | null
  /** 助理消息：完整的结果事件；失败时为 null。 */
  result: AskResultEvent | null
  status: 'SUCCESS' | 'FAILED' | 'STREAMING'
  failureCode: string | null
  failureMessage: string | null
  createdAt: string
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[]
}

/** 当前用户的会话列表，按最后活跃时间倒序。 */
export async function listConversations(params: {
  page?: number
  size?: number
  keyword?: string
  assistantId?: string
} = {}): Promise<ConversationPage> {
  const query = new URLSearchParams()
  query.set('page', String(params.page ?? 1))
  query.set('size', String(params.size ?? 20))
  if (params.keyword?.trim()) query.set('keyword', params.keyword.trim())
  if (params.assistantId) query.set('assistantId', params.assistantId)
  return await get<ConversationPage>(`/api/ask/conversations?${query.toString()}`)
}

/** 会话详情（含全部消息，按时间升序）。 */
export async function fetchConversation(id: string): Promise<ConversationDetail> {
  return await get<ConversationDetail>(`/api/ask/conversations/${encodeURIComponent(id)}`)
}

/** 删除会话（软删除）。 */
export async function deleteConversation(id: string): Promise<void> {
  await del<null>(`/api/ask/conversations/${encodeURIComponent(id)}`)
}
